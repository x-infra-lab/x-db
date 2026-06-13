package io.github.xinfra.lab.xdb.planner.logical;

import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.meta.IndexInfo;
import io.github.xinfra.lab.xdb.meta.TableInfo;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class LogicalIndexScan implements LogicalPlan {
    private final TableInfo table;
    private final IndexInfo index;
    private final String alias;
    private List<ColumnInfo> outputColumns;
    private List<Expression> accessConditions;
    private boolean needTableLookup;

    public LogicalIndexScan(TableInfo table, IndexInfo index, String alias) {
        this.table = table;
        this.index = index;
        this.alias = alias;
        this.outputColumns = new ArrayList<>(table.getPublicColumns());
        this.accessConditions = new ArrayList<>();
        this.needTableLookup = true;
    }

    public TableInfo table() { return table; }
    public IndexInfo index() { return index; }
    public String alias() { return alias; }
    public boolean needTableLookup() { return needTableLookup; }
    public void setNeedTableLookup(boolean need) { this.needTableLookup = need; }
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
        sb.append(indentStr(indent)).append("IndexScan(")
                .append(table.getName()).append(", index=").append(index.getName());
        if (alias != null) sb.append(" AS ").append(alias);
        sb.append(")");
        if (needTableLookup) sb.append(" [lookup]");
        return sb.toString();
    }
}
