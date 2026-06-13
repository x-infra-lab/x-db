package io.github.xinfra.lab.xdb.planner.physical;

import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;

import java.util.Collections;
import java.util.List;

public class PhysicalSort implements PhysicalPlan {
    private final PhysicalPlan child;
    private final List<Expression> orderByExprs;
    private final List<Boolean> ascending;

    public PhysicalSort(PhysicalPlan child, List<Expression> orderByExprs, List<Boolean> ascending) {
        this.child = child;
        this.orderByExprs = orderByExprs;
        this.ascending = ascending;
    }

    public PhysicalPlan child() { return child; }
    public List<Expression> orderByExprs() { return orderByExprs; }
    public List<Boolean> ascending() { return ascending; }

    @Override
    public List<PhysicalPlan> children() { return Collections.singletonList(child); }

    @Override
    public List<ColumnInfo> outputSchema() { return child.outputSchema(); }

    @Override
    public double estimatedCost() {
        long n = child.estimatedRowCount();
        return child.estimatedCost() + n * Math.log(Math.max(2, n)) * 0.5;
    }

    @Override
    public long estimatedRowCount() { return child.estimatedRowCount(); }

    @Override
    public String explain(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indentStr(indent)).append("PhysicalSort(");
        for (int i = 0; i < orderByExprs.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(orderByExprs.get(i).toSQL());
            sb.append(ascending.get(i) ? " ASC" : " DESC");
        }
        sb.append(")\n").append(child.explain(indent + 1));
        return sb.toString();
    }
}
