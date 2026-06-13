package io.github.xinfra.lab.xdb.parser.ast;

public class BetweenExpr implements ExprNode {
    private final ExprNode expr;
    private final ExprNode low;
    private final ExprNode high;
    private final boolean not;

    public BetweenExpr(ExprNode expr, ExprNode low, ExprNode high, boolean not) {
        this.expr = expr;
        this.low = low;
        this.high = high;
        this.not = not;
    }

    public ExprNode getExpr() { return expr; }
    public ExprNode getLow() { return low; }
    public ExprNode getHigh() { return high; }
    public boolean isNot() { return not; }
}
