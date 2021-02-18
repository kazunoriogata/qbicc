package cc.quarkus.qcc.plugin.opt;

import java.util.HashSet;
import java.util.Set;

import cc.quarkus.qcc.context.CompilationContext;
import cc.quarkus.qcc.graph.BasicBlock;
import cc.quarkus.qcc.graph.BlockEntry;
import cc.quarkus.qcc.graph.Goto;
import cc.quarkus.qcc.graph.Node;
import cc.quarkus.qcc.graph.NodeVisitor;
import cc.quarkus.qcc.graph.PhiValue;
import cc.quarkus.qcc.graph.Value;
import cc.quarkus.qcc.graph.ValueHandle;

/**
 * A copier which removes all goto nodes that are only entered by one entry block.
 */
public class GotoRemovingVisitor implements NodeVisitor.Delegating<Node.Copier, Value, Node, BasicBlock, ValueHandle> {
    private final CompilationContext context;
    private final NodeVisitor<Node.Copier, Value, Node, BasicBlock, ValueHandle> delegate;
    private final Set<BasicBlock> deleted = new HashSet<>();

    public GotoRemovingVisitor(final CompilationContext context, final NodeVisitor<Node.Copier, Value, Node, BasicBlock, ValueHandle> delegate) {
        this.context = context;
        this.delegate = delegate;
    }

    public NodeVisitor<Node.Copier, Value, Node, BasicBlock, ValueHandle> getDelegateNodeVisitor() {
        return delegate;
    }

    public BasicBlock visit(final Node.Copier param, final Goto node) {
        BasicBlock target = node.getResumeTarget();
        if (target.getIncoming().size() == 1) {
            // delete the goto target and fold it into the current block
            deleted.add(target);
            param.copyNode(node.getDependency());
            return param.copyTerminator(target.getTerminator());
        } else {
            return getDelegateTerminatorVisitor().visit(param, node);
        }
    }

    public Value visit(final Node.Copier param, final PhiValue node) {
        if (deleted.contains(node.getPinnedBlock())) {
            // the deleted block only has one incoming block, so the phi must also have only one valid incoming value
            return param.copyValue(node.getValueForInput(node.getPinnedBlock().getIncoming().iterator().next().getTerminator()));
        } else {
            return getDelegateValueVisitor().visit(param, node);
        }
    }

    public Node visit(final Node.Copier param, final BlockEntry node) {
        if (deleted.contains(node.getPinnedBlock())) {
            // just delete it
            return param.getBlockBuilder().nop();
        } else {
            return getDelegateActionVisitor().visit(param, node);
        }
    }
}
