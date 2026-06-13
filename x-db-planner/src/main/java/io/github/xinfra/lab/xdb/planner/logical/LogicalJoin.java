package io.github.xinfra.lab.xdb.planner.logical;

import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class LogicalJoin implements LogicalPlan {
    private LogicalPlan left;
    private LogicalPlan right;
    private final JoinType joinType;
    private final Expression condition;

    public LogicalJoin(LogicalPlan left, LogicalPlan right, JoinType joinType, Expression condition) {
        this.left = left;
        this.right = right;
        this.joinType = joinType;
        this.condition = condition;
    }

    public LogicalPlan left() { return left; }
    public LogicalPlan right() { return right; }
    public void setLeft(LogicalPlan left) { this.left = left; }
    public void setRight(LogicalPlan right) { this.right = right; }
    public JoinType joinType() { return joinType; }
    public Expression condition() { return condition; }

    @Override
    public List<LogicalPlan> children() { return Arrays.asList(left, right); }

    @Override
    public List<ColumnInfo> outputSchema() {
        List<ColumnInfo> schema = new ArrayList<>(left.outputSchema());
        schema.addAll(right.outputSchema());
        return schema;
    }

    @Override
    public String explain(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indentStr(indent)).append("Join(").append(joinType);
        if (condition != null) sb.append(", on=").append(condition.toSQL());
        sb.append(")\n");
        sb.append(left.explain(indent + 1)).append("\n");
        sb.append(right.explain(indent + 1));
        return sb.toString();
    }
}
