package io.github.xinfra.lab.xdb.parser.ast;

public class DeleteStmt implements Statement {
    private final String tableName;
    private final TableRef from;
    private final ExprNode where;

    public DeleteStmt(String tableName, ExprNode where) {
        this(tableName, null, where);
    }

    public DeleteStmt(String tableName, TableRef from, ExprNode where) {
        this.tableName = tableName;
        this.from = from;
        this.where = where;
    }

    public String getTableName() { return tableName; }
    public TableRef getFrom() { return from; }
    public ExprNode getWhere() { return where; }
}
