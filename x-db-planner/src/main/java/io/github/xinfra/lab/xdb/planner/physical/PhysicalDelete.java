package io.github.xinfra.lab.xdb.planner.physical;

import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.meta.TableInfo;

import java.util.Collections;
import java.util.List;

public class PhysicalDelete implements PhysicalPlan {
    private final PhysicalPlan child;
    private final TableInfo table;

    public PhysicalDelete(PhysicalPlan child, TableInfo table) {
        this.child = child;
        this.table = table;
    }

    public PhysicalPlan child() { return child; }
    public TableInfo table() { return table; }

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
        return indentStr(indent) + "PhysicalDelete(" + table.getName() + ")\n" +
                child.explain(indent + 1);
    }
}
