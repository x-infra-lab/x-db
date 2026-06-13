package io.github.xinfra.lab.xdb.parser.ast;

import java.util.List;

public class FunctionCallExpr implements ExprNode {
    private final String name;
    private final List<ExprNode> args;
    private final boolean distinct;

    public FunctionCallExpr(String name, List<ExprNode> args, boolean distinct) {
        this.name = name;
        this.args = args;
        this.distinct = distinct;
    }

    public String getName() { return name; }
    public List<ExprNode> getArgs() { return args; }
    public boolean isDistinct() { return distinct; }
}
