package org.qbicc.plugin.dispatch;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import io.smallrye.common.constraint.Assert;
import org.jboss.logging.Logger;
import org.qbicc.context.AttachmentKey;
import org.qbicc.context.CompilationContext;
import org.qbicc.graph.literal.Literal;
import org.qbicc.graph.literal.LiteralFactory;
import org.qbicc.graph.literal.ProgramObjectLiteral;
import org.qbicc.object.Data;
import org.qbicc.object.DataDeclaration;
import org.qbicc.object.Function;
import org.qbicc.object.FunctionDeclaration;
import org.qbicc.object.Linkage;
import org.qbicc.object.Section;
import org.qbicc.plugin.coreclasses.RuntimeMethodFinder;
import org.qbicc.plugin.correctness.RuntimeInitManager;
import org.qbicc.plugin.reachability.ReachabilityInfo;
import org.qbicc.type.ArrayType;
import org.qbicc.type.CompoundType;
import org.qbicc.type.FunctionType;
import org.qbicc.type.TypeSystem;
import org.qbicc.type.WordType;
import org.qbicc.type.definition.DefinedTypeDefinition;
import org.qbicc.type.definition.LoadedTypeDefinition;
import org.qbicc.type.definition.classfile.ClassFile;
import org.qbicc.type.definition.element.GlobalVariableElement;
import org.qbicc.type.definition.element.InitializerElement;
import org.qbicc.type.definition.element.MethodElement;
import org.qbicc.type.descriptor.BaseTypeDescriptor;
import org.qbicc.type.generic.BaseTypeSignature;

public class DispatchTables {
    private static final Logger slog = Logger.getLogger("org.qbicc.plugin.dispatch.stats");
    private static final Logger tlog = Logger.getLogger("org.qbicc.plugin.dispatch.tables");

    private static final AttachmentKey<DispatchTables> KEY = new AttachmentKey<>();

    private final CompilationContext ctxt;
    private final Map<LoadedTypeDefinition, VTableInfo> vtables = new ConcurrentHashMap<>();
    private final Map<LoadedTypeDefinition, ITableInfo> itables = new ConcurrentHashMap<>();
    private final Set<LoadedTypeDefinition> classesWithITables = ConcurrentHashMap.newKeySet();
    private final Set<InitializerElement> runtimeInitializers = ConcurrentHashMap.newKeySet();
    private GlobalVariableElement vtablesGlobal;
    private GlobalVariableElement itablesGlobal;
    private GlobalVariableElement rtinitsGlobal;
    private CompoundType itableDictType;

    // Used to accumulate statistics
    private int emittedVTableCount;
    private int emittedVTableBytes;
    private int emittedClassITableCount;
    private int emittedClassITableBytes;
    private int emittedClassITableDictBytes;
    private int emittedClassITableDictCount;

    private DispatchTables(final CompilationContext ctxt) {
        this.ctxt = ctxt;
    }

    public static DispatchTables get(CompilationContext ctxt) {
        DispatchTables dt = ctxt.getAttachment(KEY);
        if (dt == null) {
            dt = new DispatchTables(ctxt);
            DispatchTables appearing = ctxt.putAttachmentIfAbsent(KEY, dt);
            if (appearing != null) {
                dt = appearing;
            }
        }
        return dt;
    }

    public VTableInfo getVTableInfo(LoadedTypeDefinition cls) {
        return vtables.get(cls);
    }

    public ITableInfo getITableInfo(LoadedTypeDefinition cls) { return itables.get(cls); }

    void buildFilteredVTable(LoadedTypeDefinition cls) {
        tlog.debugf("Building VTable for %s", cls.getDescriptor());

        ArrayList<MethodElement> vtableVector = new ArrayList<>();
        for (MethodElement m: cls.getInstanceMethods()) {
            if (ctxt.wasEnqueued(m)) {
                tlog.debugf("\tadding reachable method %s%s", m.getName(), m.getDescriptor().toString());
                ctxt.registerAutoQueuedElement(m);
                vtableVector.add(m);
            }
        }
        MethodElement[] vtable = vtableVector.toArray(MethodElement.NO_METHODS);

        String vtableName = "vtable-" + cls.getInternalName().replace('/', '.');
        TypeSystem ts = ctxt.getTypeSystem();
        CompoundType.Member[] functions = new CompoundType.Member[vtable.length];
        for (int i=0; i<vtable.length; i++) {
            FunctionType funType = ctxt.getFunctionTypeForElement(vtable[i]);
            functions[i] = ts.getCompoundTypeMember("m"+i, funType.getPointer(), i*ts.getPointerSize(), ts.getPointerAlignment());
        }
        CompoundType vtableType = ts.getCompoundType(CompoundType.Tag.STRUCT, vtableName, vtable.length * ts.getPointerSize(),
            ts.getPointerAlignment(), () -> List.of(functions));

        vtables.put(cls,new VTableInfo(vtable, vtableType, vtableName));
    }

    void buildFilteredITableForInterface(LoadedTypeDefinition cls) {
        tlog.debugf("Building ITable for %s", cls.getDescriptor());

        ArrayList<MethodElement> itableVector = new ArrayList<>();
        for (MethodElement m: cls.getInstanceMethods()) {
            if (ctxt.wasEnqueued(m)) {
                tlog.debugf("\tadding invokable signature %s%s", m.getName(), m.getDescriptor().toString());
                itableVector.add(m);
            }
        }

        // Build the CompoundType for the ITable using the (arbitrary) order of selectors in itableVector
        MethodElement[] itable = itableVector.toArray(MethodElement.NO_METHODS);
        String itableName = "itable-" + cls.getInternalName().replace('/', '.');
        TypeSystem ts = ctxt.getTypeSystem();
        CompoundType.Member[] functions = new CompoundType.Member[itable.length];
        for (int i=0; i<itable.length; i++) {
            FunctionType funType = ctxt.getFunctionTypeForElement(itable[i]);
            functions[i] = ts.getCompoundTypeMember("m"+i, funType.getPointer(), i*ts.getPointerSize(), ts.getPointerAlignment());
        }
        CompoundType itableType = ts.getCompoundType(CompoundType.Tag.STRUCT, itableName, itable.length * ts.getPointerSize(),
            ts.getPointerAlignment(), () -> List.of(functions));

        itables.put(cls, new ITableInfo(itable, itableType, cls));
    }

    public void registerRuntimeInitializer(InitializerElement init) {
        runtimeInitializers.add(init);
    }

    void buildVTablesGlobal(DefinedTypeDefinition containingType) {
        GlobalVariableElement.Builder builder = GlobalVariableElement.builder("qbicc_vtables_array", BaseTypeDescriptor.V);
        // Invariant: typeIds are assigned from 1...N, where N is the number of reachable classes as computed by RTA
        // plus 18 for 8 primitive types, void, 8 primitive arrays and reference array.
        builder.setType(ctxt.getTypeSystem().getArrayType(ctxt.getTypeSystem().getVoidType().getPointer().getPointer(), vtables.size()+19));  //TODO: communicate this +19 better
        builder.setEnclosingType(containingType);
        builder.setSignature(BaseTypeSignature.V);
        vtablesGlobal = builder.build();
    }

    void buildITablesGlobal(DefinedTypeDefinition containingType) {
        TypeSystem ts = ctxt.getTypeSystem();
        CompoundType.Member itableMember = ts.getCompoundTypeMember("itable", ts.getVoidType().getPointer(), 0,  ts.getPointerAlignment());
        CompoundType.Member typeIdMember = ts.getCompoundTypeMember("typeId", ts.getTypeIdLiteralType(), ts.getPointerSize(),  ts.getTypeIdAlignment());
        itableDictType = ts.getCompoundType(CompoundType.Tag.STRUCT, "qbicc_itable_dict_entry", ts.getPointerSize() + ts.getTypeIdSize(),
            ts.getPointerAlignment(), () -> List.of(itableMember, typeIdMember));

        GlobalVariableElement.Builder builder = GlobalVariableElement.builder("qbicc_itable_dicts_array", BaseTypeDescriptor.V);
        // Invariant: typeIds are assigned from 1...N, where N is the number of reachable classes as computed by RTA
        // plus 18 for 8 primitive types, void, 8 primitive arrays and reference array.
        builder.setType(ts.getArrayType(ts.getArrayType(itableDictType, 0).getPointer(), vtables.size()+19));  //TODO: communicate this +19 better
        builder.setEnclosingType(containingType);
        builder.setSignature(BaseTypeSignature.V);
        itablesGlobal = builder.build();
    }

    void buildRTInitGlobal(DefinedTypeDefinition containingType) {
        TypeSystem ts = ctxt.getTypeSystem();
        FunctionType initType = ctxt.getFunctionTypeForInitializer();
        GlobalVariableElement.Builder builder = GlobalVariableElement.builder("qbicc_rtinit_array", BaseTypeDescriptor.V);
        builder.setType(ts.getArrayType(initType.getPointer(), RuntimeInitManager.get(ctxt).maxAssignedId()+1));
        builder.setEnclosingType(containingType);
        builder.setSignature(BaseTypeSignature.V);
        rtinitsGlobal = builder.build();
    }

    void emitVTable(LoadedTypeDefinition cls) {
        if (cls.isAbstract()) {
            return;
        }
        RuntimeMethodFinder methodFinder = RuntimeMethodFinder.get(ctxt);
        VTableInfo info = getVTableInfo(cls);
        MethodElement[] vtable = info.getVtable();
        Section section = ctxt.getImplicitSection(cls);
        HashMap<CompoundType.Member, Literal> valueMap = new HashMap<>();
        for (int i = 0; i < vtable.length; i++) {
            FunctionType funType = ctxt.getFunctionTypeForElement(vtable[i]);
            if (vtable[i].isAbstract() || vtable[i].hasAllModifiersOf(ClassFile.ACC_NATIVE)) {
                MethodElement stub = methodFinder.getMethod(vtable[i].isAbstract() ? "raiseAbstractMethodError" : "raiseUnsatisfiedLinkError");
                Function stubImpl = ctxt.getExactFunction(stub);
                FunctionDeclaration decl = section.declareFunction(stub, stubImpl.getName(), stubImpl.getValueType());
                ProgramObjectLiteral literal = ctxt.getLiteralFactory().literalOf(decl);
                valueMap.put(info.getType().getMember(i), ctxt.getLiteralFactory().bitcastLiteral(literal, ctxt.getFunctionTypeForElement(vtable[i]).getPointer()));
            } else {
                Function impl = ctxt.getExactFunctionIfExists(vtable[i]);
                if (impl == null) {
                    ctxt.error(vtable[i], "Missing method implementation for vtable of %s", cls.getInternalName());
                    continue;
                }
                if (!vtable[i].getEnclosingType().load().equals(cls)) {
                    section.declareFunction(vtable[i], impl.getName(), funType);
                }
                valueMap.put(info.getType().getMember(i), ctxt.getLiteralFactory().literalOf(impl));
            }
        }
        Literal vtableLiteral = ctxt.getLiteralFactory().literalOf(info.getType(), valueMap);
        section.addData(null, info.getName(), vtableLiteral).setLinkage(Linkage.EXTERNAL);
        emittedVTableCount += 1;
        emittedVTableBytes += info.getType().getMemberCount() * ctxt.getTypeSystem().getPointerSize();
    }

    void emitVTableTable(LoadedTypeDefinition jlo) {
        ArrayType vtablesGlobalType = ((ArrayType)vtablesGlobal.getType());
        Section section = ctxt.getImplicitSection(jlo);
        Literal[] vtableLiterals = new Literal[(int)vtablesGlobalType.getElementCount()];
        Literal zeroLiteral = ctxt.getLiteralFactory().zeroInitializerLiteralOfType(vtablesGlobalType.getElementType());
        Arrays.fill(vtableLiterals, zeroLiteral);
        for (Map.Entry<LoadedTypeDefinition, VTableInfo> e: vtables.entrySet()) {
            LoadedTypeDefinition cls = e.getKey();
            if (!cls.isAbstract()) {
                DataDeclaration decl = section.declareData(null, e.getValue().getName(), e.getValue().getType());
                ProgramObjectLiteral symbol = ctxt.getLiteralFactory().literalOf(decl);
                int typeId = cls.getTypeId();
                Assert.assertTrue(vtableLiterals[typeId].equals(zeroLiteral));
                vtableLiterals[typeId] = ctxt.getLiteralFactory().bitcastLiteral(symbol, (WordType) vtablesGlobalType.getElementType());
            }
        }
        Literal vtablesGlobalValue = ctxt.getLiteralFactory().literalOf(vtablesGlobalType, List.of(vtableLiterals));
        section.addData(null, vtablesGlobal.getName(), vtablesGlobalValue);
        slog.debugf("Root vtable[] has %d slots (%d bytes)", vtableLiterals.length, vtableLiterals.length * ctxt.getTypeSystem().getPointerSize());
        slog.debugf("Emitted %d vtables with combined size of %d bytes", emittedVTableCount, emittedVTableBytes);
    }

    public void emitITables(LoadedTypeDefinition cls) {
        if (cls.isAbstract()) {
            return;
        }
        HashSet<ITableInfo> myITables = new HashSet<>();
        cls.forEachInterfaceFullImplementedSet(i -> {
            ITableInfo iti = itables.get(i);
            if (iti != null && iti.getItable().length > 0) {
                myITables.add(iti);
            }
        });
        if (myITables.isEmpty()) {
            return;
        }

        classesWithITables.add(cls);

        LiteralFactory lf = ctxt.getLiteralFactory();
        TypeSystem ts = ctxt.getTypeSystem();
        Section cSection = ctxt.getImplicitSection(cls);

        ArrayList<Literal> itableLiterals = new ArrayList<>(myITables.size() + 1);
        RuntimeMethodFinder methodFinder = RuntimeMethodFinder.get(ctxt);
        for (ITableInfo itableInfo : myITables) {
            MethodElement[] itable = itableInfo.getItable();
            LoadedTypeDefinition currentInterface = itableInfo.getInterface();

            HashMap<CompoundType.Member, Literal> valueMap = new HashMap<>();
            for (int i = 0; i < itable.length; i++) {
                MethodElement methImpl = cls.resolveMethodElementVirtual(itable[i].getName(), itable[i].getDescriptor());
                FunctionType implType = ctxt.getFunctionTypeForElement(methImpl);
                if (methImpl == null) {
                    MethodElement icceStub = methodFinder.getMethod("raiseIncompatibleClassChangeError");
                    Function icceImpl = ctxt.getExactFunction(icceStub);
                    ProgramObjectLiteral iceeLiteral = lf.literalOf(cSection.declareFunction(icceImpl));
                    valueMap.put(itableInfo.getType().getMember(i), lf.bitcastLiteral(iceeLiteral, implType.getPointer()));
                } else if (methImpl.isAbstract()) {
                    MethodElement ameStub = methodFinder.getMethod("raiseAbstractMethodError");
                    Function ameImpl = ctxt.getExactFunction(ameStub);
                    ProgramObjectLiteral ameLiteral = lf.literalOf(cSection.declareFunction(ameImpl));
                    valueMap.put(itableInfo.getType().getMember(i), lf.bitcastLiteral(ameLiteral, implType.getPointer()));
                } else {
                    Function impl = methImpl.isNative() ? null : ctxt.getExactFunctionIfExists(methImpl);
                    if (impl == null) {
                        if (!methImpl.isNative() && ReachabilityInfo.get(ctxt).isInvokableMethod(methImpl)) {
                            ctxt.error(methImpl, "Missing method implementation for vtable of %s", cls.getInternalName());
                        } else {
                            MethodElement uleStub = methodFinder.getMethod("raiseUnsatisfiedLinkError");
                            Function uleImpl = ctxt.getExactFunction(uleStub);
                            ProgramObjectLiteral uleLiteral = lf.literalOf(cSection.declareFunction(uleImpl));
                            valueMap.put(itableInfo.getType().getMember(i), lf.bitcastLiteral(uleLiteral, implType.getPointer()));
                        }
                    } else {
                        if (!methImpl.getEnclosingType().load().equals(cls)) {
                            cSection.declareFunction(methImpl, impl.getName(), implType);
                        }
                        valueMap.put(itableInfo.getType().getMember(i), ctxt.getLiteralFactory().literalOf(impl));
                    }
                }
            }

            String functionsName = "qbicc_itable_funcs_for_"+currentInterface.getInterfaceType().toFriendlyString();
            Data data = cSection.addData(null, functionsName, lf.literalOf(itableInfo.getType(), valueMap));
            data.setLinkage(Linkage.PRIVATE);
            itableLiterals.add(lf.literalOf(itableDictType, Map.of(itableDictType.getMember("typeId"), lf.literalOf(currentInterface.getTypeId()),
                itableDictType.getMember("itable"), lf.bitcastLiteral(lf.literalOf(data), ts.getVoidType().getPointer()))));
            emittedClassITableCount += 1;
            emittedClassITableBytes += itable.length * ctxt.getTypeSystem().getPointerSize();
        }

        // zero-initialized sentinel to detect IncompatibleClassChangeErrors in dispatching search loop
        itableLiterals.add(lf.zeroInitializerLiteralOfType(itableDictType));

        cSection.addData(null, "qbicc_itable_dictionary_for_" + cls.getInternalName().replace('/', '.'),
            lf.literalOf(ts.getArrayType(itableDictType, myITables.size() + 1), itableLiterals));
        emittedClassITableDictCount += 1;
        emittedClassITableDictBytes += (myITables.size() + 1) * itableDictType.getSize();
    }

    void emitITableTable(LoadedTypeDefinition jlo) {
        ArrayType itablesGlobalType = ((ArrayType) itablesGlobal.getType());
        Section section = ctxt.getImplicitSection(jlo);
        Literal[] itableLiterals = new Literal[(int) itablesGlobalType.getElementCount()];
        Literal zeroLiteral = ctxt.getLiteralFactory().zeroInitializerLiteralOfType(itablesGlobalType.getElementType());
        Arrays.fill(itableLiterals, zeroLiteral);

        LiteralFactory lf = ctxt.getLiteralFactory();
        for (LoadedTypeDefinition cls : classesWithITables) {
            int typeId = cls.getTypeId();
            Assert.assertTrue(itableLiterals[typeId].equals(zeroLiteral));
            String dictName = "qbicc_itable_dictionary_for_"+cls.getInternalName().replace('/', '.');
            ArrayType type = ctxt.getTypeSystem().getArrayType(itableDictType, 0);
            DataDeclaration decl = section.declareData(null, dictName, type);
            ProgramObjectLiteral symLit = lf.literalOf(decl);
            itableLiterals[typeId] = symLit;
        }

        Literal itablesGlobalValue = ctxt.getLiteralFactory().literalOf(itablesGlobalType, List.of(itableLiterals));
        section.addData(null, itablesGlobal.getName(), itablesGlobalValue);
        slog.debugf("Root itable_dict[] has %d slots (%d bytes)", itableLiterals.length, itableLiterals.length * ctxt.getTypeSystem().getPointerSize());
        slog.debugf("Emitted %d itables with combined size of %d bytes", emittedClassITableCount, emittedClassITableBytes);
        slog.debugf("Emitted %d class itable dictionaries with combined size of %d bytes", emittedClassITableDictCount, emittedClassITableDictBytes);
    }

    void emitRTInitTable(LoadedTypeDefinition jlo) {
        ArrayType tableGlobalType = ((ArrayType) rtinitsGlobal.getType());
        Section section = ctxt.getImplicitSection(jlo);
        Literal[] tableLiterals = new Literal[(int) tableGlobalType.getElementCount()];
        Literal zeroLiteral = ctxt.getLiteralFactory().zeroInitializerLiteralOfType(tableGlobalType.getElementType());
        Arrays.fill(tableLiterals, zeroLiteral);

        LiteralFactory lf = ctxt.getLiteralFactory();
        for (InitializerElement init : runtimeInitializers) {
            int index = init.getLowerIndex();
            Assert.assertTrue(tableLiterals[index].equals(zeroLiteral));
            Function impl = ctxt.getExactFunctionIfExists(init);
            section.declareFunction(init, impl.getName(), ctxt.getFunctionTypeForElement(init));
            tableLiterals[index] = lf.literalOf(impl);
        }
        section.addData(null, rtinitsGlobal.getName(), lf.literalOf(tableGlobalType, List.of(tableLiterals)));
    }

    public GlobalVariableElement getVTablesGlobal() {
        return vtablesGlobal;
    }

    public GlobalVariableElement getITablesGlobal() {
        return itablesGlobal;
    }

    public GlobalVariableElement getRTInitsGlobal() {
        return rtinitsGlobal;
    }

    public CompoundType getItableDictType() {
        return itableDictType;
    }

    public int getVTableIndex(MethodElement target) {
        LoadedTypeDefinition definingType = target.getEnclosingType().load();
        VTableInfo info = getVTableInfo(definingType);
        if (info != null) {
            MethodElement[] vtable = info.getVtable();
            for (int i = 0; i < vtable.length; i++) {
                if (target.getName().equals(vtable[i].getName()) && target.getDescriptor().equals(vtable[i].getDescriptor())) {
                    return i;
                }
            }
        }
        ctxt.error("No vtable entry found for "+target);
        return 0;
    }

    public int getITableIndex(MethodElement target) {
        LoadedTypeDefinition definingType = target.getEnclosingType().load();
        ITableInfo info = getITableInfo(definingType);
        if (info != null) {
            MethodElement[] itable = info.getItable();
            for (int i = 0; i < itable.length; i++) {
                if (target.getName().equals(itable[i].getName()) && target.getDescriptor().equals(itable[i].getDescriptor())) {
                    return i;
                }
            }
        }
        ctxt.error("No itable entry found for "+target);
        return 0;
    }

    public static final class VTableInfo {
        private final MethodElement[] vtable;
        private final CompoundType type;
        private final String name;

        VTableInfo(MethodElement[] vtable, CompoundType type, String name) {
            this.vtable = vtable;
            this.type = type;
            this.name = name;
        }

        public MethodElement[] getVtable() { return vtable; }
        public String getName() { return name; }
        public CompoundType getType() { return  type; }
    }

    public static final class ITableInfo {
        private final LoadedTypeDefinition myInterface;
        private final MethodElement[] itable;
        private final CompoundType type;

        ITableInfo(MethodElement[] itable, CompoundType type, LoadedTypeDefinition myInterface) {
            this.myInterface = myInterface;
            this.itable = itable;
            this.type = type;
        }

        public LoadedTypeDefinition getInterface() { return myInterface; }
        public MethodElement[] getItable() { return itable; }
        public CompoundType getType() { return type; }
    }
}
