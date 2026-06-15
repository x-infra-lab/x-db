package io.github.xinfra.lab.xdb.planner.physical;

import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.planner.logical.LogicalShowStmt;

import java.util.Collections;
import java.util.List;

public class PhysicalShowStmt implements PhysicalPlan {
    private final LogicalShowStmt.ShowType showType;
    private final String databaseName;
    private final String tableName;
    private final List<ColumnInfo> outputColumns;

    public PhysicalShowStmt(LogicalShowStmt.ShowType showType, String databaseName,
                            String tableName, List<ColumnInfo> outputColumns) {
        this.showType = showType;
        this.databaseName = databaseName;
        this.tableName = tableName;
        this.outputColumns = outputColumns;
    }

    public LogicalShowStmt.ShowType showType() { return showType; }
    public String databaseName() { return databaseName; }
    public String tableName() { return tableName; }

    @Override
    public List<PhysicalPlan> children() { return Collections.emptyList(); }

    @Override
    public List<ColumnInfo> outputSchema() { return outputColumns; }

    @Override
    public double estimatedCost() { return 1; }

    @Override
    public long estimatedRowCount() { return 10; }

    @Override
    public String explain(int indent) {
        String s = indentStr(indent) + "PhysicalShow(" + showType;
        if (tableName != null) s += ", table=" + tableName;
        return s + costInfo() + ")";
    }
}
