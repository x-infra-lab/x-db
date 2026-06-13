package io.github.xinfra.lab.xdb.parser.ast;

public class ExistsExpr implements ExprNode {
    private final SelectStmt subquery;

    public ExistsExpr(SelectStmt subquery) {
        this.subquery = subquery;
    }

    public SelectStmt getSubquery() { return subquery; }
}
