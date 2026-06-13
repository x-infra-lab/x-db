package io.github.xinfra.lab.xdb.planner.logical;

import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.meta.TableInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LogicalTableScan implements LogicalPlan {
    private final TableInfo table;
    private final String alias;
    private List<ColumnInfo> outputColumns;
    private List<Expression> accessConditions;

    public LogicalTableScan(TableInfo table, String alias) {
        this.table = table;
        this.alias = alias;
        this.outputColumns = new ArrayList<>(table.getPublicColumns());
        this.accessConditions = new ArrayList<>();
    }

    public TableInfo table() { return table; }
    public String alias() { return alias; }
    public String tableName() { return alias != null ? alias : table.getName(); }
    public List<Expression> accessConditions() { return accessConditions; }
    public void setAccessConditions(List<Expression> conditions) { this.accessConditions = conditions; }
    public void setOutputColumns(List<ColumnInfo> columns) { this.outputColumns = columns; }

    @Override
    public List<LogicalPlan> children() { return Collections.emptyList(); }

    @Override
    public List<ColumnInfo> outputSchema() { return outputColumns; }

    @Override
    public String explain(int indent) {
        StringBuilder sb = new StringBuilder();
        sb.append(indentStr(indent)).append("TableScan(").append(table.getName());
        if (alias != null) sb.append(" AS ").append(alias);
        sb.append(")");
        if (!accessConditions.isEmpty()) {
            sb.append(" conditions=").append(accessConditions);
        }
        return sb.toString();
    }
}
