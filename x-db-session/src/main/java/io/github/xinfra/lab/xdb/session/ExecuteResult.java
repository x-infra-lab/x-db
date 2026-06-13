package io.github.xinfra.lab.xdb.session;

import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;

import java.util.Collections;
import java.util.List;

/**
 * Result of executing a SQL statement.
 * <p>
 * For queries (SELECT), contains column metadata and result rows.
 * For DML (INSERT/UPDATE/DELETE), contains affected-rows and last-insert-id.
 * For DDL (CREATE/DROP/ALTER), both counts are zero.
 */
public class ExecuteResult {

    private final boolean query;
    private final List<ColumnInfo> columns;
    private final List<Row> rows;
    private final long affectedRows;
    private final long lastInsertId;
    private final String message;

    private ExecuteResult(boolean query, List<ColumnInfo> columns, List<Row> rows,
                          long affectedRows, long lastInsertId, String message) {
        this.query = query;
        this.columns = columns;
        this.rows = rows;
        this.affectedRows = affectedRows;
        this.lastInsertId = lastInsertId;
        this.message = message;
    }

    /** Build a query result (SELECT). */
    public static ExecuteResult query(List<ColumnInfo> columns, List<Row> rows) {
        return new ExecuteResult(true, columns, rows, 0, 0, null);
    }

    /** Build a DML result (INSERT/UPDATE/DELETE). */
    public static ExecuteResult dml(long affectedRows, long lastInsertId) {
        return new ExecuteResult(false, Collections.emptyList(), Collections.emptyList(),
                affectedRows, lastInsertId, null);
    }

    /** Build a DDL/utility result (CREATE TABLE, USE, SET, etc.). */
    public static ExecuteResult ok() {
        return new ExecuteResult(false, Collections.emptyList(), Collections.emptyList(),
                0, 0, null);
    }

    /** Build a DDL/utility result with a status message. */
    public static ExecuteResult ok(String message) {
        return new ExecuteResult(false, Collections.emptyList(), Collections.emptyList(),
                0, 0, message);
    }

    public boolean isQuery() {
        return query;
    }

    public List<ColumnInfo> getColumns() {
        return columns;
    }

    public List<Row> getRows() {
        return rows;
    }

    public long getAffectedRows() {
        return affectedRows;
    }

    public long getLastInsertId() {
        return lastInsertId;
    }

    public String getMessage() {
        return message;
    }
}
