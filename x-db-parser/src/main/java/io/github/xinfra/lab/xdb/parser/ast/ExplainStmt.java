package io.github.xinfra.lab.xdb.parser.ast;

public class ExplainStmt implements Statement {
    private final Statement statement;

    public ExplainStmt(Statement statement) {
        this.statement = statement;
    }

    public Statement getStatement() { return statement; }
}
