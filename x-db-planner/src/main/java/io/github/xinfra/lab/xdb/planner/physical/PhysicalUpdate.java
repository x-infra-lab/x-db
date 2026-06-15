package io.github.xinfra.lab.xdb.planner.physical;

import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.meta.TableInfo;

import java.util.Collections;
import java.util.List;

public class PhysicalUpdate implements PhysicalPlan {
    private final PhysicalPlan child;
    private final TableInfo table;
    private final List<ColumnInfo> updateColumns;
    private final List<Expression> updateValues;

    public PhysicalUpdate(PhysicalPlan child, TableInfo table,
                          List<ColumnInfo> updateColumns, List<Expression> updateValues) {
        this.child = child;
        this.table = table;
        this.updateColumns = updateColumns;
        this.updateValues = updateValues;
    }

    public PhysicalPlan child() { return child; }
    public TableInfo table() { return table; }
    public List<ColumnInfo> updateColumns() { return updateColumns; }
    public List<Expression> updateValues() { return updateValues; }

    @Override
    public List<PhysicalPlan> children() { return Collections.singletonList(child); }

    @Override
    public List<ColumnInfo> outputSchema() { return Collections.emptyList(); }

    @Override
    public double estimatedCost() { return child.estimatedCost() + child.estimatedRowCount() * 10.0; }

    @Override
    public long estimatedRowCount() { return child.estimatedRowCount(); }

    @Override
    public String explain(int indent) {
        return indentStr(indent) + "PhysicalUpdate(" + table.getName() + costInfo() + ")\n" +
                child.explain(indent + 1);
    }
}
