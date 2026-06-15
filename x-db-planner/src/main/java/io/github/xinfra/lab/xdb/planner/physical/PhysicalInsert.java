package io.github.xinfra.lab.xdb.planner.physical;

import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.meta.TableInfo;

import java.util.Collections;
import java.util.List;

public class PhysicalInsert implements PhysicalPlan {
    private final TableInfo table;
    private final List<ColumnInfo> targetColumns;
    private final List<List<Expression>> rows;

    public PhysicalInsert(TableInfo table, List<ColumnInfo> targetColumns,
                          List<List<Expression>> rows) {
        this.table = table;
        this.targetColumns = targetColumns;
        this.rows = rows;
    }

    public TableInfo table() { return table; }
    public List<ColumnInfo> targetColumns() { return targetColumns; }
    public List<List<Expression>> rows() { return rows; }

    @Override
    public List<PhysicalPlan> children() { return Collections.emptyList(); }

    @Override
    public List<ColumnInfo> outputSchema() { return Collections.emptyList(); }

    @Override
    public double estimatedCost() { return rows.size() * 10.0; }

    @Override
    public long estimatedRowCount() { return rows.size(); }

    @Override
    public String explain(int indent) {
        return indentStr(indent) + "PhysicalInsert(" + table.getName() + costInfo() + ")";
    }
}
