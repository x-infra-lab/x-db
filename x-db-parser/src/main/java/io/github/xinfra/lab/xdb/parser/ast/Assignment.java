package io.github.xinfra.lab.xdb.parser.ast;

public class Assignment {
    private final String column;
    private final ExprNode value;

    public Assignment(String column, ExprNode value) {
        this.column = column;
        this.value = value;
    }

    public String getColumn() { return column; }
    public ExprNode getValue() { return value; }
}
