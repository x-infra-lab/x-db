package io.github.xinfra.lab.xdb.expression;

import java.util.List;

public final class CaseWhenExpr implements Expression {
    public record WhenClause(Expression condition, Expression result) {}

    private final Expression compareExpr;
    private final List<WhenClause> whenClauses;
    private final Expression elseExpr;

    public CaseWhenExpr(Expression compareExpr, List<WhenClause> whenClauses, Expression elseExpr) {
        this.compareExpr = compareExpr;
        this.whenClauses = whenClauses;
        this.elseExpr = elseExpr;
    }

    @Override
    public Datum eval(EvalContext ctx, Row row) {
        if (compareExpr != null) {
            Datum cmpVal = compareExpr.eval(ctx, row);
            for (WhenClause wc : whenClauses) {
                Datum whenVal = wc.condition.eval(ctx, row);
                if (!cmpVal.isNull() && !whenVal.isNull()
                        && DatumComparator.compare(cmpVal, whenVal) == 0) {
                    return wc.result.eval(ctx, row);
                }
            }
        } else {
            for (WhenClause wc : whenClauses) {
                Datum cond = wc.condition.eval(ctx, row);
                if (!cond.isNull() && cond.toBoolean()) {
                    return wc.result.eval(ctx, row);
                }
            }
        }
        return elseExpr != null ? elseExpr.eval(ctx, row) : Datum.nil();
    }

    @Override
    public DataType returnType() {
        if (!whenClauses.isEmpty()) return whenClauses.get(0).result.returnType();
        return elseExpr != null ? elseExpr.returnType() : DataType.NULL;
    }
}
