package io.github.xinfra.lab.xdb.parser.ast;

import java.util.List;
import java.util.Map;

public class CreateTableStmt implements Statement {
    private final String tableName;
    private final List<ColumnDef> columns;
    private final List<TableConstraint> constraints;
    private final boolean ifNotExists;
    private final Map<String, String> options;

    public CreateTableStmt(String tableName, List<ColumnDef> columns,
                           List<TableConstraint> constraints, boolean ifNotExists,
                           Map<String, String> options) {
        this.tableName = tableName;
        this.columns = columns;
        this.constraints = constraints;
        this.ifNotExists = ifNotExists;
        this.options = options;
    }

    public String getTableName() { return tableName; }
    public List<ColumnDef> getColumns() { return columns; }
    public List<TableConstraint> getConstraints() { return constraints; }
    public boolean isIfNotExists() { return ifNotExists; }
    public Map<String, String> getOptions() { return options; }
}
