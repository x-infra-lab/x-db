package io.github.xinfra.lab.xdb.parser.ast;

public class SubqueryExpr implements ExprNode {
    private final SelectStmt query;

    public SubqueryExpr(SelectStmt query) {
        this.query = query;
    }

    public SelectStmt getQuery() { return query; }
}
