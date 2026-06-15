package io.github.xinfra.lab.xdb.planner.physical;

import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.planner.logical.JoinType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PhysicalNestedLoopJoin implements PhysicalPlan {
    private final PhysicalPlan outer;
    private final PhysicalPlan inner;
    private final JoinType joinType;
    private final Expression condition;

    public PhysicalNestedLoopJoin(PhysicalPlan outer, PhysicalPlan inner,
                                  JoinType joinType, Expression condition) {
        this.outer = outer;
        this.inner = inner;
        this.joinType = joinType;
        this.condition = condition;
    }

    public PhysicalPlan outer() { return outer; }
    public PhysicalPlan inner() { return inner; }
    public JoinType joinType() { return joinType; }
    public Expression condition() { return condition; }

    @Override
    public List<PhysicalPlan> children() { return Arrays.asList(outer, inner); }

    @Override
    public List<ColumnInfo> outputSchema() {
        List<ColumnInfo> schema = new ArrayList<>(outer.outputSchema());
        schema.addAll(inner.outputSchema());
        return schema;
    }

    @Override
    public double estimatedCost() {
        return outer.estimatedCost() + outer.estimatedRowCount() * inner.estimatedCost();
    }

    @Override
    public long estimatedRowCount() {
        return outer.estimatedRowCount() * inner.estimatedRowCount() /
                Math.max(1, Math.max(outer.estimatedRowCount(), inner.estimatedRowCount()));
    }

    @Override
    public String explain(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indentStr(indent)).append("PhysicalNestedLoopJoin(").append(joinType);
        if (condition != null) sb.append(", on=").append(condition.toSQL());
        sb.append(costInfo()).append(")\n");
        sb.append(outer.explain(indent + 1)).append("\n");
        sb.append(inner.explain(indent + 1));
        return sb.toString();
    }
}
