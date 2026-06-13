package io.github.xinfra.lab.xdb.parser.ast;

public class LiteralExpr implements ExprNode {
    public enum Type {
        INT, DECIMAL, STRING, BOOL, NULL, HEX
    }

    private final Object value;
    private final Type type;

    public LiteralExpr(Object value, Type type) {
        this.value = value;
        this.type = type;
    }

    public Object getValue() { return value; }
    public Type getType() { return type; }

    public static LiteralExpr intLiteral(long value) {
        return new LiteralExpr(value, Type.INT);
    }

    public static LiteralExpr decimalLiteral(double value) {
        return new LiteralExpr(value, Type.DECIMAL);
    }

    public static LiteralExpr stringLiteral(String value) {
        return new LiteralExpr(value, Type.STRING);
    }

    public static LiteralExpr boolLiteral(boolean value) {
        return new LiteralExpr(value, Type.BOOL);
    }

    public static LiteralExpr nullLiteral() {
        return new LiteralExpr(null, Type.NULL);
    }

    public static LiteralExpr hexLiteral(String value) {
        return new LiteralExpr(value, Type.HEX);
    }
}
