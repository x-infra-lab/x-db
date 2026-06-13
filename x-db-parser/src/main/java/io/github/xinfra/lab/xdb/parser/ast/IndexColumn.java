package io.github.xinfra.lab.xdb.parser.ast;

public class IndexColumn {
    private final String columnName;
    private final Integer length;

    public IndexColumn(String columnName, Integer length) {
        this.columnName = columnName;
        this.length = length;
    }

    public String getColumnName() { return columnName; }
    public Integer getLength() { return length; }
}
