package io.github.xinfra.lab.xdb.parser.ast;

public class DropTableStmt implements Statement {
    private final String tableName;
    private final boolean ifExists;

    public DropTableStmt(String tableName, boolean ifExists) {
        this.tableName = tableName;
        this.ifExists = ifExists;
    }

    public String getTableName() { return tableName; }
    public boolean isIfExists() { return ifExists; }
}
