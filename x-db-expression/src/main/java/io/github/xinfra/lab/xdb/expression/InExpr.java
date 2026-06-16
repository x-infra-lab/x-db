package io.github.xinfra.lab.xdb.expression;

import java.util.List;

public final class InExpr implements Expression {
    private final Expression expr;
    private final List<Expression> list;
    private final boolean not;

    public InExpr(Expression expr, List<Expression> list, boolean not) {
        this.expr = expr;
        this.list = list;
        this.not = not;
    }

    public Expression expr() { return expr; }
    public List<Expression> list() { return list; }
    public boolean not() { return not; }

    @Override
    public Datum eval(EvalContext ctx, Row row) {
        Datum v = expr.eval(ctx, row);
        if (v.isNull()) return Datum.nil();
        boolean found = false;
        boolean hasNull = false;
        for (Expression e : list) {
            Datum item = e.eval(ctx, row);
            if (item.isNull()) { hasNull = true; continue; }
            if (DatumComparator.compare(v, item) == 0) { found = true; break; }
        }
        if (found) return Datum.of(not ? 0L : 1L);
        if (hasNull) return Datum.nil();
        return Datum.of(not ? 1L : 0L);
    }

    @Override public DataType returnType() { return DataType.BOOLEAN; }
}
