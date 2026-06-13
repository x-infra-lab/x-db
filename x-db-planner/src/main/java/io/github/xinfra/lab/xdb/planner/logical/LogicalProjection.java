package io.github.xinfra.lab.xdb.planner.logical;

import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;

import java.util.Collections;
import java.util.List;

public class LogicalProjection implements LogicalPlan {
    private LogicalPlan child;
    private final List<Expression> expressions;
    private final List<String> aliases;
    private final List<ColumnInfo> outputColumns;

    public LogicalProjection(LogicalPlan child, List<Expression> expressions,
                             List<String> aliases, List<ColumnInfo> outputColumns) {
        this.child = child;
        this.expressions = expressions;
        this.aliases = aliases;
        this.outputColumns = outputColumns;
    }

    public LogicalPlan child() { return child; }
    public void setChild(LogicalPlan child) { this.child = child; }
    public List<Expression> expressions() { return expressions; }
    public List<String> aliases() { return aliases; }

    @Override
    public List<LogicalPlan> children() { return Collections.singletonList(child); }

    @Override
    public List<ColumnInfo> outputSchema() { return outputColumns; }

    @Override
    public String explain(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indentStr(indent)).append("Projection(");
        for (int i = 0; i < expressions.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(expressions.get(i).toSQL());
            if (aliases.get(i) != null) sb.append(" AS ").append(aliases.get(i));
        }
        sb.append(")\n").append(child.explain(indent + 1));
        return sb.toString();
    }
}
