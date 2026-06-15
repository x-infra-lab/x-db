package io.github.xinfra.lab.xdb.executor;

import io.github.xinfra.lab.xdb.executor.dml.DeleteExecutor;
import io.github.xinfra.lab.xdb.executor.dml.InsertExecutor;
import io.github.xinfra.lab.xdb.executor.dml.UpdateExecutor;
import io.github.xinfra.lab.xdb.executor.rel.HashAggExecutor;
import io.github.xinfra.lab.xdb.executor.rel.HashJoinExecutor;
import io.github.xinfra.lab.xdb.executor.rel.LimitExecutor;
import io.github.xinfra.lab.xdb.executor.rel.NestedLoopJoinExecutor;
import io.github.xinfra.lab.xdb.executor.rel.ProjectionExecutor;
import io.github.xinfra.lab.xdb.executor.rel.SelectionExecutor;
import io.github.xinfra.lab.xdb.executor.rel.SortExecutor;
import io.github.xinfra.lab.xdb.executor.rel.StreamAggExecutor;
import io.github.xinfra.lab.xdb.executor.scan.IndexLookupExecutor;
import io.github.xinfra.lab.xdb.executor.scan.IndexScanExecutor;
import io.github.xinfra.lab.xdb.executor.scan.TableScanExecutor;
import io.github.xinfra.lab.xdb.executor.util.DualExecutor;
import io.github.xinfra.lab.xdb.executor.util.ShowExecutor;
import io.github.xinfra.lab.xdb.common.MemoryTracker;
import io.github.xinfra.lab.xdb.common.XDBException;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.meta.InfoSchema;
import io.github.xinfra.lab.xdb.planner.physical.PhysicalDelete;
import io.github.xinfra.lab.xdb.planner.physical.PhysicalDual;
import io.github.xinfra.lab.xdb.planner.physical.PhysicalHashAgg;
import io.github.xinfra.lab.xdb.planner.physical.PhysicalHashJoin;
import io.github.xinfra.lab.xdb.planner.physical.PhysicalIndexLookup;
import io.github.xinfra.lab.xdb.planner.physical.PhysicalIndexScan;
import io.github.xinfra.lab.xdb.planner.physical.PhysicalInsert;
import io.github.xinfra.lab.xdb.planner.physical.PhysicalLimit;
import io.github.xinfra.lab.xdb.planner.physical.PhysicalNestedLoopJoin;
import io.github.xinfra.lab.xdb.planner.physical.PhysicalPlan;
import io.github.xinfra.lab.xdb.planner.physical.PhysicalProjection;
import io.github.xinfra.lab.xdb.planner.physical.PhysicalSelection;
import io.github.xinfra.lab.xdb.planner.physical.PhysicalShowStmt;
import io.github.xinfra.lab.xdb.planner.physical.PhysicalSort;
import io.github.xinfra.lab.xdb.planner.physical.PhysicalStreamAgg;
import io.github.xinfra.lab.xdb.planner.physical.PhysicalTableScan;
import io.github.xinfra.lab.xdb.planner.physical.PhysicalUpdate;

/**
 * Converts a {@link PhysicalPlan} tree into an {@link Executor} tree,
 * wiring each executor with the {@link TransactionContext} for KV access.
 */
public class ExecutorBuilder {

    private final TransactionContext txnCtx;
    private final InfoSchema infoSchema;
    private final String currentDatabase;
    private final MemoryTracker memTracker;
    private SubqueryMaterializer subqueryMaterializer;

    public ExecutorBuilder(TransactionContext txnCtx) {
        this(txnCtx, null, null, MemoryTracker.noopTracker());
    }

    public ExecutorBuilder(TransactionContext txnCtx, InfoSchema infoSchema,
                           String currentDatabase) {
        this(txnCtx, infoSchema, currentDatabase, MemoryTracker.noopTracker());
    }

    public ExecutorBuilder(TransactionContext txnCtx, InfoSchema infoSchema,
                           String currentDatabase, MemoryTracker memTracker) {
        this.txnCtx = txnCtx;
        this.infoSchema = infoSchema;
        this.currentDatabase = currentDatabase;
        this.memTracker = memTracker;
    }

    private SubqueryMaterializer materializer() {
        if (subqueryMaterializer == null) {
            subqueryMaterializer = new SubqueryMaterializer(this);
        }
        return subqueryMaterializer;
    }

    /**
     * Recursively build an executor tree from a physical plan tree.
     */
    public Executor build(PhysicalPlan plan) {
        if (plan instanceof PhysicalTableScan p) {
            return buildTableScan(p);
        } else if (plan instanceof PhysicalIndexScan p) {
            return buildIndexScan(p);
        } else if (plan instanceof PhysicalIndexLookup p) {
            return buildIndexLookup(p);
        } else if (plan instanceof PhysicalProjection p) {
            return buildProjection(p);
        } else if (plan instanceof PhysicalSelection p) {
            return buildSelection(p);
        } else if (plan instanceof PhysicalSort p) {
            return buildSort(p);
        } else if (plan instanceof PhysicalLimit p) {
            return buildLimit(p);
        } else if (plan instanceof PhysicalHashAgg p) {
            return buildHashAgg(p);
        } else if (plan instanceof PhysicalStreamAgg p) {
            return buildStreamAgg(p);
        } else if (plan instanceof PhysicalHashJoin p) {
            return buildHashJoin(p);
        } else if (plan instanceof PhysicalNestedLoopJoin p) {
            return buildNestedLoopJoin(p);
        } else if (plan instanceof PhysicalInsert p) {
            return buildInsert(p);
        } else if (plan instanceof PhysicalUpdate p) {
            return buildUpdate(p);
        } else if (plan instanceof PhysicalDelete p) {
            return buildDelete(p);
        } else if (plan instanceof PhysicalDual) {
            return new DualExecutor();
        } else if (plan instanceof PhysicalShowStmt p) {
            return buildShow(p);
        } else {
            throw XDBException.internal("Unsupported plan: " + plan.getClass().getSimpleName());
        }
    }

    private Executor buildTableScan(PhysicalTableScan plan) {
        return new TableScanExecutor(txnCtx, plan.table(),
                plan.outputSchema(), plan.accessConditions());
    }

    private Executor buildIndexScan(PhysicalIndexScan plan) {
        return new IndexScanExecutor(txnCtx, plan.table(), plan.index(),
                plan.outputSchema(), plan.accessConditions());
    }

    private Executor buildIndexLookup(PhysicalIndexLookup plan) {
        IndexScanExecutor idxScan = new IndexScanExecutor(txnCtx,
                plan.indexScan().table(), plan.indexScan().index(),
                plan.indexScan().outputSchema(), plan.indexScan().accessConditions());
        return new IndexLookupExecutor(txnCtx, idxScan, plan.table(), plan.outputSchema());
    }

    private Executor buildProjection(PhysicalProjection plan) {
        Executor child = build(plan.child());
        EvalContext evalCtx = txnCtx.evalContext();
        java.util.List<Expression> exprs = materializer().materializeAll(plan.expressions());
        return new ProjectionExecutor(child, exprs, plan.outputSchema(), evalCtx);
    }

    private Executor buildSelection(PhysicalSelection plan) {
        Executor child = build(plan.child());
        EvalContext evalCtx = txnCtx.evalContext();
        java.util.List<Expression> conditions = materializer().materializeAll(plan.conditions());
        return new SelectionExecutor(child, conditions, evalCtx);
    }

    private Executor buildSort(PhysicalSort plan) {
        Executor child = build(plan.child());
        EvalContext evalCtx = txnCtx.evalContext();
        return new SortExecutor(child, plan.orderByExprs(), plan.ascending(), evalCtx,
                memTracker.newChild("sort"));
    }

    private Executor buildLimit(PhysicalLimit plan) {
        Executor child = build(plan.child());
        return new LimitExecutor(child, plan.count(), plan.offset());
    }

    private Executor buildHashAgg(PhysicalHashAgg plan) {
        Executor child = build(plan.child());
        EvalContext evalCtx = txnCtx.evalContext();
        return new HashAggExecutor(child, plan.groupByExprs(), plan.aggFunctions(),
                plan.outputSchema(), evalCtx, memTracker.newChild("hash_agg"));
    }

    private Executor buildStreamAgg(PhysicalStreamAgg plan) {
        Executor child = build(plan.child());
        EvalContext evalCtx = txnCtx.evalContext();
        return new StreamAggExecutor(child, plan.groupByExprs(), plan.aggFunctions(),
                plan.outputSchema(), evalCtx);
    }

    private Executor buildHashJoin(PhysicalHashJoin plan) {
        Executor buildSide = build(plan.buildSide());
        Executor probeSide = build(plan.probeSide());
        EvalContext evalCtx = txnCtx.evalContext();
        return new HashJoinExecutor(buildSide, probeSide, plan.joinType(),
                plan.condition(), plan.buildKeys(), plan.probeKeys(),
                plan.outputSchema(), evalCtx, memTracker.newChild("hash_join"));
    }

    private Executor buildNestedLoopJoin(PhysicalNestedLoopJoin plan) {
        Executor outer = build(plan.outer());
        Executor inner = build(plan.inner());
        EvalContext evalCtx = txnCtx.evalContext();
        return new NestedLoopJoinExecutor(outer, inner, plan.joinType(),
                plan.condition(), plan.outputSchema(), evalCtx);
    }

    private Executor buildInsert(PhysicalInsert plan) {
        return new InsertExecutor(txnCtx, plan.table(),
                plan.targetColumns(), plan.rows());
    }

    private Executor buildUpdate(PhysicalUpdate plan) {
        Executor child = build(plan.child());
        return new UpdateExecutor(txnCtx, child, plan.table(),
                plan.updateColumns(), plan.updateValues());
    }

    private Executor buildDelete(PhysicalDelete plan) {
        Executor child = build(plan.child());
        return new DeleteExecutor(txnCtx, child, plan.table());
    }

    private Executor buildShow(PhysicalShowStmt plan) {
        String dbName = plan.databaseName() != null ? plan.databaseName() : currentDatabase;
        return new ShowExecutor(plan.showType(), dbName, plan.tableName(),
                infoSchema, plan.outputSchema());
    }
}
