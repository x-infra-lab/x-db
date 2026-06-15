package io.github.xinfra.lab.xdb.planner;

import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.planner.logical.LogicalPlan;

public final class ScalarSubqueryRef implements Expression {
    private final LogicalPlan subPlan;

    public ScalarSubqueryRef(LogicalPlan subPlan) {
        this.subPlan = subPlan;
    }

    public LogicalPlan subPlan() { return subPlan; }

    @Override
    public Datum eval(EvalContext ctx, Row row) {
        throw new UnsupportedOperationException("ScalarSubqueryRef must be materialized before execution");
    }

    @Override
    public DataType returnType() {
        if (subPlan.outputSchema().isEmpty()) return DataType.VARCHAR;
        return subPlan.outputSchema().get(0).getType();
    }
}
