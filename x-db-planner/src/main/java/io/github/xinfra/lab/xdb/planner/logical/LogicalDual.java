package io.github.xinfra.lab.xdb.planner.logical;

import io.github.xinfra.lab.xdb.meta.ColumnInfo;

import java.util.Collections;
import java.util.List;

public class LogicalDual implements LogicalPlan {
    @Override
    public List<LogicalPlan> children() { return Collections.emptyList(); }

    @Override
    public List<ColumnInfo> outputSchema() { return Collections.emptyList(); }

    @Override
    public String explain(int indent) { return indentStr(indent) + "Dual"; }
}
