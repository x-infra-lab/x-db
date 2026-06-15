package io.github.xinfra.lab.xdb.planner.physical;

import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.meta.IndexInfo;
import io.github.xinfra.lab.xdb.meta.TableInfo;

import java.util.Collections;
import java.util.List;

public class PhysicalIndexLookup implements PhysicalPlan {
    private final PhysicalIndexScan indexScan;
    private final TableInfo table;
    private final List<ColumnInfo> outputColumns;

    public PhysicalIndexLookup(PhysicalIndexScan indexScan, TableInfo table,
                               List<ColumnInfo> outputColumns) {
        this.indexScan = indexScan;
        this.table = table;
        this.outputColumns = outputColumns;
    }

    public PhysicalIndexScan indexScan() { return indexScan; }
    public TableInfo table() { return table; }

    @Override
    public List<PhysicalPlan> children() { return Collections.singletonList(indexScan); }

    @Override
    public List<ColumnInfo> outputSchema() { return outputColumns; }

    @Override
    public double estimatedCost() { return indexScan.estimatedCost() + indexScan.estimatedRowCount() * 5.0; }

    @Override
    public long estimatedRowCount() { return indexScan.estimatedRowCount(); }

    @Override
    public String explain(int indent) {
        return indentStr(indent) + "PhysicalIndexLookup(" + table.getName() + costInfo() + ")\n" +
                indexScan.explain(indent + 1);
    }
}
