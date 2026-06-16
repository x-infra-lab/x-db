package io.github.xinfra.lab.xdb.planner.cost;

import io.github.xinfra.lab.xdb.expression.BinaryOp;
import io.github.xinfra.lab.xdb.expression.ColumnRef;
import io.github.xinfra.lab.xdb.expression.Constant;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.meta.IndexInfo;
import io.github.xinfra.lab.xdb.meta.TableInfo;
import io.github.xinfra.lab.xdb.planner.physical.*;

import java.util.List;

public class CostEstimator {
    private static final double SCAN_FACTOR = 2.0;
    private static final double INDEX_FACTOR = 1.5;
    private static final double LOOKUP_FACTOR = 5.0;
    private static final double CPU_FACTOR = 0.1;
    private static final double MEMORY_FACTOR = 0.5;
    private static final double NETWORK_FACTOR = 1.0;
    private static final double DEFAULT_SELECTIVITY = 0.33;

    private final StatsStore statsStore;

    public CostEstimator() {
        this(StatsStore.getInstance());
    }

    public CostEstimator(StatsStore statsStore) {
        this.statsStore = statsStore;
    }

    public double estimateCost(PhysicalPlan plan) {
        if (plan instanceof PhysicalTableScan scan) {
            long rows = estimateTableScanRows(scan);
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

    public long estimateTableScanRows(TableInfo table, List<Expression> conditions) {
        TableStatistics stats = statsStore.getTableStats(table.getId());
        if (stats == null || stats.getRowCount() == 0) return 10000;
        long baseRows = stats.getRowCount();
        double selectivity = estimateSelectivity(conditions, stats);
        return Math.max(1, (long) (baseRows * selectivity));
    }

    public long estimateIndexScanRows(TableInfo table, IndexInfo index, List<Expression> conditions) {
        TableStatistics stats = statsStore.getTableStats(table.getId());
        if (stats == null || stats.getRowCount() == 0) return 100;
        long baseRows = stats.getRowCount();
        double selectivity = estimateSelectivity(conditions, stats);
        return Math.max(1, (long) (baseRows * selectivity));
    }

    public double estimateSelectivity(List<Expression> conditions, TableStatistics stats) {
        if (conditions == null || conditions.isEmpty()) return 1.0;
        double sel = 1.0;
        for (Expression cond : conditions) {
            sel *= estimateConditionSelectivity(cond, stats);
        }
        return sel;
    }

    private double estimateConditionSelectivity(Expression cond, TableStatistics stats) {
        if (cond instanceof BinaryOp op) {
            ColumnRef col = null;
            Constant lit = null;
            if (op.left() instanceof ColumnRef c && op.right() instanceof Constant l) {
                col = c; lit = l;
            } else if (op.left() instanceof Constant l && op.right() instanceof ColumnRef c) {
                col = c; lit = l;
            }
            if (col != null && lit != null) {
                ColumnStatistics cs = stats.getColumnStat(col.columnName());
                if (cs != null) {
                    return switch (op.op()) {
                        case EQ -> cs.estimateEqual(lit.value());
                        case NE -> 1.0 - cs.estimateEqual(lit.value());
                        case LT, LE -> cs.estimateLessThan(lit.value());
                        case GT, GE -> cs.estimateGreaterThan(lit.value());
                        default -> DEFAULT_SELECTIVITY;
                    };
                }
            }
            // AND: multiply selectivities
            if (op.op() == io.github.xinfra.lab.xdb.expression.BinaryOp.Op.AND) {
                return estimateConditionSelectivity(op.left(), stats)
                        * estimateConditionSelectivity(op.right(), stats);
            }
            // OR: P(A) + P(B) - P(A)*P(B)
            if (op.op() == io.github.xinfra.lab.xdb.expression.BinaryOp.Op.OR) {
                double selA = estimateConditionSelectivity(op.left(), stats);
                double selB = estimateConditionSelectivity(op.right(), stats);
                return selA + selB - selA * selB;
            }
        }
        return DEFAULT_SELECTIVITY;
    }

    private long estimateTableScanRows(PhysicalTableScan scan) {
        return estimateTableScanRows(scan.table(), scan.accessConditions());
    }
}
