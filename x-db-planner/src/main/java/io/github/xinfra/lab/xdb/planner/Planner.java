package io.github.xinfra.lab.xdb.planner;

import io.github.xinfra.lab.xdb.meta.InfoSchema;
import io.github.xinfra.lab.xdb.parser.ast.Statement;
import io.github.xinfra.lab.xdb.planner.logical.LogicalPlan;
import io.github.xinfra.lab.xdb.planner.optimize.PhysicalOptimizer;
import io.github.xinfra.lab.xdb.planner.optimize.RuleBasedOptimizer;
import io.github.xinfra.lab.xdb.planner.physical.PhysicalPlan;

public class Planner {
    private final InfoSchema infoSchema;
    private final RuleBasedOptimizer rbo;
    private final PhysicalOptimizer physicalOptimizer;

    public Planner(InfoSchema infoSchema) {
        this.infoSchema = infoSchema;
        this.rbo = new RuleBasedOptimizer();
        this.physicalOptimizer = new PhysicalOptimizer();
    }

    public LogicalPlan buildLogicalPlan(Statement stmt, String currentDatabase) {
        Analyzer analyzer = new Analyzer(infoSchema, currentDatabase);
        return analyzer.analyze(stmt);
    }

    public LogicalPlan optimizeLogical(LogicalPlan plan) {
        return rbo.optimize(plan);
    }

    public PhysicalPlan optimizePhysical(LogicalPlan logicalPlan) {
        return physicalOptimizer.optimize(logicalPlan);
    }

    public PhysicalPlan plan(Statement stmt, String currentDatabase) {
        LogicalPlan logical = buildLogicalPlan(stmt, currentDatabase);
        LogicalPlan optimized = optimizeLogical(logical);
        return optimizePhysical(optimized);
    }

    public String explain(Statement stmt, String currentDatabase) {
        PhysicalPlan physical = plan(stmt, currentDatabase);
        return physical.explain(0);
    }
}
