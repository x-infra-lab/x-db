package io.github.xinfra.lab.xdb.planner.logical;

import io.github.xinfra.lab.xdb.meta.ColumnInfo;

import java.util.List;

public class LogicalUnion implements LogicalPlan {
    private final List<LogicalPlan> inputs;
    private final boolean all;
    private final List<ColumnInfo> outputColumns;

    public LogicalUnion(List<LogicalPlan> inputs, boolean all, List<ColumnInfo> outputColumns) {
        this.inputs = inputs;
        this.all = all;
        this.outputColumns = outputColumns;
    }

    public List<LogicalPlan> inputs() { return inputs; }
    public boolean isAll() { return all; }

    @Override
    public List<LogicalPlan> children() { return inputs; }

    @Override
    public List<ColumnInfo> outputSchema() { return outputColumns; }

    @Override
    public String explain(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indentStr(indent)).append("Union(all=").append(all).append(")\n");
        for (LogicalPlan child : inputs) {
            sb.append(child.explain(indent + 1));
        }
        return sb.toString();
    }
}
