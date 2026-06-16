package io.github.xinfra.lab.xdb.planner.physical;

import io.github.xinfra.lab.xdb.meta.ColumnInfo;

import java.util.List;

public class PhysicalUnion implements PhysicalPlan {
    private final List<PhysicalPlan> inputs;
    private final boolean all;
    private final List<ColumnInfo> outputColumns;

    public PhysicalUnion(List<PhysicalPlan> inputs, boolean all, List<ColumnInfo> outputColumns) {
        this.inputs = inputs;
        this.all = all;
        this.outputColumns = outputColumns;
    }

    public List<PhysicalPlan> inputs() { return inputs; }
    public boolean isAll() { return all; }

    @Override
    public List<PhysicalPlan> children() { return inputs; }

    @Override
    public List<ColumnInfo> outputSchema() { return outputColumns; }

    @Override
    public double estimatedCost() {
        double cost = 0;
        for (PhysicalPlan child : inputs) {
            cost += child.estimatedCost();
        }
        return cost + estimatedRowCount() * 0.01;
    }

    @Override
    public long estimatedRowCount() {
        long rows = 0;
        for (PhysicalPlan child : inputs) {
            rows += child.estimatedRowCount();
        }
        return rows;
    }

    @Override
    public String explain(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indentStr(indent)).append("PhysicalUnion(all=").append(all)
                .append(costInfo()).append(")\n");
        for (PhysicalPlan child : inputs) {
            sb.append(child.explain(indent + 1));
        }
        return sb.toString();
    }
}
