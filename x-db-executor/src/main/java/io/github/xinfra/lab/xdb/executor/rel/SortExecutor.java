package io.github.xinfra.lab.xdb.executor.rel;

import io.github.xinfra.lab.xdb.executor.Executor;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.DatumComparator;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Buffers ALL rows from child, sorts them in memory, then returns rows one by one.
 */
public class SortExecutor implements Executor {

    private final Executor child;
    private final List<Expression> orderByExprs;
    private final List<Boolean> ascending;
    private final EvalContext evalCtx;

    private List<Row> sortedRows;
    private int currentIndex;

    public SortExecutor(Executor child, List<Expression> orderByExprs,
                        List<Boolean> ascending, EvalContext evalCtx) {
        this.child = child;
        this.orderByExprs = orderByExprs;
        this.ascending = ascending;
        this.evalCtx = evalCtx;
    }

    @Override
    public void open() throws Exception {
        child.open();

        // Read all rows from child
        sortedRows = new ArrayList<>();
        Row row;
        while ((row = child.next()) != null) {
            sortedRows.add(row);
        }

        // Sort using a comparator based on ORDER BY expressions
        Comparator<Row> comparator = (r1, r2) -> {
            for (int i = 0; i < orderByExprs.size(); i++) {
                Datum v1 = orderByExprs.get(i).eval(evalCtx, r1);
                Datum v2 = orderByExprs.get(i).eval(evalCtx, r2);
                int cmp = DatumComparator.compare(v1, v2);
                if (cmp != 0) {
                    return ascending.get(i) ? cmp : -cmp;
                }
            }
            return 0;
        };
        sortedRows.sort(comparator);
        currentIndex = 0;
    }

    @Override
    public Row next() throws Exception {
        if (currentIndex >= sortedRows.size()) {
            return null;
        }
        return sortedRows.get(currentIndex++);
    }

    @Override
    public void close() throws Exception {
        child.close();
        sortedRows = null;
    }

    @Override
    public List<ColumnInfo> outputSchema() {
        return child.outputSchema();
    }
}
