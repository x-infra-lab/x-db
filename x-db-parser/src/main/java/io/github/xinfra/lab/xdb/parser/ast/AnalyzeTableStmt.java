package io.github.xinfra.lab.xdb.parser.ast;

public class AnalyzeTableStmt implements Statement {
    private final String tableName;

    public AnalyzeTableStmt(String tableName) {
        this.tableName = tableName;
    }

    public String getTableName() { return tableName; }
}
