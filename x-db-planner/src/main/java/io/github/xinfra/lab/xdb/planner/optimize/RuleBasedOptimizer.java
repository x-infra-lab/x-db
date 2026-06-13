package io.github.xinfra.lab.xdb.planner.optimize;

import io.github.xinfra.lab.xdb.planner.logical.LogicalPlan;

import java.util.Arrays;
import java.util.List;

public class RuleBasedOptimizer {
    private final List<OptimizeRule> rules;

    public RuleBasedOptimizer() {
        this.rules = Arrays.asList(
                new ConstantFoldingRule(),
                new PredicatePushdownRule(),
                new ColumnPruningRule()
        );
    }

    public RuleBasedOptimizer(List<OptimizeRule> rules) {
        this.rules = rules;
    }

    public LogicalPlan optimize(LogicalPlan plan) {
        LogicalPlan current = plan;
        for (OptimizeRule rule : rules) {
            current = rule.apply(current);
        }
        return current;
    }
}
