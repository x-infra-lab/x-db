package io.github.xinfra.lab.xdb.planner.physical;

import io.github.xinfra.lab.xdb.meta.ColumnInfo;

import java.util.Collections;
import java.util.List;

public class PhysicalLimit implements PhysicalPlan {
    private final PhysicalPlan child;
    private final long count;
    private final long offset;

    public PhysicalLimit(PhysicalPlan child, long count, long offset) {
        this.child = child;
        this.count = count;
        this.offset = offset;
    }

    public PhysicalPlan child() { return child; }
    public long count() { return count; }
    public long offset() { return offset; }

    @Override
    public List<PhysicalPlan> children() { return Collections.singletonList(child); }

    @Override
    public List<ColumnInfo> outputSchema() { return child.outputSchema(); }

    @Override
    public double estimatedCost() { return child.estimatedCost(); }

    @Override
    public long estimatedRowCount() { return Math.min(count, child.estimatedRowCount()); }

    @Override
    public String explain(int indent) {
        String s = indentStr(indent) + "PhysicalLimit(count=" + count;
        if (offset > 0) s += ", offset=" + offset;
        return s + costInfo() + ")\n" + child.explain(indent + 1);
    }
}
