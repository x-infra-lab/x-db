package io.github.xinfra.lab.xdb.planner.logical;

import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LogicalShowStmt implements LogicalPlan {
    public enum ShowType {
        DATABASES, TABLES, COLUMNS, CREATE_TABLE, VARIABLES, WARNINGS, STATUS
    }

    private final ShowType showType;
    private final String databaseName;
    private final String tableName;

    public LogicalShowStmt(ShowType showType, String databaseName, String tableName) {
        this.showType = showType;
        this.databaseName = databaseName;
        this.tableName = tableName;
    }

    public ShowType showType() { return showType; }
    public String databaseName() { return databaseName; }
    public String tableName() { return tableName; }

    @Override
    public List<LogicalPlan> children() { return Collections.emptyList(); }

    @Override
    public List<ColumnInfo> outputSchema() {
        List<ColumnInfo> cols = new ArrayList<>();
        switch (showType) {
            case DATABASES -> {
                cols.add(makeCol("Database", DataType.VARCHAR));
            }
            case TABLES -> {
                cols.add(makeCol("Tables_in_" + (databaseName != null ? databaseName : ""), DataType.VARCHAR));
            }
            case COLUMNS -> {
                cols.add(makeCol("Field", DataType.VARCHAR));
                cols.add(makeCol("Type", DataType.VARCHAR));
                cols.add(makeCol("Null", DataType.VARCHAR));
                cols.add(makeCol("Key", DataType.VARCHAR));
                cols.add(makeCol("Default", DataType.VARCHAR));
                cols.add(makeCol("Extra", DataType.VARCHAR));
            }
            case CREATE_TABLE -> {
                cols.add(makeCol("Table", DataType.VARCHAR));
                cols.add(makeCol("Create Table", DataType.VARCHAR));
            }
            case VARIABLES -> {
                cols.add(makeCol("Variable_name", DataType.VARCHAR));
                cols.add(makeCol("Value", DataType.VARCHAR));
            }
            case WARNINGS -> {
                cols.add(makeCol("Level", DataType.VARCHAR));
                cols.add(makeCol("Code", DataType.INT));
                cols.add(makeCol("Message", DataType.VARCHAR));
            }
            case STATUS -> {
                cols.add(makeCol("Variable_name", DataType.VARCHAR));
                cols.add(makeCol("Value", DataType.VARCHAR));
            }
        }
        return cols;
    }

    private ColumnInfo makeCol(String name, DataType type) {
        ColumnInfo col = new ColumnInfo();
        col.setName(name);
        col.setType(type);
        return col;
    }

    @Override
    public String explain(int indent) {
        String s = indentStr(indent) + "Show(" + showType;
        if (tableName != null) s += ", table=" + tableName;
        return s + ")";
    }
}
