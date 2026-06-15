package io.github.xinfra.lab.xdb.planner;

import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.planner.logical.LogicalPlan;

public final class InSubqueryRef implements Expression {
    private final Expression left;
    private final LogicalPlan subPlan;
    private final boolean not;

    public InSubqueryRef(Expression left, LogicalPlan subPlan, boolean not) {
        this.left = left;
        this.subPlan = subPlan;
        this.not = not;
    }

    public Expression left() { return left; }
    public LogicalPlan subPlan() { return subPlan; }
    public boolean isNot() { return not; }

    @Override
    public Datum eval(EvalContext ctx, Row row) {
        throw new UnsupportedOperationException("InSubqueryRef must be materialized before execution");
    }

    @Override
    public DataType returnType() { return DataType.BOOLEAN; }
}
