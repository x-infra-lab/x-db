package io.github.xinfra.lab.xdb.session;

import io.github.xinfra.lab.xdb.ddl.DDLExecutor;
import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.meta.DatabaseInfo;
import io.github.xinfra.lab.xdb.meta.IndexColumn;
import io.github.xinfra.lab.xdb.meta.IndexInfo;
import io.github.xinfra.lab.xdb.meta.InfoSchema;
import io.github.xinfra.lab.xdb.meta.MetaStore;
import io.github.xinfra.lab.xdb.meta.SchemaState;
import io.github.xinfra.lab.xdb.meta.TableInfo;
import io.github.xinfra.lab.xdb.parser.ast.AlterSpec;
import io.github.xinfra.lab.xdb.parser.ast.AlterTableStmt;
import io.github.xinfra.lab.xdb.parser.ast.ColumnDef;
import io.github.xinfra.lab.xdb.parser.ast.CreateDatabaseStmt;
import io.github.xinfra.lab.xdb.parser.ast.CreateTableStmt;
import io.github.xinfra.lab.xdb.parser.ast.DropDatabaseStmt;
import io.github.xinfra.lab.xdb.parser.ast.DropTableStmt;
import io.github.xinfra.lab.xdb.parser.ast.ExprNode;
import io.github.xinfra.lab.xdb.parser.ast.LiteralExpr;
import io.github.xinfra.lab.xdb.parser.ast.Statement;
import io.github.xinfra.lab.xdb.parser.ast.TableConstraint;
import io.github.xinfra.lab.xdb.parser.ast.TruncateTableStmt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Bridges parsed DDL AST nodes to {@link DDLExecutor} calls.
 * <p>
 * Each {@code handle*} method converts the AST representation into
 * the metadata model ({@link TableInfo}, {@link ColumnInfo}, etc.)
 * and invokes the appropriate DDL executor method.
 */
public class DDLHandler {

    private static final Logger log = LoggerFactory.getLogger(DDLHandler.class);

    private final DDLExecutor ddlExecutor;
    private final InfoSchemaHolder schemaHolder;
    private final MetaStore metaStore;

    public DDLHandler(DDLExecutor ddlExecutor, InfoSchemaHolder schemaHolder,
                      MetaStore metaStore) {
        this.ddlExecutor = ddlExecutor;
        this.schemaHolder = schemaHolder;
        this.metaStore = metaStore;
    }

    /**
     * Dispatch a DDL statement to the appropriate handler.
     *
     * @param stmt            the parsed DDL statement
     * @param currentDatabase the session's current database (may be {@code null})
     * @return execution result
     */
    public ExecuteResult handleDDL(Statement stmt, String currentDatabase) {
        if (stmt instanceof CreateDatabaseStmt s) {
            return handleCreateDatabase(s);
        } else if (stmt instanceof DropDatabaseStmt s) {
            return handleDropDatabase(s);
        } else if (stmt instanceof CreateTableStmt s) {
            return handleCreateTable(s, currentDatabase);
        } else if (stmt instanceof DropTableStmt s) {
            return handleDropTable(s, currentDatabase);
        } else if (stmt instanceof AlterTableStmt s) {
            return handleAlterTable(s, currentDatabase);
        } else if (stmt instanceof TruncateTableStmt s) {
            return handleTruncateTable(s, currentDatabase);
        } else {
            throw new RuntimeException("Unsupported DDL statement: " + stmt.getClass().getSimpleName());
        }
    }

    // ---------------------------------------------------------------
    // CREATE DATABASE
    // ---------------------------------------------------------------

    private ExecuteResult handleCreateDatabase(CreateDatabaseStmt stmt) {
        InfoSchema is = schemaHolder.get();
        DatabaseInfo existing = is.getDatabase(stmt.getName());
        if (existing != null) {
            if (stmt.isIfNotExists()) {
                return ExecuteResult.ok("Database already exists");
            }
            throw new RuntimeException("Database '" + stmt.getName() + "' already exists");
        }

        long dbId = metaStore.allocGlobalId();
        String charset = stmt.getCharset() != null ? stmt.getCharset() : "utf8mb4";
        String collation = stmt.getCollation() != null ? stmt.getCollation() : "utf8mb4_general_ci";
        DatabaseInfo db = new DatabaseInfo(dbId, stmt.getName(), charset, collation, SchemaState.PUBLIC);

        ddlExecutor.executeCreateDatabase(db);
        schemaHolder.refresh();
        log.info("Database created: {}", stmt.getName());
        return ExecuteResult.ok();
    }

    // ---------------------------------------------------------------
    // DROP DATABASE
    // ---------------------------------------------------------------

    private ExecuteResult handleDropDatabase(DropDatabaseStmt stmt) {
        InfoSchema is = schemaHolder.get();
        DatabaseInfo db = is.getDatabase(stmt.getName());
        if (db == null) {
            if (stmt.isIfExists()) {
                return ExecuteResult.ok("Database does not exist");
            }
            throw new RuntimeException("Database '" + stmt.getName() + "' does not exist");
        }

        ddlExecutor.executeDropDatabase(db.getId(), db.getName());
        schemaHolder.refresh();
        log.info("Database dropped: {}", stmt.getName());
        return ExecuteResult.ok();
    }

    // ---------------------------------------------------------------
    // CREATE TABLE
    // ---------------------------------------------------------------

    private ExecuteResult handleCreateTable(CreateTableStmt stmt, String currentDatabase) {
        String dbName = currentDatabase;
        if (dbName == null) {
            throw new RuntimeException("No database selected");
        }
        InfoSchema is = schemaHolder.get();
        DatabaseInfo db = is.getDatabase(dbName);
        if (db == null) {
            throw new RuntimeException("Database '" + dbName + "' does not exist");
        }

        // Check for existing table
        TableInfo existing = is.getTable(dbName, stmt.getTableName());
        if (existing != null) {
            if (stmt.isIfNotExists()) {
                return ExecuteResult.ok("Table already exists");
            }
            throw new RuntimeException("Table '" + stmt.getTableName() + "' already exists");
        }

        long tableId = metaStore.allocGlobalId();
        TableInfo table = new TableInfo();
        table.setId(tableId);
        table.setName(stmt.getTableName());
        table.setDbId(db.getId());
        table.setState(SchemaState.PUBLIC);

        // Parse table options
        Map<String, String> options = stmt.getOptions();
        if (options != null) {
            table.setCharset(options.getOrDefault("charset", "utf8mb4"));
            table.setCollation(options.getOrDefault("collation", "utf8mb4_general_ci"));
            table.setEngine(options.getOrDefault("engine", "xkv"));
            table.setComment(options.get("comment"));
        } else {
            table.setCharset("utf8mb4");
            table.setCollation("utf8mb4_general_ci");
            table.setEngine("xkv");
        }

        // Build columns
        List<ColumnInfo> columns = new ArrayList<>();
        int offset = 0;
        for (ColumnDef colDef : stmt.getColumns()) {
            long colId = metaStore.allocGlobalId();
            ColumnInfo col = buildColumnInfo(colDef, colId, offset);
            columns.add(col);
            table.setMaxColumnId(colId);
            offset++;
        }
        table.setColumns(columns);

        // Build indices from column-level PRIMARY KEY and table constraints
        List<IndexInfo> indices = new ArrayList<>();
        buildIndicesFromColumns(stmt, table, columns, indices);
        buildIndicesFromConstraints(stmt, table, columns, indices);
        table.setIndices(indices);

        ddlExecutor.executeCreateTable(db.getId(), table);
        schemaHolder.refresh();
        log.info("Table created: {}.{}", dbName, stmt.getTableName());
        return ExecuteResult.ok();
    }

    private ColumnInfo buildColumnInfo(ColumnDef colDef, long colId, int offset) {
        DataType dataType = DataType.fromMySQLType(colDef.getDataType());
        int fieldLength = colDef.getLength() != null ? colDef.getLength() : 0;
        int decimals = colDef.getDecimals() != null ? colDef.getDecimals() : 0;

        String defaultValue = null;
        ExprNode defExpr = colDef.getDefaultValue();
        if (defExpr instanceof LiteralExpr lit) {
            Object val = lit.getValue();
            defaultValue = val != null ? val.toString() : null;
        }

        return new ColumnInfo(
                colId,
                colDef.getName(),
                dataType,
                fieldLength,
                decimals,
                colDef.isNullable(),
                colDef.isUnsigned(),
                colDef.isAutoIncrement(),
                defaultValue,
                colDef.getComment(),
                offset,
                SchemaState.PUBLIC
        );
    }

    private void buildIndicesFromColumns(CreateTableStmt stmt, TableInfo table,
                                         List<ColumnInfo> columns, List<IndexInfo> indices) {
        for (int i = 0; i < stmt.getColumns().size(); i++) {
            ColumnDef colDef = stmt.getColumns().get(i);
            if (colDef.isPrimaryKey()) {
                long idxId = metaStore.allocGlobalId();
                ColumnInfo col = columns.get(i);
                IndexColumn idxCol = new IndexColumn(col.getName(), col.getId(), 0);
                IndexInfo idx = new IndexInfo(idxId, "PRIMARY", table.getId(),
                        List.of(idxCol), true, true, SchemaState.PUBLIC);
                indices.add(idx);
                table.setMaxIndexId(idxId);
            }
        }
    }

    private void buildIndicesFromConstraints(CreateTableStmt stmt, TableInfo table,
                                              List<ColumnInfo> columns, List<IndexInfo> indices) {
        if (stmt.getConstraints() == null) {
            return;
        }
        for (TableConstraint constraint : stmt.getConstraints()) {
            long idxId = metaStore.allocGlobalId();
            List<IndexColumn> idxCols = new ArrayList<>();
            for (io.github.xinfra.lab.xdb.parser.ast.IndexColumn ic : constraint.getColumns()) {
                // Find the column info to get its ID
                ColumnInfo col = findColumn(columns, ic.getColumnName());
                int length = ic.getLength() != null ? ic.getLength() : 0;
                idxCols.add(new IndexColumn(ic.getColumnName(), col.getId(), length));
            }

            boolean primary = constraint.getType() == TableConstraint.Type.PRIMARY;
            boolean unique = primary || constraint.getType() == TableConstraint.Type.UNIQUE;
            String idxName = constraint.getName();
            if (idxName == null || idxName.isEmpty()) {
                idxName = primary ? "PRIMARY" : "idx_" + idxId;
            }

            IndexInfo idx = new IndexInfo(idxId, idxName, table.getId(),
                    idxCols, unique, primary, SchemaState.PUBLIC);
            indices.add(idx);
            table.setMaxIndexId(idxId);
        }
    }

    private ColumnInfo findColumn(List<ColumnInfo> columns, String name) {
        for (ColumnInfo col : columns) {
            if (col.getName().equalsIgnoreCase(name)) {
                return col;
            }
        }
        throw new RuntimeException("Column '" + name + "' not found");
    }

    // ---------------------------------------------------------------
    // DROP TABLE
    // ---------------------------------------------------------------

    private ExecuteResult handleDropTable(DropTableStmt stmt, String currentDatabase) {
        String dbName = currentDatabase;
        if (dbName == null) {
            throw new RuntimeException("No database selected");
        }
        InfoSchema is = schemaHolder.get();
        DatabaseInfo db = is.getDatabase(dbName);
        if (db == null) {
            throw new RuntimeException("Database '" + dbName + "' does not exist");
        }

        TableInfo table = is.getTable(dbName, stmt.getTableName());
        if (table == null) {
            if (stmt.isIfExists()) {
                return ExecuteResult.ok("Table does not exist");
            }
            throw new RuntimeException("Table '" + stmt.getTableName() + "' does not exist");
        }

        ddlExecutor.executeDropTable(db.getId(), table.getId(), table.getName());
        schemaHolder.refresh();
        log.info("Table dropped: {}.{}", dbName, stmt.getTableName());
        return ExecuteResult.ok();
    }

    // ---------------------------------------------------------------
    // ALTER TABLE
    // ---------------------------------------------------------------

    private ExecuteResult handleAlterTable(AlterTableStmt stmt, String currentDatabase) {
        String dbName = currentDatabase;
        if (dbName == null) {
            throw new RuntimeException("No database selected");
        }
        InfoSchema is = schemaHolder.get();
        DatabaseInfo db = is.getDatabase(dbName);
        if (db == null) {
            throw new RuntimeException("Database '" + dbName + "' does not exist");
        }
        TableInfo table = is.getTable(dbName, stmt.getTableName());
        if (table == null) {
            throw new RuntimeException("Table '" + stmt.getTableName() + "' does not exist");
        }

        for (AlterSpec spec : stmt.getSpecs()) {
            if (spec instanceof AlterSpec.AddColumn addCol) {
                ColumnDef colDef = addCol.getColumnDef();
                long colId = metaStore.allocGlobalId();
                ColumnInfo col = buildColumnInfo(colDef, colId, table.getColumns().size());
                ddlExecutor.executeAddColumn(db.getId(), table.getId(), col);
            } else if (spec instanceof AlterSpec.DropColumn dropCol) {
                ddlExecutor.executeDropColumn(db.getId(), table.getId(), dropCol.getColumnName());
            } else if (spec instanceof AlterSpec.AddIndex addIdx) {
                long idxId = metaStore.allocGlobalId();
                List<IndexColumn> idxCols = new ArrayList<>();
                for (io.github.xinfra.lab.xdb.parser.ast.IndexColumn ic : addIdx.getColumns()) {
                    ColumnInfo col = table.getColumn(ic.getColumnName());
                    if (col == null) {
                        throw new RuntimeException("Column '" + ic.getColumnName() + "' not found");
                    }
                    int length = ic.getLength() != null ? ic.getLength() : 0;
                    idxCols.add(new IndexColumn(ic.getColumnName(), col.getId(), length));
                }
                String idxName = addIdx.getIndexName() != null ? addIdx.getIndexName() : "idx_" + idxId;
                IndexInfo idx = new IndexInfo(idxId, idxName, table.getId(),
                        idxCols, false, false, SchemaState.PUBLIC);
                ddlExecutor.executeAddIndex(db.getId(), table.getId(), idx);
            } else if (spec instanceof AlterSpec.DropIndex dropIdx) {
                ddlExecutor.executeDropIndex(db.getId(), table.getId(), dropIdx.getIndexName());
            } else {
                throw new RuntimeException("Unsupported ALTER spec: " + spec.getClass().getSimpleName());
            }
        }

        schemaHolder.refresh();
        log.info("Table altered: {}.{}", dbName, stmt.getTableName());
        return ExecuteResult.ok();
    }

    // ---------------------------------------------------------------
    // TRUNCATE TABLE
    // ---------------------------------------------------------------

    private ExecuteResult handleTruncateTable(TruncateTableStmt stmt, String currentDatabase) {
        String dbName = currentDatabase;
        if (dbName == null) {
            throw new RuntimeException("No database selected");
        }
        InfoSchema is = schemaHolder.get();
        DatabaseInfo db = is.getDatabase(dbName);
        if (db == null) {
            throw new RuntimeException("Database '" + dbName + "' does not exist");
        }
        TableInfo table = is.getTable(dbName, stmt.getTableName());
        if (table == null) {
            throw new RuntimeException("Table '" + stmt.getTableName() + "' does not exist");
        }

        ddlExecutor.executeTruncateTable(db.getId(), table.getId(), table.getName());
        schemaHolder.refresh();
        log.info("Table truncated: {}.{}", dbName, stmt.getTableName());
        return ExecuteResult.ok();
    }
}
