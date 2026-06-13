package io.github.xinfra.lab.xdb.parser.ast;

import java.util.List;

public class TableConstraint {
    public enum Type {
        PRIMARY, UNIQUE, INDEX
    }

    private final Type type;
    private final String name;
    private final List<IndexColumn> columns;

    public TableConstraint(Type type, String name, List<IndexColumn> columns) {
        this.type = type;
        this.name = name;
        this.columns = columns;
    }

    public Type getType() { return type; }
    public String getName() { return name; }
    public List<IndexColumn> getColumns() { return columns; }
}
