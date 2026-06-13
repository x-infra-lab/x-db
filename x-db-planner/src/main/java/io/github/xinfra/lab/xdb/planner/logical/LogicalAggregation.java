package io.github.xinfra.lab.xdb.planner.logical;

import io.github.xinfra.lab.xdb.expression.AggFunction;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;

import java.util.Collections;
import java.util.List;

public class LogicalAggregation implements LogicalPlan {
    private LogicalPlan child;
    private final List<Expression> groupByExprs;
    private final List<AggFunction> aggFunctions;
    private final List<ColumnInfo> outputColumns;

    public LogicalAggregation(LogicalPlan child, List<Expression> groupByExprs,
                              List<AggFunction> aggFunctions, List<ColumnInfo> outputColumns) {
        this.child = child;
        this.groupByExprs = groupByExprs;
        this.aggFunctions = aggFunctions;
        this.outputColumns = outputColumns;
    }

    public LogicalPlan child() { return child; }
    public void setChild(LogicalPlan child) { this.child = child; }
    public List<Expression> groupByExprs() { return groupByExprs; }
    public List<AggFunction> aggFunctions() { return aggFunctions; }

    @Override
    public List<LogicalPlan> children() { return Collections.singletonList(child); }

    @Override
    public List<ColumnInfo> outputSchema() { return outputColumns; }

    @Override
    public String explain(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indentStr(indent)).append("Aggregation(");
        if (!groupByExprs.isEmpty()) {
            sb.append("groupBy=[");
            for (int i = 0; i < groupByExprs.size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(groupByExprs.get(i).toSQL());
            }
            sb.append("], ");
        }
        sb.append("aggs=[");
        for (int i = 0; i < aggFunctions.size(); i++) {
            if (i > 0) sb.append(", ");
            AggFunction agg = aggFunctions.get(i);
            sb.append(agg.type());
            if (agg.distinct()) sb.append("(DISTINCT)");
        }
        sb.append("])\n").append(child.explain(indent + 1));
        return sb.toString();
    }
}
