package io.github.xinfra.lab.xdb.planner.optimize;

import io.github.xinfra.lab.xdb.expression.BinaryOp;
import io.github.xinfra.lab.xdb.expression.ColumnRef;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.planner.logical.*;

import java.util.ArrayList;
import java.util.List;

public class PredicatePushdownRule implements OptimizeRule {
    @Override
    public String name() { return "PredicatePushdown"; }

    @Override
    public LogicalPlan apply(LogicalPlan plan) {
        return pushDown(plan);
    }

    private LogicalPlan pushDown(LogicalPlan plan) {
        if (plan instanceof LogicalSelection sel) {
            LogicalPlan child = pushDown(sel.child());
            return pushSelectionDown(sel.conditions(), child);
        }
        if (plan instanceof LogicalProjection proj) {
            proj.setChild(pushDown(proj.child()));
            return proj;
        }
        if (plan instanceof LogicalSort sort) {
            sort.setChild(pushDown(sort.child()));
            return sort;
        }
        if (plan instanceof LogicalLimit limit) {
            limit.setChild(pushDown(limit.child()));
            return limit;
        }
        if (plan instanceof LogicalAggregation agg) {
            agg.setChild(pushDown(agg.child()));
            return agg;
        }
        if (plan instanceof LogicalJoin join) {
            join.setLeft(pushDown(join.left()));
            join.setRight(pushDown(join.right()));
            return join;
        }
        return plan;
    }

    private LogicalPlan pushSelectionDown(List<Expression> conditions, LogicalPlan child) {
        if (child instanceof LogicalTableScan scan) {
            List<Expression> pushed = new ArrayList<>(scan.accessConditions());
            pushed.addAll(conditions);
            scan.setAccessConditions(pushed);
            return scan;
        }
        if (child instanceof LogicalIndexScan scan) {
            List<Expression> pushed = new ArrayList<>(scan.accessConditions());
            pushed.addAll(conditions);
            scan.setAccessConditions(pushed);
            return scan;
        }
        if (child instanceof LogicalJoin join) {
            List<Expression> leftConds = new ArrayList<>();
            List<Expression> rightConds = new ArrayList<>();
            List<Expression> remaining = new ArrayList<>();
            for (Expression cond : conditions) {
                TableSide side = determineTableSide(cond, join);
                if (side == TableSide.LEFT) leftConds.add(cond);
                else if (side == TableSide.RIGHT) rightConds.add(cond);
                else remaining.add(cond);
            }
            boolean canPushLeft = join.joinType() != JoinType.RIGHT;
            boolean canPushRight = join.joinType() != JoinType.LEFT;
            if (!leftConds.isEmpty()) {
                if (canPushLeft) {
                    join.setLeft(pushSelectionDown(leftConds, join.left()));
                } else {
                    remaining.addAll(leftConds);
                }
            }
            if (!rightConds.isEmpty()) {
                if (canPushRight) {
                    join.setRight(pushSelectionDown(rightConds, join.right()));
                } else {
                    remaining.addAll(rightConds);
                }
            }
            if (!remaining.isEmpty()) {
                return new LogicalSelection(join, remaining);
            }
            return join;
        }
        if (child instanceof LogicalProjection proj) {
            proj.setChild(pushSelectionDown(conditions, proj.child()));
            return proj;
        }
        return new LogicalSelection(child, conditions);
    }

    private enum TableSide { LEFT, RIGHT, BOTH }

    private TableSide determineTableSide(Expression expr, LogicalJoin join) {
        List<String> tables = collectTableNames(expr);
        if (tables.isEmpty()) return TableSide.LEFT;
        List<ColumnRef> leftCols = collectColumnRefs(join.left());
        List<ColumnRef> rightCols = collectColumnRefs(join.right());
        boolean matchesLeft = tables.stream().allMatch(t ->
                leftCols.stream().anyMatch(c -> t.equalsIgnoreCase(c.tableName())));
        boolean matchesRight = tables.stream().allMatch(t ->
                rightCols.stream().anyMatch(c -> t.equalsIgnoreCase(c.tableName())));
        if (matchesLeft && !matchesRight) return TableSide.LEFT;
        if (matchesRight && !matchesLeft) return TableSide.RIGHT;
        return TableSide.BOTH;
    }

    private List<String> collectTableNames(Expression expr) {
        List<String> names = new ArrayList<>();
        if (expr instanceof ColumnRef ref && ref.tableName() != null) {
            names.add(ref.tableName());
        } else if (expr instanceof BinaryOp op) {
            names.addAll(collectTableNames(op.left()));
            names.addAll(collectTableNames(op.right()));
        } else if (expr instanceof io.github.xinfra.lab.xdb.expression.UnaryOp uop) {
            names.addAll(collectTableNames(uop.operand()));
        }
        return names;
    }

    private List<ColumnRef> collectColumnRefs(LogicalPlan plan) {
        List<ColumnRef> refs = new ArrayList<>();
        if (plan instanceof LogicalTableScan scan) {
            String tblName = scan.tableName();
            for (var col : scan.outputSchema()) {
                refs.add(new ColumnRef(tblName, col.getName(), 0, col.getType()));
            }
        } else if (plan instanceof LogicalIndexScan scan) {
            String tblName = scan.alias() != null ? scan.alias() : scan.table().getName();
            for (var col : scan.outputSchema()) {
                refs.add(new ColumnRef(tblName, col.getName(), 0, col.getType()));
            }
        } else {
            for (var child : plan.children()) {
                refs.addAll(collectColumnRefs(child));
            }
        }
        return refs;
    }
}
