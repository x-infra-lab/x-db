package io.github.xinfra.lab.xdb.executor.rel;

import io.github.xinfra.lab.xdb.executor.Executor;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;

import java.util.List;

/**
 * Skips {@code offset} rows, then returns up to {@code count} rows from child.
 */
public class LimitExecutor implements Executor {

    private final Executor child;
    private final long count;
    private final long offset;

    private long skipped;
    private long returned;

    public LimitExecutor(Executor child, long count, long offset) {
        this.child = child;
        this.count = count;
        this.offset = offset;
    }

    @Override
    public void open() throws Exception {
        child.open();
        skipped = 0;
        returned = 0;
    }

    @Override
    public Row next() throws Exception {
        // Skip offset rows
        while (skipped < offset) {
            Row row = child.next();
            if (row == null) {
                return null;
            }
            skipped++;
        }

        // Return up to count rows
        if (returned >= count) {
            return null;
        }

        Row row = child.next();
        if (row == null) {
            return null;
        }
        returned++;
        return row;
    }

    @Override
    public void close() throws Exception {
        child.close();
    }

    @Override
    public List<ColumnInfo> outputSchema() {
        return child.outputSchema();
    }
}
