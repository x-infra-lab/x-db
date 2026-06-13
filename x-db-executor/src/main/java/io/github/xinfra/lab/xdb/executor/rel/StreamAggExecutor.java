package io.github.xinfra.lab.xdb.executor.rel;

import io.github.xinfra.lab.xdb.executor.Executor;
import io.github.xinfra.lab.xdb.expression.AggFunction;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Streaming aggregation on pre-sorted input.
 * Reads rows, accumulates aggregates while group key is the same.
 * When the group key changes, outputs the previous group's result.
 */
public class StreamAggExecutor implements Executor {

    private final Executor child;
    private final List<Expression> groupByExprs;
    private final List<AggFunction> aggFunctions;
    private final List<ColumnInfo> outputColumns;
    private final EvalContext evalCtx;

    private Row pendingRow;   // buffered row from the next group
    private boolean childExhausted;
    private boolean started;
    private boolean finished;

    public StreamAggExecutor(Executor child, List<Expression> groupByExprs,
                             List<AggFunction> aggFunctions,
                             List<ColumnInfo> outputColumns, EvalContext evalCtx) {
        this.child = child;
        this.groupByExprs = groupByExprs;
        this.aggFunctions = aggFunctions;
        this.outputColumns = outputColumns;
        this.evalCtx = evalCtx;
    }

    @Override
    public void open() throws Exception {
        child.open();
        pendingRow = null;
        childExhausted = false;
        started = false;
        finished = false;
    }

    @Override
    public Row next() throws Exception {
        if (finished) {
            return null;
        }

        // Create new aggregate function instances for this group
        List<AggFunction> currentAggs = new ArrayList<>(aggFunctions.size());
        for (AggFunction aggFunc : aggFunctions) {
            currentAggs.add(aggFunc.newInstance());
        }

        Datum[] currentGroupKey = null;
        boolean hasData = false;

        // Start with pending row from previous call, if any
        if (pendingRow != null) {
            currentGroupKey = evaluateGroupByExprs(pendingRow);
            updateAggregates(currentAggs, pendingRow);
            pendingRow = null;
            hasData = true;
        }

        // Read rows until group key changes or child is exhausted
        while (!childExhausted) {
            Row row = child.next();
            if (row == null) {
                childExhausted = true;
                break;
            }

            Datum[] rowGroupKey = evaluateGroupByExprs(row);

            if (!hasData) {
                // First row
                currentGroupKey = rowGroupKey;
                updateAggregates(currentAggs, row);
                hasData = true;
            } else if (groupKeysEqual(currentGroupKey, rowGroupKey)) {
                // Same group
                updateAggregates(currentAggs, row);
            } else {
                // Group changed, buffer this row for next call
                pendingRow = row;
                break;
            }
        }

        if (!hasData) {
            // No data at all
            if (!started && groupByExprs.isEmpty()) {
                // Scalar aggregation with no input rows: return default aggregates
                started = true;
                finished = true;
                return buildResultRow(new Datum[0], currentAggs);
            }
            finished = true;
            return null;
        }

        started = true;
        if (childExhausted && pendingRow == null) {
            finished = true;
        }

        return buildResultRow(currentGroupKey, currentAggs);
    }

    @Override
    public void close() throws Exception {
        child.close();
    }

    @Override
    public List<ColumnInfo> outputSchema() {
        return outputColumns;
    }

    private Datum[] evaluateGroupByExprs(Row row) {
        Datum[] values = new Datum[groupByExprs.size()];
        for (int i = 0; i < groupByExprs.size(); i++) {
            values[i] = groupByExprs.get(i).eval(evalCtx, row);
        }
        return values;
    }

    private void updateAggregates(List<AggFunction> aggs, Row row) {
        for (int i = 0; i < aggs.size(); i++) {
            AggFunction agg = aggs.get(i);
            Expression arg = agg.arg();
            Datum value;
            if (arg != null) {
                value = arg.eval(evalCtx, row);
            } else {
                value = Datum.of(1L);
            }
            agg.update(value);
        }
    }

    private boolean groupKeysEqual(Datum[] key1, Datum[] key2) {
        if (key1.length != key2.length) return false;
        for (int i = 0; i < key1.length; i++) {
            if (key1[i].isNull() && key2[i].isNull()) continue;
            if (key1[i].isNull() || key2[i].isNull()) return false;
            if (!key1[i].toStringValue().equals(key2[i].toStringValue())) {
                return false;
            }
        }
        return true;
    }

    private Row buildResultRow(Datum[] groupKeyValues, List<AggFunction> aggs) {
        int groupCount = groupByExprs.size();
        int aggCount = aggs.size();
        Datum[] values = new Datum[groupCount + aggCount];

        for (int i = 0; i < groupCount; i++) {
            values[i] = groupKeyValues[i];
        }
        for (int i = 0; i < aggCount; i++) {
            values[groupCount + i] = aggs.get(i).result();
        }

        return new Row(values);
    }
}
