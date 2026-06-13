package io.github.xinfra.lab.xdb.executor;

import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Test helper: wraps a list of Rows into an Executor for unit testing.
 */
public class ListExecutor implements Executor {

    private final List<Row> rows;
    private final List<ColumnInfo> schema;
    private int index;

    public ListExecutor(List<Row> rows, List<ColumnInfo> schema) {
        this.rows = new ArrayList<>(rows);
        this.schema = schema;
    }

    @Override
    public void open() throws Exception {
        index = 0;
    }

    @Override
    public Row next() throws Exception {
        if (index >= rows.size()) {
            return null;
        }
        return rows.get(index++);
    }

    @Override
    public void close() throws Exception {
        // no-op
    }

    @Override
    public List<ColumnInfo> outputSchema() {
        return schema;
    }

    /**
     * Create a simple ColumnInfo with the given name and type.
     */
    public static ColumnInfo col(String name, DataType type) {
        ColumnInfo ci = new ColumnInfo();
        ci.setName(name);
        ci.setType(type);
        return ci;
    }
}
