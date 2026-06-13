package io.github.xinfra.lab.xdb.planner.logical;

import io.github.xinfra.lab.xdb.meta.ColumnInfo;

import java.util.Collections;
import java.util.List;

public class LogicalLimit implements LogicalPlan {
    private LogicalPlan child;
    private final long count;
    private final long offset;

    public LogicalLimit(LogicalPlan child, long count, long offset) {
        this.child = child;
        this.count = count;
        this.offset = offset;
    }

    public LogicalPlan child() { return child; }
    public void setChild(LogicalPlan child) { this.child = child; }
    public long count() { return count; }
    public long offset() { return offset; }

    @Override
    public List<LogicalPlan> children() { return Collections.singletonList(child); }

    @Override
    public List<ColumnInfo> outputSchema() { return child.outputSchema(); }

    @Override
    public String explain(int indent) {
        String s = indentStr(indent) + "Limit(count=" + count;
        if (offset > 0) s += ", offset=" + offset;
        return s + ")\n" + child.explain(indent + 1);
    }
}
