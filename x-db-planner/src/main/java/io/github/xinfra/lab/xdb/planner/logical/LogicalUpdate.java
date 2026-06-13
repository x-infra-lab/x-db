package io.github.xinfra.lab.xdb.planner.logical;

import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.meta.TableInfo;

import java.util.Collections;
import java.util.List;

public class LogicalUpdate implements LogicalPlan {
    private LogicalPlan child;
    private final TableInfo table;
    private final List<ColumnInfo> updateColumns;
    private final List<Expression> updateValues;

    public LogicalUpdate(LogicalPlan child, TableInfo table,
                         List<ColumnInfo> updateColumns, List<Expression> updateValues) {
        this.child = child;
        this.table = table;
        this.updateColumns = updateColumns;
        this.updateValues = updateValues;
    }

    public LogicalPlan child() { return child; }
    public void setChild(LogicalPlan child) { this.child = child; }
    public TableInfo table() { return table; }
    public List<ColumnInfo> updateColumns() { return updateColumns; }
    public List<Expression> updateValues() { return updateValues; }

    @Override
    public List<LogicalPlan> children() { return Collections.singletonList(child); }

    @Override
    public List<ColumnInfo> outputSchema() { return Collections.emptyList(); }

    @Override
    public String explain(int indent) {
        return indentStr(indent) + "Update(" + table.getName() + ")\n" + child.explain(indent + 1);
    }
}
