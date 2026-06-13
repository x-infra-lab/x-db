package io.github.xinfra.lab.xdb.parser.ast;

public class UnaryExpr implements ExprNode {
    public enum Op {
        NOT, NEG, IS_NULL, IS_NOT_NULL
    }

    private final ExprNode operand;
    private final Op op;

    public UnaryExpr(Op op, ExprNode operand) {
        this.op = op;
        this.operand = operand;
    }

    public ExprNode getOperand() { return operand; }
    public Op getOp() { return op; }
}
