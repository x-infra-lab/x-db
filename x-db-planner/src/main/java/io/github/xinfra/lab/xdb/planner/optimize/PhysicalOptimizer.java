package io.github.xinfra.lab.xdb.planner.optimize;

import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.meta.IndexInfo;
import io.github.xinfra.lab.xdb.meta.TableInfo;
import io.github.xinfra.lab.xdb.planner.cost.CostEstimator;
import io.github.xinfra.lab.xdb.planner.logical.*;
import io.github.xinfra.lab.xdb.planner.physical.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PhysicalOptimizer {

    private final CostEstimator costEstimator;

    public PhysicalOptimizer() {
        this(new CostEstimator());
    }

    public PhysicalOptimizer(CostEstimator costEstimator) {
        this.costEstimator = costEstimator;
    }

    public PhysicalPlan optimize(LogicalPlan logicalPlan) {
        return convert(logicalPlan);
    }

    private PhysicalPlan convert(LogicalPlan plan) {
        if (plan instanceof LogicalTableScan scan) {
            return convertTableScan(scan);
        }
        if (plan instanceof LogicalIndexScan scan) {
            return convertIndexScan(scan);
        }
        if (plan instanceof LogicalSelection sel) {
            return new PhysicalSelection(convert(sel.child()), sel.conditions());
        }
        if (plan instanceof LogicalProjection proj) {
            return new PhysicalProjection(convert(proj.child()),
                    proj.expressions(), proj.aliases(), proj.outputSchema());
        }
        if (plan instanceof LogicalSort sort) {
            return new PhysicalSort(convert(sort.child()), sort.orderByExprs(), sort.ascending());
        }
        if (plan instanceof LogicalLimit limit) {
            return new PhysicalLimit(convert(limit.child()), limit.count(), limit.offset());
        }
        if (plan instanceof LogicalJoin join) {
            return convertJoin(join);
        }
        if (plan instanceof LogicalAggregation agg) {
            return new PhysicalHashAgg(convert(agg.child()),
                    agg.groupByExprs(), agg.aggFunctions(), agg.outputSchema());
        }
        if (plan instanceof LogicalInsert ins) {
            return new PhysicalInsert(ins.table(), ins.targetColumns(), ins.rows());
        }
        if (plan instanceof LogicalUpdate upd) {
            return new PhysicalUpdate(convert(upd.child()), upd.table(),
                    upd.updateColumns(), upd.updateValues());
        }
        if (plan instanceof LogicalDelete del) {
            return new PhysicalDelete(convert(del.child()), del.table());
        }
        if (plan instanceof LogicalDual) {
            return new PhysicalDual();
        }
        if (plan instanceof LogicalShowStmt show) {
            return new PhysicalShowStmt(show.showType(), show.databaseName(),
                    show.tableName(), show.outputSchema());
        }
        if (plan instanceof LogicalUnion union) {
            List<PhysicalPlan> children = new ArrayList<>();
            for (LogicalPlan child : union.inputs()) {
                children.add(convert(child));
            }
            return new PhysicalUnion(children, union.isAll(), union.outputSchema());
        }
        throw new UnsupportedOperationException("Cannot convert plan: " + plan.getClass().getSimpleName());
    }

    private PhysicalPlan convertTableScan(LogicalTableScan scan) {
        TableInfo table = scan.table();

        PhysicalTableScan tableScan = new PhysicalTableScan(table, scan.alias(),
                scan.outputSchema(), scan.accessConditions());
        long tsRows = costEstimator.estimateTableScanRows(table, scan.accessConditions());
        tableScan.setEstimatedRows(tsRows);
        double bestCost = costEstimator.estimateCost(tableScan);
        PhysicalPlan bestPlan = tableScan;

        List<IndexInfo> indices = table.getIndices();
        if (indices != null && !scan.accessConditions().isEmpty()) {
            Set<String> conditionColumns = new HashSet<>();
            for (Expression cond : scan.accessConditions()) {
                collectConditionColumns(cond, conditionColumns);
            }

            for (IndexInfo idx : indices) {
                if (idx.isPrimary()) continue;
                if (idx.getState() != null &&
                        idx.getState() != io.github.xinfra.lab.xdb.meta.SchemaState.PUBLIC) continue;
                if (idx.getColumns().isEmpty()) continue;

                long firstColId = idx.getColumns().get(0).getColumnId();
                io.github.xinfra.lab.xdb.meta.ColumnInfo firstCol = table.getColumn(firstColId);
                if (firstCol == null || !conditionColumns.contains(firstCol.getName().toLowerCase())) {
                    continue;
                }

                PhysicalIndexScan idxScan = new PhysicalIndexScan(table, idx, scan.alias(),
                        scan.outputSchema(), scan.accessConditions());
                long idxRows = costEstimator.estimateIndexScanRows(table, idx, scan.accessConditions());
                idxScan.setEstimatedRows(idxRows);

                PhysicalIndexLookup lookup = new PhysicalIndexLookup(idxScan, table, scan.outputSchema());
                double idxCost = costEstimator.estimateCost(lookup);

                if (idxCost < bestCost) {
                    bestPlan = lookup;
                    bestCost = idxCost;
                }
            }
        }
        return bestPlan;
    }

    private PhysicalPlan convertIndexScan(LogicalIndexScan scan) {
        PhysicalIndexScan idxScan = new PhysicalIndexScan(scan.table(), scan.index(), scan.alias(),
                scan.outputSchema(), scan.accessConditions());
        if (scan.needTableLookup()) {
            return new PhysicalIndexLookup(idxScan, scan.table(), scan.outputSchema());
        }
        return idxScan;
    }

    private PhysicalPlan convertJoin(LogicalJoin join) {
        PhysicalPlan left = convert(join.left());
        PhysicalPlan right = convert(join.right());

        PhysicalPlan buildSide;
        PhysicalPlan probeSide;
        if (join.joinType() == JoinType.LEFT) {
            buildSide = right;
            probeSide = left;
        } else if (join.joinType() == JoinType.RIGHT) {
            buildSide = left;
            probeSide = right;
        } else {
            buildSide = left.estimatedRowCount() <= right.estimatedRowCount() ? left : right;
            probeSide = buildSide == left ? right : left;
        }

        PhysicalHashJoin hashJoin = new PhysicalHashJoin(buildSide, probeSide, join.joinType(),
                join.condition(), Collections.emptyList(), Collections.emptyList());
        PhysicalNestedLoopJoin nlj = new PhysicalNestedLoopJoin(left, right,
                join.joinType(), join.condition());

        double hashCost = costEstimator.estimateCost(hashJoin);
        double nljCost = costEstimator.estimateCost(nlj);

        return hashCost <= nljCost ? hashJoin : nlj;
    }

    private void collectConditionColumns(Expression expr, Set<String> columns) {
        if (expr instanceof io.github.xinfra.lab.xdb.expression.ColumnRef ref) {
            columns.add(ref.columnName().toLowerCase());
        } else if (expr instanceof io.github.xinfra.lab.xdb.expression.BinaryOp op) {
            collectConditionColumns(op.left(), columns);
            collectConditionColumns(op.right(), columns);
        } else if (expr instanceof io.github.xinfra.lab.xdb.expression.UnaryOp uop) {
            collectConditionColumns(uop.operand(), columns);
        }
    }
}
