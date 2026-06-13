package io.github.xinfra.lab.xdb.parser.ast;

public class TruncateTableStmt implements Statement {
    private final String tableName;

    public TruncateTableStmt(String tableName) {
        this.tableName = tableName;
    }

    public String getTableName() { return tableName; }
}
