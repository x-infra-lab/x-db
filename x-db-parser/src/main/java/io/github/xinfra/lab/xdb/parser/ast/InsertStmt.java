package io.github.xinfra.lab.xdb.parser.ast;

import java.util.List;

public class InsertStmt implements Statement {
    private final String tableName;
    private final List<String> columns;
    private final List<List<ExprNode>> values;

    public InsertStmt(String tableName, List<String> columns, List<List<ExprNode>> values) {
        this.tableName = tableName;
        this.columns = columns;
        this.values = values;
    }

    public String getTableName() { return tableName; }
    public List<String> getColumns() { return columns; }
    public List<List<ExprNode>> getValues() { return values; }
}
