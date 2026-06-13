package io.github.xinfra.lab.xdb.expression;

public final class BetweenExpr implements Expression {
    private final Expression expr;
    private final Expression low;
    private final Expression high;
    private final boolean not;

    public BetweenExpr(Expression expr, Expression low, Expression high, boolean not) {
        this.expr = expr;
        this.low = low;
        this.high = high;
        this.not = not;
    }

    @Override
    public Datum eval(EvalContext ctx, Row row) {
        Datum v = expr.eval(ctx, row);
        Datum lo = low.eval(ctx, row);
        Datum hi = high.eval(ctx, row);
        if (v.isNull() || lo.isNull() || hi.isNull()) return Datum.nil();
        boolean between = DatumComparator.compare(v, lo) >= 0
                && DatumComparator.compare(v, hi) <= 0;
        return Datum.of((between ^ not) ? 1L : 0L);
    }

    @Override public DataType returnType() { return DataType.BOOLEAN; }
}
