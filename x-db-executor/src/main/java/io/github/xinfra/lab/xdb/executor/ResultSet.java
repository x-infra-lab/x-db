package io.github.xinfra.lab.xdb.executor;

import io.github.xinfra.lab.xdb.executor.dml.DeleteExecutor;
import io.github.xinfra.lab.xdb.executor.dml.InsertExecutor;
import io.github.xinfra.lab.xdb.executor.dml.UpdateExecutor;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;

import java.util.List;

/**
 * Wraps an {@link Executor} for convenient result consumption.
 * Handles both query results (with columns/rows) and DML results (affected rows count).
 */
public class ResultSet {

    private final Executor executor;
    private final List<ColumnInfo> columns;
    private long affectedRows;
    private long lastInsertId;

    public ResultSet(Executor executor) {
        this.executor = executor;
        this.columns = executor.outputSchema();
    }

    /**
     * Returns true if this result set is a query (has columns to return).
     */
    public boolean isQuery() {
        return columns != null && !columns.isEmpty();
    }

    /**
     * Return the output columns.
     */
    public List<ColumnInfo> columns() {
        return columns;
    }

    /**
     * Return the next row, or null if no more rows.
     * For DML executors, this drives execution and returns null.
     */
    public Row next() throws Exception {
        return executor.next();
    }

    /**
     * Close the executor and extract DML metadata.
     */
    public void close() throws Exception {
        // Extract DML metadata before closing
        if (executor instanceof InsertExecutor ins) {
            affectedRows = ins.affectedRows();
            lastInsertId = ins.lastInsertId();
        } else if (executor instanceof UpdateExecutor upd) {
            affectedRows = upd.affectedRows();
        } else if (executor instanceof DeleteExecutor del) {
            affectedRows = del.affectedRows();
        }
        executor.close();
    }

    /**
     * Open the executor.
     */
    public void open() throws Exception {
        executor.open();
    }

    /**
     * Return the number of affected rows (for DML statements).
     */
    public long affectedRows() {
        return affectedRows;
    }

    /**
     * Return the last insert ID (for INSERT with auto-increment).
     */
    public long lastInsertId() {
        return lastInsertId;
    }

    /**
     * Return the underlying executor.
     */
    public Executor executor() {
        return executor;
    }
}
