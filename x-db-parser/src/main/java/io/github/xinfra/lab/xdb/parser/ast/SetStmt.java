package io.github.xinfra.lab.xdb.parser.ast;

public class SetStmt implements Statement {
    public enum Scope {
        SESSION, GLOBAL, NONE
    }

    private final String variable;
    private final ExprNode value;
    private final Scope scope;

    public SetStmt(String variable, ExprNode value, Scope scope) {
        this.variable = variable;
        this.value = value;
        this.scope = scope;
    }

    public String getVariable() { return variable; }
    public ExprNode getValue() { return value; }
    public Scope getScope() { return scope; }
}
