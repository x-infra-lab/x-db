package io.github.xinfra.lab.xdb.parser.ast;

public class DescribeStmt implements Statement {
    private final String tableName;

    public DescribeStmt(String tableName) {
        this.tableName = tableName;
    }

    public String getTableName() { return tableName; }
}
