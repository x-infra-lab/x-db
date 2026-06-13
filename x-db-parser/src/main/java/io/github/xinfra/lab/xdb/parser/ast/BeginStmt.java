package io.github.xinfra.lab.xdb.parser.ast;

public class BeginStmt implements Statement {
    private final boolean optimistic;

    public BeginStmt(boolean optimistic) {
        this.optimistic = optimistic;
    }

    public boolean isOptimistic() { return optimistic; }
}
