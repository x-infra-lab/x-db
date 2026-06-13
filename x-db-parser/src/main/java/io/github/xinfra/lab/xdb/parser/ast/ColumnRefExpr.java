package io.github.xinfra.lab.xdb.parser.ast;

public class ColumnRefExpr implements ExprNode {
    private final String tableName;
    private final String columnName;

    public ColumnRefExpr(String tableName, String columnName) {
        this.tableName = tableName;
        this.columnName = columnName;
    }

    public String getTableName() { return tableName; }
    public String getColumnName() { return columnName; }
}
