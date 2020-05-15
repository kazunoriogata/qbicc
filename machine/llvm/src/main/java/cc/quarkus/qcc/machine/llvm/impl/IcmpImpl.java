package cc.quarkus.qcc.machine.llvm.impl;

import java.io.IOException;

import cc.quarkus.qcc.machine.llvm.IntCondition;

final class IcmpImpl extends AbstractBinary {
    private final IntCondition cond;

    IcmpImpl(final BasicBlockImpl block, final IntCondition cond, final AbstractValue type, final AbstractValue arg1, final AbstractValue arg2) {
        super(block, type, arg1, arg2);
        this.cond = cond;
    }

    public Appendable appendTo(final Appendable target) throws IOException {
        return appendTrailer(super.appendTo(target).append(' ').append("icmp").append(' ').append(cond.name()));
    }
}
