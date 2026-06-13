package io.github.xinfra.lab.xdb.planner.physical;

import io.github.xinfra.lab.xdb.expression.AggFunction;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;

import java.util.Collections;
import java.util.List;

public class PhysicalHashAgg implements PhysicalPlan {
    private final PhysicalPlan child;
    private final List<Expression> groupByExprs;
    private final List<AggFunction> aggFunctions;
    private final List<ColumnInfo> outputColumns;

    public PhysicalHashAgg(PhysicalPlan child, List<Expression> groupByExprs,
                           List<AggFunction> aggFunctions, List<ColumnInfo> outputColumns) {
        this.child = child;
        this.groupByExprs = groupByExprs;
        this.aggFunctions = aggFunctions;
        this.outputColumns = outputColumns;
    }

    public PhysicalPlan child() { return child; }
    public List<Expression> groupByExprs() { return groupByExprs; }
    public List<AggFunction> aggFunctions() { return aggFunctions; }

    @Override
    public List<PhysicalPlan> children() { return Collections.singletonList(child); }

    @Override
    public List<ColumnInfo> outputSchema() { return outputColumns; }

    @Override
    public double estimatedCost() {
        return child.estimatedCost() + child.estimatedRowCount() * 2.0;
    }

    @Override
    public long estimatedRowCount() {
        if (groupByExprs.isEmpty()) return 1;
        return Math.max(1, child.estimatedRowCount() / 10);
    }

    @Override
    public String explain(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indentStr(indent)).append("PhysicalHashAgg(");
        if (!groupByExprs.isEmpty()) {
            sb.append("groupBy=[");
            for (int i = 0; i < groupByExprs.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(groupByExprs.get(i).toSQL());
            }
            sb.append("], ");
        }
        sb.append("aggs=").append(aggFunctions.size()).append(")\n");
        sb.append(child.explain(indent + 1));
        return sb.toString();
    }
}
