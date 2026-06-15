package io.github.xinfra.lab.xdb.planner.physical;

import io.github.xinfra.lab.xdb.meta.ColumnInfo;

import java.util.Collections;
import java.util.List;

public class PhysicalDual implements PhysicalPlan {
    @Override
    public List<PhysicalPlan> children() { return Collections.emptyList(); }

    @Override
    public List<ColumnInfo> outputSchema() { return Collections.emptyList(); }

    @Override
    public double estimatedCost() { return 0; }

    @Override
    public long estimatedRowCount() { return 1; }

    @Override
    public String explain(int indent) { return indentStr(indent) + "PhysicalDual(" + costInfo() + ")"; }
}
