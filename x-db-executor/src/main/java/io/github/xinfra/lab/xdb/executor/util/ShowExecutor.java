package io.github.xinfra.lab.xdb.executor.util;

import io.github.xinfra.lab.xdb.executor.Executor;
import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.meta.DatabaseInfo;
import io.github.xinfra.lab.xdb.meta.IndexColumn;
import io.github.xinfra.lab.xdb.meta.IndexInfo;
import io.github.xinfra.lab.xdb.meta.InfoSchema;
import io.github.xinfra.lab.xdb.meta.TableInfo;
import io.github.xinfra.lab.xdb.planner.logical.LogicalShowStmt;

import java.util.ArrayList;
import java.util.List;

/**
 * Executes SHOW commands by querying InfoSchema.
 */
public class ShowExecutor implements Executor {

    private final LogicalShowStmt.ShowType showType;
    private final String databaseName;
    private final String tableName;
    private final InfoSchema infoSchema;
    private final List<ColumnInfo> outputColumns;

    private List<Row> rows;
    private int currentIndex;

    public ShowExecutor(LogicalShowStmt.ShowType showType, String databaseName,
                        String tableName, InfoSchema infoSchema,
                        List<ColumnInfo> outputColumns) {
        this.showType = showType;
        this.databaseName = databaseName;
        this.tableName = tableName;
        this.infoSchema = infoSchema;
        this.outputColumns = outputColumns;
    }

    @Override
    public void open() throws Exception {
        rows = new ArrayList<>();
        currentIndex = 0;

        if (showType == LogicalShowStmt.ShowType.DATABASES) {
            buildShowDatabases();
        } else if (showType == LogicalShowStmt.ShowType.TABLES) {
            buildShowTables();
        } else if (showType == LogicalShowStmt.ShowType.COLUMNS) {
            buildShowColumns();
        } else if (showType == LogicalShowStmt.ShowType.CREATE_TABLE) {
            buildShowCreateTable();
        } else if (showType == LogicalShowStmt.ShowType.VARIABLES) {
            // Return empty result for now
        } else if (showType == LogicalShowStmt.ShowType.WARNINGS) {
            // Return empty result for now
        } else if (showType == LogicalShowStmt.ShowType.STATUS) {
            // Return empty result for now
        }
    }

    @Override
    public Row next() throws Exception {
        if (currentIndex >= rows.size()) {
            return null;
        }
        return rows.get(currentIndex++);
    }

    @Override
    public void close() throws Exception {
        rows = null;
    }

    @Override
    public List<ColumnInfo> outputSchema() {
        return outputColumns;
    }

    private void buildShowDatabases() {
        List<DatabaseInfo> databases = infoSchema.listDatabases();
        for (DatabaseInfo db : databases) {
            rows.add(new Row(new Datum[]{Datum.of(db.getName())}));
        }
    }

    private void buildShowTables() {
        if (databaseName == null) {
            return;
        }
        List<TableInfo> tables = infoSchema.listTables(databaseName);
        for (TableInfo tbl : tables) {
            rows.add(new Row(new Datum[]{Datum.of(tbl.getName())}));
        }
    }

    private void buildShowColumns() {
        if (databaseName == null || tableName == null) {
            return;
        }
        TableInfo tbl = infoSchema.getTable(databaseName, tableName);
        if (tbl == null) {
            return;
        }
        for (ColumnInfo col : tbl.getColumns()) {
            String typeStr = formatColumnType(col);
            String nullStr = col.isNullable() ? "YES" : "NO";
            String keyStr = getKeyStr(col, tbl);
            String defaultStr = col.getDefaultValue() != null ? col.getDefaultValue() : "NULL";
            String extraStr = col.isAutoIncrement() ? "auto_increment" : "";

            rows.add(new Row(new Datum[]{
                    Datum.of(col.getName()),
                    Datum.of(typeStr),
                    Datum.of(nullStr),
                    Datum.of(keyStr),
                    Datum.of(defaultStr),
                    Datum.of(extraStr)
            }));
        }
    }

    private void buildShowCreateTable() {
        if (databaseName == null || tableName == null) {
            return;
        }
        TableInfo tbl = infoSchema.getTable(databaseName, tableName);
        if (tbl == null) {
            return;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE `").append(tbl.getName()).append("` (\n");

        List<ColumnInfo> cols = tbl.getColumns();
        for (int i = 0; i < cols.size(); i++) {
            ColumnInfo col = cols.get(i);
            sb.append("  `").append(col.getName()).append("` ");
            sb.append(formatColumnType(col));
            if (!col.isNullable()) {
                sb.append(" NOT NULL");
            }
            if (col.isAutoIncrement()) {
                sb.append(" AUTO_INCREMENT");
            }
            if (col.getDefaultValue() != null) {
                sb.append(" DEFAULT '").append(col.getDefaultValue()).append("'");
            }
            if (i < cols.size() - 1 || !tbl.getIndices().isEmpty()) {
                sb.append(",");
            }
            sb.append("\n");
        }

        // Indices
        List<IndexInfo> indices = tbl.getIndices();
        for (int i = 0; i < indices.size(); i++) {
            IndexInfo idx = indices.get(i);
            if (idx.isPrimary()) {
                sb.append("  PRIMARY KEY (");
            } else if (idx.isUnique()) {
                sb.append("  UNIQUE KEY `").append(idx.getName()).append("` (");
            } else {
                sb.append("  KEY `").append(idx.getName()).append("` (");
            }
            List<IndexColumn> idxCols = idx.getColumns();
            for (int j = 0; j < idxCols.size(); j++) {
                if (j > 0) sb.append(",");
                sb.append("`").append(idxCols.get(j).getColumnName()).append("`");
            }
            sb.append(")");
            if (i < indices.size() - 1) {
                sb.append(",");
            }
            sb.append("\n");
        }

        sb.append(")");
        if (tbl.getEngine() != null) {
            sb.append(" ENGINE=").append(tbl.getEngine());
        }
        if (tbl.getCharset() != null) {
            sb.append(" DEFAULT CHARSET=").append(tbl.getCharset());
        }

        rows.add(new Row(new Datum[]{
                Datum.of(tbl.getName()),
                Datum.of(sb.toString())
        }));
    }

    private String formatColumnType(ColumnInfo col) {
        DataType type = col.getType();
        StringBuilder sb = new StringBuilder(type.name().toLowerCase());
        if (col.getFieldLength() > 0) {
            if (type == DataType.DECIMAL) {
                sb.append("(").append(col.getFieldLength());
                if (col.getDecimal() > 0) {
                    sb.append(",").append(col.getDecimal());
                }
                sb.append(")");
            } else if (type.isString() || type.isBinary()) {
                sb.append("(").append(col.getFieldLength()).append(")");
            }
        }
        if (col.isUnsigned()) {
            sb.append(" unsigned");
        }
        return sb.toString();
    }

    private String getKeyStr(ColumnInfo col, TableInfo tbl) {
        IndexInfo pk = tbl.getPrimaryIndex();
        if (pk != null) {
            for (IndexColumn idxCol : pk.getColumns()) {
                if (idxCol.getColumnId() == col.getId()) {
                    return "PRI";
                }
            }
        }
        for (IndexInfo idx : tbl.getIndices()) {
            if (idx.isPrimary()) continue;
            if (idx.isUnique()) {
                for (IndexColumn idxCol : idx.getColumns()) {
                    if (idxCol.getColumnId() == col.getId()) {
                        return "UNI";
                    }
                }
            } else {
                for (IndexColumn idxCol : idx.getColumns()) {
                    if (idxCol.getColumnId() == col.getId()) {
                        return "MUL";
                    }
                }
            }
        }
        return "";
    }
}
