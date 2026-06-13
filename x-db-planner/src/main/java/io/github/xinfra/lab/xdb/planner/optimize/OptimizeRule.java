package io.github.xinfra.lab.xdb.planner.optimize;

import io.github.xinfra.lab.xdb.planner.logical.LogicalPlan;

public interface OptimizeRule {
    String name();
    LogicalPlan apply(LogicalPlan plan);
}
