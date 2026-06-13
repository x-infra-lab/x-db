package io.github.xinfra.lab.xdb.parser.ast;

public class UseStmt implements Statement {
    private final String database;

    public UseStmt(String database) {
        this.database = database;
    }

    public String getDatabase() { return database; }
}
