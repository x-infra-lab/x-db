package io.github.xinfra.lab.xdb.parser.ast;

public class OrderByItem {
    private final ExprNode expression;
    private final boolean asc;

    public OrderByItem(ExprNode expression, boolean asc) {
        this.expression = expression;
        this.asc = asc;
    }

    public ExprNode getExpression() { return expression; }
    public boolean isAsc() { return asc; }
}
