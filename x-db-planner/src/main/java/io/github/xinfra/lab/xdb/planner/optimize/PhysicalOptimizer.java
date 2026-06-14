package io.github.xinfra.lab.xdb.planner.optimize;

import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.meta.IndexInfo;
import io.github.xinfra.lab.xdb.meta.TableInfo;
import io.github.xinfra.lab.xdb.planner.logical.*;
import io.github.xinfra.lab.xdb.planner.physical.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class PhysicalOptimizer {

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
        throw new UnsupportedOperationException("Cannot convert plan: " + plan.getClass().getSimpleName());
    }

    private PhysicalPlan convertTableScan(LogicalTableScan scan) {
        TableInfo table = scan.table();
        List<IndexInfo> indices = table.getIndices();
        if (indices != null && !scan.accessConditions().isEmpty()) {
            IndexInfo bestIndex = selectBestIndex(table, scan.accessConditions());
            if (bestIndex != null) {
                PhysicalIndexScan idxScan = new PhysicalIndexScan(table, bestIndex, scan.alias(),
                        scan.outputSchema(), scan.accessConditions());
                return new PhysicalIndexLookup(idxScan, table, scan.outputSchema());
            }
        }
        return new PhysicalTableScan(table, scan.alias(), scan.outputSchema(), scan.accessConditions());
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
        if (left.estimatedRowCount() <= 10000 || right.estimatedRowCount() <= 10000) {
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
            return new PhysicalHashJoin(buildSide, probeSide, join.joinType(),
                    join.condition(), Collections.emptyList(), Collections.emptyList());
        }
        return new PhysicalNestedLoopJoin(left, right, join.joinType(), join.condition());
    }

    private IndexInfo selectBestIndex(TableInfo table, List<Expression> conditions) {
        if (table.getIndices() == null) return null;
        Set<String> conditionColumns = new HashSet<>();
        for (Expression cond : conditions) {
            collectConditionColumns(cond, conditionColumns);
        }
        for (IndexInfo idx : table.getIndices()) {
            if (idx.isPrimary()) continue;
            if (idx.getState() != null &&
                    idx.getState() != io.github.xinfra.lab.xdb.meta.SchemaState.PUBLIC) continue;
            if (idx.getColumns().isEmpty()) continue;
            long firstColId = idx.getColumns().get(0).getColumnId();
            io.github.xinfra.lab.xdb.meta.ColumnInfo firstCol = table.getColumn(firstColId);
            if (firstCol != null && conditionColumns.contains(firstCol.getName().toLowerCase())) {
                return idx;
            }
        }
        return null;
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
