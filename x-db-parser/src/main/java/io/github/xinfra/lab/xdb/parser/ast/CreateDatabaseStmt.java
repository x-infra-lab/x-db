package io.github.xinfra.lab.xdb.parser.ast;

public class CreateDatabaseStmt implements Statement {
    private final String name;
    private final boolean ifNotExists;
    private final String charset;
    private final String collation;

    public CreateDatabaseStmt(String name, boolean ifNotExists, String charset, String collation) {
        this.name = name;
        this.ifNotExists = ifNotExists;
        this.charset = charset;
        this.collation = collation;
    }

    public String getName() { return name; }
    public boolean isIfNotExists() { return ifNotExists; }
    public String getCharset() { return charset; }
    public String getCollation() { return collation; }
}
