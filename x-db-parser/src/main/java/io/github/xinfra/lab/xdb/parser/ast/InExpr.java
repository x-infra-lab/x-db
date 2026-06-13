package io.github.xinfra.lab.xdb.parser.ast;

import java.util.List;

public class InExpr implements ExprNode {
    private final ExprNode expr;
    private final List<ExprNode> values;
    private final boolean not;

    public InExpr(ExprNode expr, List<ExprNode> values, boolean not) {
        this.expr = expr;
        this.values = values;
        this.not = not;
    }

    public ExprNode getExpr() { return expr; }
    public List<ExprNode> getValues() { return values; }
    public boolean isNot() { return not; }
}
