package io.github.xinfra.lab.xdb.session;

import io.github.xinfra.lab.xdb.ddl.DDLExecutor;
import io.github.xinfra.lab.xdb.executor.Executor;
import io.github.xinfra.lab.xdb.executor.ExecutorBuilder;
import io.github.xinfra.lab.xdb.executor.TransactionContext;
import io.github.xinfra.lab.xdb.executor.dml.DeleteExecutor;
import io.github.xinfra.lab.xdb.executor.dml.InsertExecutor;
import io.github.xinfra.lab.xdb.executor.dml.UpdateExecutor;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.meta.DatabaseInfo;
import io.github.xinfra.lab.xdb.meta.InfoSchema;
import io.github.xinfra.lab.xdb.meta.MetaStore;
import io.github.xinfra.lab.xdb.parser.SQLParser;
import io.github.xinfra.lab.xdb.parser.ast.AlterTableStmt;
import io.github.xinfra.lab.xdb.parser.ast.BeginStmt;
import io.github.xinfra.lab.xdb.parser.ast.CommitStmt;
import io.github.xinfra.lab.xdb.parser.ast.CreateDatabaseStmt;
import io.github.xinfra.lab.xdb.parser.ast.CreateTableStmt;
import io.github.xinfra.lab.xdb.parser.ast.DeleteStmt;
import io.github.xinfra.lab.xdb.parser.ast.DescribeStmt;
import io.github.xinfra.lab.xdb.parser.ast.DropDatabaseStmt;
import io.github.xinfra.lab.xdb.parser.ast.DropTableStmt;
import io.github.xinfra.lab.xdb.parser.ast.ExplainStmt;
import io.github.xinfra.lab.xdb.parser.ast.ExprNode;
import io.github.xinfra.lab.xdb.parser.ast.InsertStmt;
import io.github.xinfra.lab.xdb.parser.ast.LiteralExpr;
import io.github.xinfra.lab.xdb.parser.ast.RollbackStmt;
import io.github.xinfra.lab.xdb.parser.ast.SelectStmt;
import io.github.xinfra.lab.xdb.parser.ast.SetStmt;
import io.github.xinfra.lab.xdb.parser.ast.ShowStmt;
import io.github.xinfra.lab.xdb.parser.ast.Statement;
import io.github.xinfra.lab.xdb.parser.ast.TruncateTableStmt;
import io.github.xinfra.lab.xdb.parser.ast.UpdateStmt;
import io.github.xinfra.lab.xdb.parser.ast.UseStmt;
import io.github.xinfra.lab.xdb.planner.Planner;
import io.github.xinfra.lab.xdb.planner.physical.PhysicalPlan;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * Default implementation of {@link Session}.
 * <p>
 * Implements the full SQL execution pipeline: parse -> plan -> build -> execute.
 * Manages transaction lifecycle and session state.
 */
public class SessionImpl implements Session {

    private static final Logger log = LoggerFactory.getLogger(SessionImpl.class);
    private static final int MAX_RESULT_ROWS = 1_000_000;

    private final long id;
    private final SessionVariable variables;
    private volatile SessionState state;
    private String currentDatabase;
    private final InfoSchemaHolder schemaHolder;
    private final DDLExecutor ddlExecutor;
    private final DDLHandler ddlHandler;
    private final TransactionManager txnManager;
    private final MetaStore metaStore;
    private final Runnable onClose;

    public SessionImpl(long id,
                       InfoSchemaHolder schemaHolder,
                       DDLExecutor ddlExecutor,
                       TransactionManager txnManager,
                       MetaStore metaStore) {
        this(id, schemaHolder, ddlExecutor, txnManager, metaStore, null);
    }

    public SessionImpl(long id,
                       InfoSchemaHolder schemaHolder,
                       DDLExecutor ddlExecutor,
                       TransactionManager txnManager,
                       MetaStore metaStore,
                       Runnable onClose) {
        this.id = id;
        this.variables = new SessionVariable();
        this.state = SessionState.IDLE;
        this.schemaHolder = schemaHolder;
        this.ddlExecutor = ddlExecutor;
        this.ddlHandler = new DDLHandler(ddlExecutor, schemaHolder, metaStore);
        this.txnManager = txnManager;
        this.metaStore = metaStore;
        this.onClose = onClose;
    }

    // ---------------------------------------------------------------
    // Session interface
    // ---------------------------------------------------------------

    @Override
    public ExecuteResult execute(String sql) {
        checkState();

        try {
            // 1. Parse SQL -> Statement
            Statement stmt = SQLParser.parse(sql);
            log.debug("Session {}: parsed {} from SQL: {}", id,
                    stmt.getClass().getSimpleName(), truncateSQL(sql));

            // 2. Dispatch based on statement type
            return dispatch(stmt, sql);

        } catch (Exception e) {
            if (variables.isAutoCommit() && txnManager.isActive()) {
                txnManager.rollbackQuietly();
            }
            log.error("Session {}: execution failed for SQL: {}", id, truncateSQL(sql), e);
            if (e instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("Execution failed: " + e.getMessage(), e);
        }
    }

    @Override
    public void begin(boolean optimistic) {
        checkState();
        txnManager.begin(!optimistic); // optimistic=true means pessimistic=false
        state = SessionState.IN_TRANSACTION;
        log.debug("Session {}: explicit transaction started (optimistic={})", id, optimistic);
    }

    @Override
    public void commit() {
        checkState();
        if (!txnManager.isActive()) {
            throw new IllegalStateException("No active transaction to commit");
        }
        txnManager.commit();
        state = SessionState.IDLE;
        log.debug("Session {}: transaction committed", id);
    }

    @Override
    public void rollback() {
        checkState();
        if (!txnManager.isActive()) {
            throw new IllegalStateException("No active transaction to rollback");
        }
        txnManager.rollback();
        state = SessionState.IDLE;
        log.debug("Session {}: transaction rolled back", id);
    }

    @Override
    public void useDatabase(String dbName) {
        checkState();
        // Verify database exists
        schemaHolder.refresh();
        InfoSchema is = schemaHolder.get();
        DatabaseInfo db = is.getDatabase(dbName);
        if (db == null) {
            throw new RuntimeException("Unknown database '" + dbName + "'");
        }
        this.currentDatabase = dbName;
        log.debug("Session {}: switched to database '{}'", id, dbName);
    }

    @Override
    public String currentDatabase() {
        return currentDatabase;
    }

    @Override
    public void close() {
        if (state == SessionState.CLOSED) {
            return;
        }
        state = SessionState.CLOSING;
        try {
            if (txnManager.isActive()) {
                txnManager.rollbackQuietly();
            }
        } finally {
            state = SessionState.CLOSED;
            log.debug("Session {}: closed", id);
            if (onClose != null) {
                try {
                    onClose.run();
                } catch (Exception e) {
                    log.warn("Session {}: onClose callback failed", id, e);
                }
            }
        }
    }

    @Override
    public long id() {
        return id;
    }

    @Override
    public SessionState state() {
        return state;
    }

    // ---------------------------------------------------------------
    // Statement dispatch
    // ---------------------------------------------------------------

    private ExecuteResult dispatch(Statement stmt, String sql) throws Exception {
        // DDL statements
        if (isDDL(stmt)) {
            return handleDDL(stmt);
        }

        // Transaction control
        if (stmt instanceof BeginStmt beginStmt) {
            begin(beginStmt.isOptimistic());
            return ExecuteResult.ok();
        }
        if (stmt instanceof CommitStmt) {
            commit();
            return ExecuteResult.ok();
        }
        if (stmt instanceof RollbackStmt) {
            rollback();
            return ExecuteResult.ok();
        }

        // SET statement
        if (stmt instanceof SetStmt setStmt) {
            return handleSet(setStmt);
        }

        // USE statement
        if (stmt instanceof UseStmt useStmt) {
            useDatabase(useStmt.getDatabase());
            return ExecuteResult.ok();
        }

        // EXPLAIN statement
        if (stmt instanceof ExplainStmt explainStmt) {
            return handleExplain(explainStmt);
        }

        // DESCRIBE statement
        if (stmt instanceof DescribeStmt describeStmt) {
            return handleDescribe(describeStmt);
        }

        // DML / query: SELECT, INSERT, UPDATE, DELETE, SHOW
        return handleDMLOrQuery(stmt);
    }

    // ---------------------------------------------------------------
    // DDL
    // ---------------------------------------------------------------

    private boolean isDDL(Statement stmt) {
        return stmt instanceof CreateDatabaseStmt
                || stmt instanceof DropDatabaseStmt
                || stmt instanceof CreateTableStmt
                || stmt instanceof DropTableStmt
                || stmt instanceof AlterTableStmt
                || stmt instanceof TruncateTableStmt;
    }

    private ExecuteResult handleDDL(Statement stmt) {
        // DDL should not run inside an explicit transaction
        if (state == SessionState.IN_TRANSACTION) {
            throw new RuntimeException("Cannot execute DDL inside an explicit transaction");
        }
        return ddlHandler.handleDDL(stmt, currentDatabase);
    }

    // ---------------------------------------------------------------
    // SET
    // ---------------------------------------------------------------

    private ExecuteResult handleSet(SetStmt stmt) {
        String value = evaluateExprToString(stmt.getValue());
        variables.set(stmt.getVariable(), value);
        log.debug("Session {}: SET {} = {}", id, stmt.getVariable(), value);
        return ExecuteResult.ok();
    }

    // ---------------------------------------------------------------
    // EXPLAIN
    // ---------------------------------------------------------------

    private ExecuteResult handleExplain(ExplainStmt stmt) {
        schemaHolder.refresh();
        InfoSchema is = schemaHolder.get();
        Planner planner = new Planner(is);
        String explanation = planner.explain(stmt.getStatement(), currentDatabase);

        // Return as a single-column result set
        ColumnInfo col = new ColumnInfo();
        col.setName("EXPLAIN");
        col.setType(io.github.xinfra.lab.xdb.expression.DataType.VARCHAR);

        Row row = new Row(new Datum[]{Datum.of(explanation)});
        return ExecuteResult.query(List.of(col), List.of(row));
    }

    // ---------------------------------------------------------------
    // DESCRIBE
    // ---------------------------------------------------------------

    private ExecuteResult handleDescribe(DescribeStmt stmt) {
        if (currentDatabase == null) {
            throw new RuntimeException("No database selected");
        }
        schemaHolder.refresh();
        InfoSchema is = schemaHolder.get();
        var table = is.getTable(currentDatabase, stmt.getTableName());
        if (table == null) {
            throw new RuntimeException("Table '" + stmt.getTableName() + "' does not exist");
        }

        // Build DESCRIBE columns: Field, Type, Null, Key, Default, Extra
        List<ColumnInfo> descCols = buildDescribeColumns();
        List<Row> rows = new ArrayList<>();

        for (ColumnInfo col : table.getPublicColumns()) {
            Datum[] values = new Datum[6];
            values[0] = Datum.of(col.getName());
            values[1] = Datum.of(col.getType().name().toLowerCase());
            values[2] = Datum.of(col.isNullable() ? "YES" : "NO");
            values[3] = Datum.of(getKeyType(table, col));
            values[4] = col.getDefaultValue() != null ? Datum.of(col.getDefaultValue()) : Datum.nil();
            values[5] = Datum.of(col.isAutoIncrement() ? "auto_increment" : "");
            rows.add(new Row(values));
        }

        return ExecuteResult.query(descCols, rows);
    }

    private List<ColumnInfo> buildDescribeColumns() {
        String[] names = {"Field", "Type", "Null", "Key", "Default", "Extra"};
        List<ColumnInfo> cols = new ArrayList<>();
        for (int i = 0; i < names.length; i++) {
            ColumnInfo col = new ColumnInfo();
            col.setName(names[i]);
            col.setType(io.github.xinfra.lab.xdb.expression.DataType.VARCHAR);
            col.setOffset(i);
            cols.add(col);
        }
        return cols;
    }

    private String getKeyType(io.github.xinfra.lab.xdb.meta.TableInfo table, ColumnInfo col) {
        var pk = table.getPrimaryIndex();
        if (pk != null) {
            for (var idxCol : pk.getColumns()) {
                if (idxCol.getColumnId() == col.getId()) {
                    return "PRI";
                }
            }
        }
        for (var idx : table.getIndices()) {
            if (idx.isPrimary()) continue;
            for (var idxCol : idx.getColumns()) {
                if (idxCol.getColumnId() == col.getId()) {
                    return idx.isUnique() ? "UNI" : "MUL";
                }
            }
        }
        return "";
    }

    // ---------------------------------------------------------------
    // DML / Query execution pipeline
    // ---------------------------------------------------------------

    private ExecuteResult handleDMLOrQuery(Statement stmt) throws Exception {
        boolean isQuery = stmt instanceof SelectStmt || stmt instanceof ShowStmt;
        boolean autoCommitTxn = false;

        // Ensure a transaction is active
        if (!txnManager.isActive()) {
            if (variables.isAutoCommit()) {
                // Auto-begin for this statement
                txnManager.beginIfNeeded(true); // pessimistic by default
                autoCommitTxn = true;
            } else {
                // MySQL behavior: implicitly start a transaction when autocommit is off
                txnManager.beginIfNeeded(true);
                state = SessionState.IN_TRANSACTION;
            }
        }

        try {
            schemaHolder.refreshIfNeeded();
            InfoSchema is = schemaHolder.get();

            // Plan
            Planner planner = new Planner(is);
            PhysicalPlan physicalPlan = planner.plan(stmt, currentDatabase);

            // Build executor
            TransactionContext txnCtx = txnManager.currentContext();
            ExecutorBuilder executorBuilder = new ExecutorBuilder(txnCtx, is, currentDatabase);
            Executor executor = executorBuilder.build(physicalPlan);

            // Execute
            ExecuteResult result;
            try {
                executor.open();

                if (isQuery) {
                    result = executeQuery(executor);
                } else {
                    result = executeDML(executor);
                }
            } finally {
                executor.close();
            }

            // Auto-commit if needed
            if (autoCommitTxn && txnManager.isActive()) {
                txnManager.commit();
            }

            return result;

        } catch (Exception e) {
            // Rollback on error if auto-commit
            if (autoCommitTxn && txnManager.isActive()) {
                txnManager.rollbackQuietly();
            }
            throw e;
        }
    }

    /**
     * Execute a query (SELECT/SHOW): drain all rows from the executor.
     */
    private ExecuteResult executeQuery(Executor executor) throws Exception {
        List<ColumnInfo> columns = executor.outputSchema();
        List<Row> rows = new ArrayList<>();

        Row row;
        while ((row = executor.next()) != null) {
            rows.add(row);
            if (rows.size() >= MAX_RESULT_ROWS) {
                throw new RuntimeException("Result set too large (exceeds " + MAX_RESULT_ROWS + " rows)");
            }
        }

        return ExecuteResult.query(columns, rows);
    }

    /**
     * Execute a DML (INSERT/UPDATE/DELETE): drain the executor and
     * return affected row count.
     */
    private ExecuteResult executeDML(Executor executor) throws Exception {
        // Drive the executor to completion
        while (executor.next() != null) {
            // drain
        }

        if (executor instanceof InsertExecutor insertExec) {
            return ExecuteResult.dml(insertExec.affectedRows(), insertExec.lastInsertId());
        } else if (executor instanceof UpdateExecutor updateExec) {
            return ExecuteResult.dml(updateExec.affectedRows(), 0);
        } else if (executor instanceof DeleteExecutor deleteExec) {
            return ExecuteResult.dml(deleteExec.affectedRows(), 0);
        }

        // Fallback for other executor types
        return ExecuteResult.dml(0, 0);
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private void checkState() {
        if (state == SessionState.CLOSED || state == SessionState.CLOSING) {
            throw new IllegalStateException("Session " + id + " is closed");
        }
    }

    private String evaluateExprToString(ExprNode expr) {
        if (expr instanceof LiteralExpr lit) {
            Object val = lit.getValue();
            return val != null ? val.toString() : null;
        }
        // For non-literal expressions, fall back to toString
        return expr != null ? expr.toString() : null;
    }

    private static String truncateSQL(String sql) {
        if (sql == null) return "null";
        if (sql.length() <= 200) return sql;
        return sql.substring(0, 200) + "...";
    }

    /**
     * Return the session variables for inspection.
     */
    public SessionVariable variables() {
        return variables;
    }
}
