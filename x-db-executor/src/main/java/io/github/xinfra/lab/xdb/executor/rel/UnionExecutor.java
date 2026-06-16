package io.github.xinfra.lab.xdb.executor.rel;

import io.github.xinfra.lab.xdb.executor.Executor;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class UnionExecutor implements Executor {
    private final List<Executor> children;
    private final boolean all;
    private final List<ColumnInfo> outputColumns;
    private int currentIndex;
    private Set<Row> seen;

    public UnionExecutor(List<Executor> children, boolean all, List<ColumnInfo> outputColumns) {
        this.children = children;
        this.all = all;
        this.outputColumns = outputColumns;
    }

    @Override
    public void open() throws Exception {
        currentIndex = 0;
        if (!all) {
            seen = new HashSet<>();
        }
        if (!children.isEmpty()) {
            children.get(0).open();
        }
    }

    @Override
    public Row next() throws Exception {
        while (currentIndex < children.size()) {
            Row row = children.get(currentIndex).next();
            if (row != null) {
                if (all || seen.add(row)) {
                    return row;
                }
                continue;
            }
            children.get(currentIndex).close();
            currentIndex++;
            if (currentIndex < children.size()) {
                children.get(currentIndex).open();
            }
        }
        return null;
    }

    @Override
    public void close() throws Exception {
        if (currentIndex < children.size()) {
            children.get(currentIndex).close();
        }
        seen = null;
    }

    @Override
    public List<ColumnInfo> outputSchema() {
        return outputColumns;
    }
}
