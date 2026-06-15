package io.github.xinfra.lab.xdb.executor;

import io.github.xinfra.lab.xdb.common.XDBException;
import io.github.xinfra.lab.xdb.expression.BinaryOp;
import io.github.xinfra.lab.xdb.expression.Constant;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.expression.InExpr;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.expression.UnaryOp;
import io.github.xinfra.lab.xdb.planner.ExistsSubqueryRef;
import io.github.xinfra.lab.xdb.planner.InSubqueryRef;
import io.github.xinfra.lab.xdb.planner.ScalarSubqueryRef;
import io.github.xinfra.lab.xdb.planner.logical.LogicalPlan;
import io.github.xinfra.lab.xdb.planner.optimize.PhysicalOptimizer;
import io.github.xinfra.lab.xdb.planner.physical.PhysicalPlan;

import java.util.ArrayList;
import java.util.List;

public final class SubqueryMaterializer {

    private final ExecutorBuilder builder;
    private final PhysicalOptimizer physicalOptimizer;

    public SubqueryMaterializer(ExecutorBuilder builder) {
        this.builder = builder;
        this.physicalOptimizer = new PhysicalOptimizer();
    }

    public Expression materialize(Expression expr) {
        if (expr instanceof ScalarSubqueryRef ref) {
            return materializeScalar(ref);
        }
        if (expr instanceof ExistsSubqueryRef ref) {
            return materializeExists(ref);
        }
        if (expr instanceof InSubqueryRef ref) {
            return materializeIn(ref);
        }
        if (expr instanceof BinaryOp op) {
            Expression left = materialize(op.left());
            Expression right = materialize(op.right());
            if (left == op.left() && right == op.right()) return expr;
            return new BinaryOp(left, op.op(), right);
        }
        if (expr instanceof UnaryOp op) {
            Expression operand = materialize(op.operand());
            if (operand == op.operand()) return expr;
            return new UnaryOp(op.op(), operand);
        }
        return expr;
    }

    public List<Expression> materializeAll(List<Expression> exprs) {
        List<Expression> result = new ArrayList<>(exprs.size());
        for (Expression expr : exprs) {
            result.add(materialize(expr));
        }
        return result;
    }

    private Constant materializeScalar(ScalarSubqueryRef ref) {
        try {
            PhysicalPlan physPlan = physicalOptimizer.optimize(ref.subPlan());
            Executor exec = builder.build(physPlan);
            exec.open();
            try {
                Row row = exec.next();
                if (row == null) return Constant.ofNull();
                if (exec.next() != null) {
                    throw XDBException.internal("Scalar subquery returns more than one row");
                }
                return Constant.of(row.get(0));
            } finally {
                exec.close();
            }
        } catch (XDBException e) {
            throw e;
        } catch (Exception e) {
            throw XDBException.internal("Subquery execution failed", e);
        }
    }

    private Constant materializeExists(ExistsSubqueryRef ref) {
        try {
            PhysicalPlan physPlan = physicalOptimizer.optimize(ref.subPlan());
            Executor exec = builder.build(physPlan);
            exec.open();
            try {
                Row row = exec.next();
                return row != null ? Constant.ofTrue() : Constant.ofFalse();
            } finally {
                exec.close();
            }
        } catch (XDBException e) {
            throw e;
        } catch (Exception e) {
            throw XDBException.internal("Subquery execution failed", e);
        }
    }

    private Expression materializeIn(InSubqueryRef ref) {
        try {
            PhysicalPlan physPlan = physicalOptimizer.optimize(ref.subPlan());
            Executor exec = builder.build(physPlan);
            exec.open();
            try {
                List<Expression> values = new ArrayList<>();
                Row row;
                while ((row = exec.next()) != null) {
                    values.add(Constant.of(row.get(0)));
                }
                Expression left = materialize(ref.left());
                return new InExpr(left, values, ref.isNot());
            } finally {
                exec.close();
            }
        } catch (XDBException e) {
            throw e;
        } catch (Exception e) {
            throw XDBException.internal("Subquery execution failed", e);
        }
    }
}
