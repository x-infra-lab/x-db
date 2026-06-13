package io.github.xinfra.lab.xdb.parser.ast;

public class CastExpr implements ExprNode {
    private final ExprNode expr;
    private final String targetType;

    public CastExpr(ExprNode expr, String targetType) {
        this.expr = expr;
        this.targetType = targetType;
    }

    public ExprNode getExpr() { return expr; }
    public String getTargetType() { return targetType; }
}
