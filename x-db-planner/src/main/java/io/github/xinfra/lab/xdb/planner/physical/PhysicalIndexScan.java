package io.github.xinfra.lab.xdb.planner.physical;

import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.meta.IndexInfo;
import io.github.xinfra.lab.xdb.meta.TableInfo;

import java.util.Collections;
import java.util.List;

public class PhysicalIndexScan implements PhysicalPlan {
    private final TableInfo table;
    private final IndexInfo index;
    private final String alias;
    private final List<ColumnInfo> outputColumns;
    private final List<Expression> accessConditions;
    private long estimatedRows;
    private boolean keepOrder;

    public PhysicalIndexScan(TableInfo table, IndexInfo index, String alias,
                             List<ColumnInfo> outputColumns, List<Expression> accessConditions) {
        this.table = table;
        this.index = index;
        this.alias = alias;
        this.outputColumns = outputColumns;
        this.accessConditions = accessConditions;
        this.estimatedRows = 100;
    }

    public TableInfo table() { return table; }
    public IndexInfo index() { return index; }
    public String alias() { return alias; }
    public List<Expression> accessConditions() { return accessConditions; }
    public boolean keepOrder() { return keepOrder; }
    public void setKeepOrder(boolean keepOrder) { this.keepOrder = keepOrder; }
    public void setEstimatedRows(long rows) { this.estimatedRows = rows; }

    @Override
    public List<PhysicalPlan> children() { return Collections.emptyList(); }

    @Override
    public List<ColumnInfo> outputSchema() { return outputColumns; }

    @Override
    public double estimatedCost() { return estimatedRows * 1.5; }

    @Override
    public long estimatedRowCount() { return estimatedRows; }

    @Override
    public String explain(int indent) {
        return indentStr(indent) + "PhysicalIndexScan(" + table.getName() +
                ", idx=" + index.getName() + costInfo() + ")";
    }
}
