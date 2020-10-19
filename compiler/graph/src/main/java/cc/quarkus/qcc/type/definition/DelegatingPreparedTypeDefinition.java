package cc.quarkus.qcc.type.definition;

/**
 *
 */
public abstract class DelegatingPreparedTypeDefinition extends DelegatingResolvedTypeDefinition implements PreparedTypeDefinition {
    protected DelegatingPreparedTypeDefinition() {}

    protected abstract PreparedTypeDefinition getDelegate();

    public PreparedTypeDefinition prepare() {
        return this;
    }

    public PreparedTypeDefinition validate() {
        return this;
    }

    public PreparedTypeDefinition resolve() {
        return this;
    }

    public PreparedTypeDefinition getSuperClass() {
        return getDelegate().getSuperClass();
    }

    public PreparedTypeDefinition getInterface(final int index) throws IndexOutOfBoundsException {
        return getDelegate().getInterface(index);
    }

    public FieldContainer getStaticFields() {
        return getDelegate().getStaticFields();
    }

    public InitializedTypeDefinition initialize() throws InitializationFailedException {
        return getDelegate().initialize();
    }
}
