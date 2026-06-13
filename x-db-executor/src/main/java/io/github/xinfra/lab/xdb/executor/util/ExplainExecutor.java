package io.github.xinfra.lab.xdb.executor.util;

import io.github.xinfra.lab.xdb.executor.Executor;
import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.planner.physical.PhysicalPlan;

import java.util.Collections;
import java.util.List;

/**
 * Returns the plan tree as a string result set.
 * Each line of the explain output becomes a row.
 */
public class ExplainExecutor implements Executor {

    private final PhysicalPlan plan;
    private final List<ColumnInfo> outputColumns;

    private String[] lines;
    private int currentIndex;

    public ExplainExecutor(PhysicalPlan plan) {
        this.plan = plan;

        ColumnInfo col = new ColumnInfo();
        col.setName("Plan");
        col.setType(DataType.VARCHAR);
        this.outputColumns = Collections.singletonList(col);
    }

    @Override
    public void open() throws Exception {
        String explainText = plan.explain(0);
        lines = explainText.split("\n");
        currentIndex = 0;
    }

    @Override
    public Row next() throws Exception {
        if (currentIndex >= lines.length) {
            return null;
        }
        String line = lines[currentIndex++];
        return new Row(new Datum[]{Datum.of(line)});
    }

    @Override
    public void close() throws Exception {
        lines = null;
    }

    @Override
    public List<ColumnInfo> outputSchema() {
        return outputColumns;
    }
}
