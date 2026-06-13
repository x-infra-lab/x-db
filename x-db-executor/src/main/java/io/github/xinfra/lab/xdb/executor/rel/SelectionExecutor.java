package io.github.xinfra.lab.xdb.executor.rel;

import io.github.xinfra.lab.xdb.executor.Executor;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;

import java.util.List;

/**
 * Wraps a child executor, filters rows by evaluating conditions.
 */
public class SelectionExecutor implements Executor {

    private final Executor child;
    private final List<Expression> conditions;
    private final EvalContext evalCtx;

    public SelectionExecutor(Executor child, List<Expression> conditions, EvalContext evalCtx) {
        this.child = child;
        this.conditions = conditions;
        this.evalCtx = evalCtx;
    }

    @Override
    public void open() throws Exception {
        child.open();
    }

    @Override
    public Row next() throws Exception {
        while (true) {
            Row row = child.next();
            if (row == null) {
                return null;
            }

            if (passesFilter(row)) {
                return row;
            }
        }
    }

    @Override
    public void close() throws Exception {
        child.close();
    }

    @Override
    public List<ColumnInfo> outputSchema() {
        return child.outputSchema();
    }

    private boolean passesFilter(Row row) {
        for (Expression cond : conditions) {
            Datum result = cond.eval(evalCtx, row);
            if (!result.toBoolean()) {
                return false;
            }
        }
        return true;
    }
}
