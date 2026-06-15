package io.github.xinfra.lab.xdb.planner.physical;

import io.github.xinfra.lab.xdb.meta.ColumnInfo;

import java.util.List;

public interface PhysicalPlan {
    List<PhysicalPlan> children();
    List<ColumnInfo> outputSchema();
    String explain(int indent);
    double estimatedCost();
    long estimatedRowCount();

    default String indentStr(int indent) {
        return "  ".repeat(indent);
    }

    default String costInfo() {
        return String.format(", cost=%.1f, rows=%d", estimatedCost(), estimatedRowCount());
    }
}
