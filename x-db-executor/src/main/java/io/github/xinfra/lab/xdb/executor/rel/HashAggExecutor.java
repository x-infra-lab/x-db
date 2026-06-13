package io.github.xinfra.lab.xdb.executor.rel;

import io.github.xinfra.lab.xdb.executor.Executor;
import io.github.xinfra.lab.xdb.expression.AggFunction;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hash-based aggregation executor.
 * Reads all child rows, groups by key, and computes aggregate results.
 */
public class HashAggExecutor implements Executor {

    private final Executor child;
    private final List<Expression> groupByExprs;
    private final List<AggFunction> aggFunctions;
    private final List<ColumnInfo> outputColumns;
    private final EvalContext evalCtx;

    // group key -> (group key values, aggregate function instances)
    private Map<String, GroupEntry> groups;
    private Iterator<GroupEntry> resultIterator;

    public HashAggExecutor(Executor child, List<Expression> groupByExprs,
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

        groups = new LinkedHashMap<>();

        // Read all rows from child
        Row row;
        while ((row = child.next()) != null) {
            // Compute group key
            String groupKey = computeGroupKey(row);
            Datum[] groupKeyValues = evaluateGroupByExprs(row);

            GroupEntry entry = groups.get(groupKey);
            if (entry == null) {
                // Create new aggregate function instances for this group
                List<AggFunction> groupAggs = new ArrayList<>(aggFunctions.size());
                for (AggFunction aggFunc : aggFunctions) {
                    groupAggs.add(aggFunc.newInstance());
                }
                entry = new GroupEntry(groupKeyValues, groupAggs);
                groups.put(groupKey, entry);
            }

            // Update aggregates
            for (int i = 0; i < entry.aggFunctions.size(); i++) {
                AggFunction agg = entry.aggFunctions.get(i);
                Expression arg = agg.arg();
                Datum value;
                if (arg != null) {
                    value = arg.eval(evalCtx, row);
                } else {
                    // COUNT(*) case: arg is null, treat as non-null
                    value = Datum.of(1L);
                }
                agg.update(value);
            }
        }

        // If no groups and no group-by (scalar aggregation), produce one group
        if (groups.isEmpty() && groupByExprs.isEmpty()) {
            List<AggFunction> emptyAggs = new ArrayList<>(aggFunctions.size());
            for (AggFunction aggFunc : aggFunctions) {
                emptyAggs.add(aggFunc.newInstance());
            }
            groups.put("", new GroupEntry(new Datum[0], emptyAggs));
        }

        resultIterator = groups.values().iterator();
    }

    @Override
    public Row next() throws Exception {
        if (!resultIterator.hasNext()) {
            return null;
        }

        GroupEntry entry = resultIterator.next();

        // Build output row: group-by columns + aggregate results
        int groupCount = groupByExprs.size();
        int aggCount = entry.aggFunctions.size();
        Datum[] values = new Datum[groupCount + aggCount];

        for (int i = 0; i < groupCount; i++) {
            values[i] = entry.groupKeyValues[i];
        }
        for (int i = 0; i < aggCount; i++) {
            values[groupCount + i] = entry.aggFunctions.get(i).result();
        }

        return new Row(values);
    }

    @Override
    public void close() throws Exception {
        child.close();
        groups = null;
    }

    @Override
    public List<ColumnInfo> outputSchema() {
        return outputColumns;
    }

    private String computeGroupKey(Row row) {
        if (groupByExprs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < groupByExprs.size(); i++) {
            if (i > 0) sb.append('\0');
            Datum value = groupByExprs.get(i).eval(evalCtx, row);
            sb.append(value.toStringValue());
        }
        return sb.toString();
    }

    private Datum[] evaluateGroupByExprs(Row row) {
        Datum[] values = new Datum[groupByExprs.size()];
        for (int i = 0; i < groupByExprs.size(); i++) {
            values[i] = groupByExprs.get(i).eval(evalCtx, row);
        }
        return values;
    }

    private static class GroupEntry {
        final Datum[] groupKeyValues;
        final List<AggFunction> aggFunctions;

        GroupEntry(Datum[] groupKeyValues, List<AggFunction> aggFunctions) {
            this.groupKeyValues = groupKeyValues;
            this.aggFunctions = aggFunctions;
        }
    }
}
