package io.github.xinfra.lab.xdb.planner.physical;

import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;

import java.util.Collections;
import java.util.List;

public class PhysicalSelection implements PhysicalPlan {
    private final PhysicalPlan child;
    private final List<Expression> conditions;

    public PhysicalSelection(PhysicalPlan child, List<Expression> conditions) {
        this.child = child;
        this.conditions = conditions;
    }

    public PhysicalPlan child() { return child; }
    public List<Expression> conditions() { return conditions; }

    @Override
    public List<PhysicalPlan> children() { return Collections.singletonList(child); }

    @Override
    public List<ColumnInfo> outputSchema() { return child.outputSchema(); }

    @Override
    public double estimatedCost() { return child.estimatedCost() + child.estimatedRowCount() * 0.1; }

    @Override
    public long estimatedRowCount() { return Math.max(1, child.estimatedRowCount() / 3); }

    @Override
    public String explain(int indent) {
        return indentStr(indent) + "PhysicalSelection(" + conditions + costInfo() + ")\n" +
                child.explain(indent + 1);
    }
}
