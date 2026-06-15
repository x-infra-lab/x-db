package io.github.xinfra.lab.xdb.planner.cost;

import io.github.xinfra.lab.xdb.planner.physical.*;

public class CostEstimator {
    private static final double SCAN_FACTOR = 2.0;
    private static final double INDEX_FACTOR = 1.5;
    private static final double LOOKUP_FACTOR = 5.0;
    private static final double CPU_FACTOR = 0.1;
    private static final double MEMORY_FACTOR = 0.5;
    private static final double NETWORK_FACTOR = 1.0;

    private final StatsStore statsStore;

    public CostEstimator() {
        this(StatsStore.getInstance());
    }

    public CostEstimator(StatsStore statsStore) {
        this.statsStore = statsStore;
    }

    public double estimateCost(PhysicalPlan plan) {
        if (plan instanceof PhysicalTableScan scan) {
            long rows = estimateRowCount(scan);
            return rows * SCAN_FACTOR;
        }
        if (plan instanceof PhysicalIndexScan scan) {
            return scan.estimatedRowCount() * INDEX_FACTOR;
        }
        if (plan instanceof PhysicalIndexLookup lookup) {
            return estimateCost(lookup.indexScan()) + lookup.indexScan().estimatedRowCount() * LOOKUP_FACTOR;
        }
        if (plan instanceof PhysicalSelection sel) {
            return estimateCost(sel.child()) + sel.child().estimatedRowCount() * CPU_FACTOR;
        }
        if (plan instanceof PhysicalProjection proj) {
            return estimateCost(proj.child()) + proj.child().estimatedRowCount() * CPU_FACTOR * 0.1;
        }
        if (plan instanceof PhysicalSort sort) {
            long n = sort.child().estimatedRowCount();
            return estimateCost(sort.child()) + n * Math.log(Math.max(2, n)) * MEMORY_FACTOR;
        }
        if (plan instanceof PhysicalLimit limit) {
            return estimateCost(limit.child());
        }
        if (plan instanceof PhysicalHashJoin join) {
            return estimateCost(join.buildSide()) + estimateCost(join.probeSide()) +
                    join.buildSide().estimatedRowCount() * MEMORY_FACTOR +
                    join.probeSide().estimatedRowCount() * CPU_FACTOR;
        }
        if (plan instanceof PhysicalNestedLoopJoin join) {
            return estimateCost(join.outer()) +
                    join.outer().estimatedRowCount() * estimateCost(join.inner());
        }
        if (plan instanceof PhysicalHashAgg agg) {
            return estimateCost(agg.child()) + agg.child().estimatedRowCount() * MEMORY_FACTOR;
        }
        if (plan instanceof PhysicalStreamAgg agg) {
            return estimateCost(agg.child()) + agg.child().estimatedRowCount() * CPU_FACTOR;
        }
        return plan.estimatedCost();
    }

    private long estimateRowCount(PhysicalTableScan scan) {
        TableStatistics stats = statsStore.getTableStats(scan.table().getId());
        if (stats != null && stats.getRowCount() > 0) {
            return stats.getRowCount();
        }
        return scan.estimatedRowCount();
    }
}
