package io.github.xinfra.lab.xdb.planner.logical;

import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.meta.TableInfo;

import java.util.Collections;
import java.util.List;

public class LogicalDelete implements LogicalPlan {
    private LogicalPlan child;
    private final TableInfo table;

    public LogicalDelete(LogicalPlan child, TableInfo table) {
        this.child = child;
        this.table = table;
    }

    public LogicalPlan child() { return child; }
    public void setChild(LogicalPlan child) { this.child = child; }
    public TableInfo table() { return table; }

    @Override
    public List<LogicalPlan> children() { return Collections.singletonList(child); }

    @Override
    public List<ColumnInfo> outputSchema() { return Collections.emptyList(); }

    @Override
    public String explain(int indent) {
        return indentStr(indent) + "Delete(" + table.getName() + ")\n" + child.explain(indent + 1);
    }
}
