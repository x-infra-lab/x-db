package io.github.xinfra.lab.xdb.parser.ast;

public class DropDatabaseStmt implements Statement {
    private final String name;
    private final boolean ifExists;

    public DropDatabaseStmt(String name, boolean ifExists) {
        this.name = name;
        this.ifExists = ifExists;
    }

    public String getName() { return name; }
    public boolean isIfExists() { return ifExists; }
}
