package io.github.xinfra.lab.xdb.expression;

public final class ColumnRef implements Expression {
    private final String tableName;
    private final String columnName;
    private final int index;
    private final DataType type;

    public ColumnRef(String tableName, String columnName, int index, DataType type) {
        this.tableName = tableName;
        this.columnName = columnName;
        this.index = index;
        this.type = type;
    }

    public String tableName() { return tableName; }
    public String columnName() { return columnName; }
    public int index() { return index; }

    @Override public Datum eval(EvalContext ctx, Row row) { return row.get(index); }
    @Override public DataType returnType() { return type; }
    @Override public String toSQL() {
        return tableName != null ? tableName + "." + columnName : columnName;
    }
    @Override public String toString() { return toSQL(); }
}
