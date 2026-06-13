package io.github.xinfra.lab.xdb.executor.rel;

import io.github.xinfra.lab.xdb.executor.Executor;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;

import java.util.List;

/**
 * Wraps a child executor, computes output columns via projection expressions.
 */
public class ProjectionExecutor implements Executor {

    private final Executor child;
    private final List<Expression> expressions;
    private final List<ColumnInfo> outputColumns;
    private final EvalContext evalCtx;

    public ProjectionExecutor(Executor child, List<Expression> expressions,
                              List<ColumnInfo> outputColumns, EvalContext evalCtx) {
        this.child = child;
        this.expressions = expressions;
        this.outputColumns = outputColumns;
        this.evalCtx = evalCtx;
    }

    @Override
    public void open() throws Exception {
        child.open();
    }

    @Override
    public Row next() throws Exception {
        Row inputRow = child.next();
        if (inputRow == null) {
            return null;
        }

        Datum[] values = new Datum[expressions.size()];
        for (int i = 0; i < expressions.size(); i++) {
            values[i] = expressions.get(i).eval(evalCtx, inputRow);
        }
        return new Row(values);
    }

    @Override
    public void close() throws Exception {
        child.close();
    }

    @Override
    public List<ColumnInfo> outputSchema() {
        return outputColumns;
    }
}
