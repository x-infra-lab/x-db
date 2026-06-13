package io.github.xinfra.lab.xdb.executor.util;

import io.github.xinfra.lab.xdb.executor.Executor;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;

import java.util.Collections;
import java.util.List;

/**
 * Returns a single empty row (for expressions like SELECT 1+1).
 */
public class DualExecutor implements Executor {

    private boolean returned;

    @Override
    public void open() throws Exception {
        returned = false;
    }

    @Override
    public Row next() throws Exception {
        if (returned) {
            return null;
        }
        returned = true;
        return new Row(0);
    }

    @Override
    public void close() throws Exception {
        // no-op
    }

    @Override
    public List<ColumnInfo> outputSchema() {
        return Collections.emptyList();
    }
}
