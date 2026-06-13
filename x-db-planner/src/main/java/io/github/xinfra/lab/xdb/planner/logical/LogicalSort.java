package io.github.xinfra.lab.xdb.planner.logical;

import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;

import java.util.Collections;
import java.util.List;

public class LogicalSort implements LogicalPlan {
    private LogicalPlan child;
    private final List<Expression> orderByExprs;
    private final List<Boolean> ascending;

    public LogicalSort(LogicalPlan child, List<Expression> orderByExprs, List<Boolean> ascending) {
        this.child = child;
        this.orderByExprs = orderByExprs;
        this.ascending = ascending;
    }

    public LogicalPlan child() { return child; }
    public void setChild(LogicalPlan child) { this.child = child; }
    public List<Expression> orderByExprs() { return orderByExprs; }
    public List<Boolean> ascending() { return ascending; }

    @Override
    public List<LogicalPlan> children() { return Collections.singletonList(child); }

    @Override
    public List<ColumnInfo> outputSchema() { return child.outputSchema(); }

    @Override
    public String explain(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indentStr(indent)).append("Sort(");
        for (int i = 0; i < orderByExprs.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(orderByExprs.get(i).toSQL());
            sb.append(ascending.get(i) ? " ASC" : " DESC");
        }
        sb.append(")\n").append(child.explain(indent + 1));
        return sb.toString();
    }
}
