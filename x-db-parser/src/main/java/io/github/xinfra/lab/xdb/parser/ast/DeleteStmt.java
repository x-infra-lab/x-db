package io.github.xinfra.lab.xdb.parser.ast;

public class DeleteStmt implements Statement {
    private final String tableName;
    private final ExprNode where;

    public DeleteStmt(String tableName, ExprNode where) {
        this.tableName = tableName;
        this.where = where;
    }

    public String getTableName() { return tableName; }
    public ExprNode getWhere() { return where; }
}
