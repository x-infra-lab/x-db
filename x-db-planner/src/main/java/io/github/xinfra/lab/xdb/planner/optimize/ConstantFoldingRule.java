package io.github.xinfra.lab.xdb.planner.optimize;

import io.github.xinfra.lab.xdb.expression.*;
import io.github.xinfra.lab.xdb.planner.logical.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class ConstantFoldingRule implements OptimizeRule {
    @Override
    public String name() { return "ConstantFolding"; }

    @Override
    public LogicalPlan apply(LogicalPlan plan) {
        return fold(plan);
    }

    private LogicalPlan fold(LogicalPlan plan) {
        if (plan instanceof LogicalSelection sel) {
            List<Expression> folded = sel.conditions().stream()
                    .map(this::foldExpr)
                    .collect(Collectors.toList());
            sel.setChild(fold(sel.child()));
            return new LogicalSelection(sel.child(), folded);
        }
        if (plan instanceof LogicalProjection proj) {
            List<Expression> folded = proj.expressions().stream()
                    .map(this::foldExpr)
                    .collect(Collectors.toList());
            proj.setChild(fold(proj.child()));
            return new LogicalProjection(proj.child(), folded, proj.aliases(), proj.outputSchema());
        }
        if (plan instanceof LogicalSort sort) {
            sort.setChild(fold(sort.child()));
            return sort;
        }
        if (plan instanceof LogicalLimit limit) {
            limit.setChild(fold(limit.child()));
            return limit;
        }
        if (plan instanceof LogicalJoin join) {
            join.setLeft(fold(join.left()));
            join.setRight(fold(join.right()));
            return join;
        }
        if (plan instanceof LogicalAggregation agg) {
            agg.setChild(fold(agg.child()));
            return agg;
        }
        return plan;
    }

    private Expression foldExpr(Expression expr) {
        if (expr instanceof BinaryOp op) {
            Expression left = foldExpr(op.left());
            Expression right = foldExpr(op.right());
            if (left instanceof Constant && right instanceof Constant) {
                try {
                    Datum result = new BinaryOp(left, op.op(), right)
                            .eval(new EvalContext(), null);
                    return datumToConstant(result);
                } catch (Exception e) {
                    return new BinaryOp(left, op.op(), right);
                }
            }
            return new BinaryOp(left, op.op(), right);
        }
        return expr;
    }

    private Expression datumToConstant(Datum datum) {
        if (datum.isNull()) return Constant.ofNull();
        if (datum instanceof Datum.IntDatum i) return Constant.ofLong(i.value());
        if (datum instanceof Datum.DoubleDatum d) return Constant.ofDouble(d.value());
        if (datum instanceof Datum.StringDatum s) return Constant.ofString(s.value());
        return Constant.ofString(datum.toStringValue());
    }
}
