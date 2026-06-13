package io.github.xinfra.lab.xdb.parser.ast;

public class ShowStmt implements Statement {
    public enum ShowType {
        DATABASES, TABLES, COLUMNS, CREATE_TABLE, VARIABLES, WARNINGS, STATUS
    }

    private final ShowType type;
    private final String tableName;

    public ShowStmt(ShowType type, String tableName) {
        this.type = type;
        this.tableName = tableName;
    }

    public ShowType getType() { return type; }
    public String getTableName() { return tableName; }
}
