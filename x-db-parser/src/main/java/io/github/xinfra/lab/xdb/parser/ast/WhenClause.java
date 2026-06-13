package io.github.xinfra.lab.xdb.parser.ast;

public class WhenClause {
    private final ExprNode condition;
    private final ExprNode result;

    public WhenClause(ExprNode condition, ExprNode result) {
        this.condition = condition;
        this.result = result;
    }

    public ExprNode getCondition() { return condition; }
    public ExprNode getResult() { return result; }
}
