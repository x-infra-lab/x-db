package io.github.xinfra.lab.xdb.planner;

import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.planner.logical.LogicalPlan;

public final class ExistsSubqueryRef implements Expression {
    private final LogicalPlan subPlan;

    public ExistsSubqueryRef(LogicalPlan subPlan) {
        this.subPlan = subPlan;
    }

    public LogicalPlan subPlan() { return subPlan; }

    @Override
    public Datum eval(EvalContext ctx, Row row) {
        throw new UnsupportedOperationException("ExistsSubqueryRef must be materialized before execution");
    }

    @Override
    public DataType returnType() { return DataType.BOOLEAN; }
}
