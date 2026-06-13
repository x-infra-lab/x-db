package io.github.xinfra.lab.xdb.parser.ast;

public class LikeExpr implements ExprNode {
    private final ExprNode expr;
    private final ExprNode pattern;
    private final boolean not;

    public LikeExpr(ExprNode expr, ExprNode pattern, boolean not) {
        this.expr = expr;
        this.pattern = pattern;
        this.not = not;
    }

    public ExprNode getExpr() { return expr; }
    public ExprNode getPattern() { return pattern; }
    public boolean isNot() { return not; }
}
