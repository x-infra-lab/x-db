package io.github.xinfra.lab.xdb.planner.logical;

import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;

import java.util.Collections;
import java.util.List;

public class LogicalSelection implements LogicalPlan {
    private LogicalPlan child;
    private final List<Expression> conditions;

    public LogicalSelection(LogicalPlan child, List<Expression> conditions) {
        this.child = child;
        this.conditions = conditions;
    }

    public LogicalPlan child() { return child; }
    public void setChild(LogicalPlan child) { this.child = child; }
    public List<Expression> conditions() { return conditions; }

    @Override
    public List<LogicalPlan> children() { return Collections.singletonList(child); }

    @Override
    public List<ColumnInfo> outputSchema() { return child.outputSchema(); }

    @Override
    public String explain(int indent) {
        return indentStr(indent) + "Selection(" + conditions + ")\n" + child.explain(indent + 1);
    }
}
