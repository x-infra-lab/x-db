package io.github.xinfra.lab.xdb.planner.physical;

import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;

import java.util.Collections;
import java.util.List;

public class PhysicalProjection implements PhysicalPlan {
    private final PhysicalPlan child;
    private final List<Expression> expressions;
    private final List<String> aliases;
    private final List<ColumnInfo> outputColumns;

    public PhysicalProjection(PhysicalPlan child, List<Expression> expressions,
                              List<String> aliases, List<ColumnInfo> outputColumns) {
        this.child = child;
        this.expressions = expressions;
        this.aliases = aliases;
        this.outputColumns = outputColumns;
    }

    public PhysicalPlan child() { return child; }
    public List<Expression> expressions() { return expressions; }
    public List<String> aliases() { return aliases; }

    @Override
    public List<PhysicalPlan> children() { return Collections.singletonList(child); }

    @Override
    public List<ColumnInfo> outputSchema() { return outputColumns; }

    @Override
    public double estimatedCost() { return child.estimatedCost() + child.estimatedRowCount() * 0.01; }

    @Override
    public long estimatedRowCount() { return child.estimatedRowCount(); }

    @Override
    public String explain(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indentStr(indent)).append("PhysicalProjection(");
        for (int i = 0; i < expressions.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(expressions.get(i).toSQL());
            if (i < aliases.size() && aliases.get(i) != null) sb.append(" AS ").append(aliases.get(i));
        }
        sb.append(")\n").append(child.explain(indent + 1));
        return sb.toString();
    }
}
