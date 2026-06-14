package io.github.xinfra.lab.xdb.planner.optimize;

import io.github.xinfra.lab.xdb.expression.ColumnRef;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.planner.logical.*;

import java.util.*;
import java.util.stream.Collectors;

public class ColumnPruningRule implements OptimizeRule {
    @Override
    public String name() { return "ColumnPruning"; }

    @Override
    public LogicalPlan apply(LogicalPlan plan) {
        Set<String> required = new HashSet<>();
        collectRequiredColumns(plan, required);
        return prune(plan, required);
    }

    private LogicalPlan prune(LogicalPlan plan, Set<String> required) {
        if (plan instanceof LogicalProjection proj) {
            Set<String> childRequired = new HashSet<>(required);
            for (Expression expr : proj.expressions()) {
                collectColumnsFromExpr(expr, childRequired);
            }
            proj.setChild(prune(proj.child(), childRequired));
            return proj;
        }
        if (plan instanceof LogicalSelection sel) {
            Set<String> childRequired = new HashSet<>(required);
            for (Expression expr : sel.conditions()) {
                collectColumnsFromExpr(expr, childRequired);
            }
            sel.setChild(prune(sel.child(), childRequired));
            return sel;
        }
        if (plan instanceof LogicalTableScan scan) {
            Set<String> allRequired = new HashSet<>(required);
            for (Expression expr : scan.accessConditions()) {
                collectColumnsFromExpr(expr, allRequired);
            }
            if (!allRequired.isEmpty()) {
                List<ColumnInfo> pruned = scan.outputSchema().stream()
                        .filter(c -> allRequired.contains(c.getName().toLowerCase()))
                        .collect(Collectors.toList());
                if (!pruned.isEmpty()) {
                    scan.setOutputColumns(pruned);
                }
            }
            return scan;
        }
        if (plan instanceof LogicalSort sort) {
            Set<String> childRequired = new HashSet<>(required);
            for (Expression expr : sort.orderByExprs()) {
                collectColumnsFromExpr(expr, childRequired);
            }
            sort.setChild(prune(sort.child(), childRequired));
            return sort;
        }
        if (plan instanceof LogicalLimit limit) {
            limit.setChild(prune(limit.child(), required));
            return limit;
        }
        if (plan instanceof LogicalJoin join) {
            Set<String> leftRequired = new HashSet<>(required);
            Set<String> rightRequired = new HashSet<>(required);
            if (join.condition() != null) {
                collectColumnsFromExpr(join.condition(), leftRequired);
                collectColumnsFromExpr(join.condition(), rightRequired);
            }
            join.setLeft(prune(join.left(), leftRequired));
            join.setRight(prune(join.right(), rightRequired));
            return join;
        }
        if (plan instanceof LogicalAggregation agg) {
            Set<String> childRequired = new HashSet<>();
            for (Expression expr : agg.groupByExprs()) {
                collectColumnsFromExpr(expr, childRequired);
            }
            for (var aggFunc : agg.aggFunctions()) {
                if (aggFunc.arg() != null) {
                    collectColumnsFromExpr(aggFunc.arg(), childRequired);
                }
            }
            agg.setChild(prune(agg.child(), childRequired));
            return agg;
        }
        return plan;
    }

    private void collectRequiredColumns(LogicalPlan plan, Set<String> required) {
        if (plan instanceof LogicalProjection proj) {
            for (Expression expr : proj.expressions()) {
                collectColumnsFromExpr(expr, required);
            }
        }
        for (var col : plan.outputSchema()) {
            required.add(col.getName().toLowerCase());
        }
    }

    private void collectColumnsFromExpr(Expression expr, Set<String> columns) {
        if (expr instanceof ColumnRef ref) {
            columns.add(ref.columnName().toLowerCase());
        } else if (expr instanceof io.github.xinfra.lab.xdb.expression.BinaryOp op) {
            collectColumnsFromExpr(op.left(), columns);
            collectColumnsFromExpr(op.right(), columns);
        } else if (expr instanceof io.github.xinfra.lab.xdb.expression.UnaryOp uop) {
            collectColumnsFromExpr(uop.operand(), columns);
        } else if (expr instanceof io.github.xinfra.lab.xdb.expression.FunctionCallExpr func) {
            for (Expression arg : func.args()) {
                collectColumnsFromExpr(arg, columns);
            }
        }
    }
}
