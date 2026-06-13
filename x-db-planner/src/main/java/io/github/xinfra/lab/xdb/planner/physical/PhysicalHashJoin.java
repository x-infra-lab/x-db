package io.github.xinfra.lab.xdb.planner.physical;

import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.planner.logical.JoinType;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class PhysicalHashJoin implements PhysicalPlan {
    private final PhysicalPlan buildSide;
    private final PhysicalPlan probeSide;
    private final JoinType joinType;
    private final Expression condition;
    private final List<Expression> buildKeys;
    private final List<Expression> probeKeys;

    public PhysicalHashJoin(PhysicalPlan buildSide, PhysicalPlan probeSide,
                            JoinType joinType, Expression condition,
                            List<Expression> buildKeys, List<Expression> probeKeys) {
        this.buildSide = buildSide;
        this.probeSide = probeSide;
        this.joinType = joinType;
        this.condition = condition;
        this.buildKeys = buildKeys;
        this.probeKeys = probeKeys;
    }

    public PhysicalPlan buildSide() { return buildSide; }
    public PhysicalPlan probeSide() { return probeSide; }
    public JoinType joinType() { return joinType; }
    public Expression condition() { return condition; }
    public List<Expression> buildKeys() { return buildKeys; }
    public List<Expression> probeKeys() { return probeKeys; }

    @Override
    public List<PhysicalPlan> children() { return Arrays.asList(buildSide, probeSide); }

    @Override
    public List<ColumnInfo> outputSchema() {
        List<ColumnInfo> schema = new ArrayList<>(buildSide.outputSchema());
        schema.addAll(probeSide.outputSchema());
        return schema;
    }

    @Override
    public double estimatedCost() {
        return buildSide.estimatedCost() + probeSide.estimatedCost() +
                buildSide.estimatedRowCount() * 3.0 + probeSide.estimatedRowCount() * 1.5;
    }

    @Override
    public long estimatedRowCount() {
        return buildSide.estimatedRowCount() * probeSide.estimatedRowCount() /
                Math.max(1, Math.max(buildSide.estimatedRowCount(), probeSide.estimatedRowCount()));
    }

    @Override
    public String explain(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indentStr(indent)).append("PhysicalHashJoin(").append(joinType);
        if (condition != null) sb.append(", on=").append(condition.toSQL());
        sb.append(")\n");
        sb.append(indentStr(indent + 1)).append("[build]\n").append(buildSide.explain(indent + 2)).append("\n");
        sb.append(indentStr(indent + 1)).append("[probe]\n").append(probeSide.explain(indent + 2));
        return sb.toString();
    }
}
