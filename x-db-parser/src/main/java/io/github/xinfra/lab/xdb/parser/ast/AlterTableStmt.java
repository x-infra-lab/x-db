package io.github.xinfra.lab.xdb.parser.ast;

import java.util.List;

public class AlterTableStmt implements Statement {
    private final String tableName;
    private final List<AlterSpec> specs;

    public AlterTableStmt(String tableName, List<AlterSpec> specs) {
        this.tableName = tableName;
        this.specs = specs;
    }

    public String getTableName() { return tableName; }
    public List<AlterSpec> getSpecs() { return specs; }
}
