package io.github.xinfra.lab.xdb.expression;

public final class UnaryOp implements Expression {
    public enum Op { NOT, NEG, IS_NULL, IS_NOT_NULL }

    private final Expression operand;
    private final Op op;

    public UnaryOp(Op op, Expression operand) {
        this.op = op;
        this.operand = operand;
    }

    public Expression operand() { return operand; }
    public Op op() { return op; }

    @Override
    public Datum eval(EvalContext ctx, Row row) {
        Datum v = operand.eval(ctx, row);
        if (op == Op.IS_NULL) return Datum.of(v.isNull() ? 1L : 0L);
        if (op == Op.IS_NOT_NULL) return Datum.of(v.isNull() ? 0L : 1L);
        if (v.isNull()) return Datum.nil();
        if (op == Op.NOT) return Datum.of(v.toBoolean() ? 0L : 1L);
        // NEG
        if (v instanceof Datum.IntDatum i) return Datum.of(-i.value());
        return Datum.of(-v.toDouble());
    }

    @Override
    public DataType returnType() {
        return switch (op) {
            case NOT, IS_NULL, IS_NOT_NULL -> DataType.BOOLEAN;
            case NEG -> operand.returnType();
        };
    }
}
