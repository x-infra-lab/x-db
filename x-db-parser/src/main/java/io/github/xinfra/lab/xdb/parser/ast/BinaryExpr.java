package io.github.xinfra.lab.xdb.parser.ast;

public class BinaryExpr implements ExprNode {
    public enum Op {
        ADD, SUB, MUL, DIV, MOD, INT_DIV,
        EQ, NE, LT, LE, GT, GE,
        AND, OR
    }

    private final ExprNode left;
    private final ExprNode right;
    private final Op op;

    public BinaryExpr(ExprNode left, Op op, ExprNode right) {
        this.left = left;
        this.op = op;
        this.right = right;
    }

    public ExprNode getLeft() { return left; }
    public ExprNode getRight() { return right; }
    public Op getOp() { return op; }
}
