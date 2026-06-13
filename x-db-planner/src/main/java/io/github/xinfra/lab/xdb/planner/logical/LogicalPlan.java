package io.github.xinfra.lab.xdb.planner.logical;

import io.github.xinfra.lab.xdb.meta.ColumnInfo;

import java.util.List;

public interface LogicalPlan {
    List<LogicalPlan> children();
    List<ColumnInfo> outputSchema();
    String explain(int indent);

    default String indentStr(int indent) {
        return "  ".repeat(indent);
    }
}
