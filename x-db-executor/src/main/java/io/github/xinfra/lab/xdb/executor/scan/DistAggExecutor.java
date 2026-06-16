package io.github.xinfra.lab.xdb.executor.scan;

import io.github.xinfra.lab.xdb.common.XDBException;
import io.github.xinfra.lab.xdb.executor.Executor;
import io.github.xinfra.lab.xdb.executor.TransactionContext;
import io.github.xinfra.lab.xdb.expression.AggFunction;
import io.github.xinfra.lab.xdb.expression.AggFunctions;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.DatumComparator;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.meta.TableInfo;
import io.github.xinfra.lab.xdb.table.CopRequestCodec;
import io.github.xinfra.lab.xdb.table.CopRequestCodec.AggFuncDesc;
import io.github.xinfra.lab.xdb.table.CopRequestCodec.AggGroupResult;
import io.github.xinfra.lab.xdb.table.CopRequestCodec.ColumnDesc;
import io.github.xinfra.lab.xdb.table.CopRequestCodec.CopRequest;
import io.github.xinfra.lab.xdb.table.CopRequestCodec.CopResponse;
import io.github.xinfra.lab.xdb.table.CopRequestCodec.PartialAggState;
import io.github.xinfra.lab.xdb.table.TableCodec;
import io.github.xinfra.lab.xdb.table.TipbCodec;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Two-phase distributed aggregation executor.
 *
 * <p>Pushes selection conditions and aggregation descriptors to the
 * KV coprocessor (tp=1). Each region returns partial aggregation
 * results which are merged here using {@link AggFunction#merge}.
 */
public class DistAggExecutor implements Executor {

    private static final int DEFAULT_CONCURRENCY = 4;

    private final TransactionContext txnCtx;
    private final TableInfo table;
    private final List<ColumnInfo> outputColumns;
    private final List<Expression> conditions;
    private final List<Expression> groupByExprs;
    private final List<AggFunction> aggFunctions;
    private final int concurrency;

    private Iterator<Row> resultIterator;

    public DistAggExecutor(TransactionContext txnCtx, TableInfo table,
                           List<ColumnInfo> outputColumns,
                           List<Expression> conditions,
                           List<Expression> groupByExprs,
                           List<AggFunction> aggFunctions,
                           int concurrency) {
        this.txnCtx = txnCtx;
        this.table = table;
        this.outputColumns = outputColumns;
        this.conditions = conditions;
        this.groupByExprs = groupByExprs;
        this.aggFunctions = aggFunctions;
        this.concurrency = concurrency > 0 ? concurrency : DEFAULT_CONCURRENCY;
    }

    @Override
    public void open() throws Exception {
        byte[] startKey = TableCodec.tableRecordPrefix(table.getId());
        byte[] endKey = TableCodec.encodeRowKeyMax(table.getId());

        List<AggFuncDesc> aggDescs = new ArrayList<>(aggFunctions.size());
        for (AggFunction agg : aggFunctions) {
            aggDescs.add(new AggFuncDesc(agg.type(), agg.distinct(), agg.arg()));
        }

        List<ColumnDesc> colDescs = buildColumnDescs();
        List<Integer> outputIndices = buildOutputIndices();

        CopRequest copReq = new CopRequest(
                table.getId(), colDescs, outputIndices,
                conditions, groupByExprs, aggDescs,
                -1, 0, List.of(), List.of());
        byte[] data = TipbCodec.encodeDAGRequest(copReq);

        var results = txnCtx.copProcessor().scanParallel(
                1, data, 0, startKey, endKey, concurrency);

        Map<GroupKey, AggFunction[]> merged = mergePartialResults(results);
        resultIterator = buildFinalRows(merged).iterator();
    }

    @Override
    public Row next() throws Exception {
        return resultIterator.hasNext() ? resultIterator.next() : null;
    }

    @Override
    public void close() throws Exception {
        resultIterator = null;
    }

    @Override
    public List<ColumnInfo> outputSchema() {
        return outputColumns;
    }

    private Map<GroupKey, AggFunction[]> mergePartialResults(
            Iterator<TransactionContext.KVCopProcessor.CopRegionResult> results) {
        Map<GroupKey, AggFunction[]> merged = new HashMap<>();

        while (results.hasNext()) {
            var result = results.next();
            if (result.error() != null && !result.error().isEmpty()) {
                throw XDBException.internal("Coprocessor error: " + result.error());
            }

            CopResponse resp = TipbCodec.decodeResponse(result.data());
            if (!(resp instanceof CopResponse.AggResult aggResult)) continue;

            for (AggGroupResult group : aggResult.groups()) {
                GroupKey gk = new GroupKey(group.groupKeys());
                AggFunction[] existing = merged.computeIfAbsent(gk,
                        k -> createNewAggFunctions());

                for (int i = 0; i < group.partialStates().size(); i++) {
                    PartialAggState state = group.partialStates().get(i);
                    AggFunction partial = restoreFromState(state);
                    existing[i].merge(partial);
                }
            }
        }

        if (merged.isEmpty() && (groupByExprs == null || groupByExprs.isEmpty())) {
            merged.put(GroupKey.EMPTY, createNewAggFunctions());
        }

        return merged;
    }

    private AggFunction[] createNewAggFunctions() {
        AggFunction[] result = new AggFunction[aggFunctions.size()];
        for (int i = 0; i < aggFunctions.size(); i++) {
            result[i] = aggFunctions.get(i).newInstance();
        }
        return result;
    }

    private AggFunction restoreFromState(PartialAggState state) {
        AggFunction agg = AggFunctions.create(state.type(), null, state.distinct());
        List<Datum> s = state.state();

        switch (state.type()) {
            case COUNT -> {
                agg.restorePartialState(s.get(0).toLong(), null);
            }
            case SUM -> {
                Datum val = s.get(0);
                BigDecimal sumVal = val.isNull() ? null : toBigDecimal(val);
                agg.restorePartialState(0, sumVal);
            }
            case AVG -> {
                long count = s.get(0).toLong();
                Datum sum = s.get(1);
                BigDecimal sumVal = sum.isNull() ? null : toBigDecimal(sum);
                agg.restorePartialState(count, sumVal);
            }
            case MIN, MAX -> {
                Datum val = s.get(0);
                if (!val.isNull()) agg.update(val);
            }
            case GROUP_CONCAT -> {
                Datum val = s.get(0);
                if (!val.isNull()) agg.update(val);
            }
        }
        return agg;
    }

    private static BigDecimal toBigDecimal(Datum d) {
        if (d instanceof Datum.DecimalDatum dd) return dd.value();
        if (d instanceof Datum.IntDatum id) return BigDecimal.valueOf(id.value());
        return BigDecimal.valueOf(d.toDouble());
    }

    private List<Row> buildFinalRows(Map<GroupKey, AggFunction[]> merged) {
        List<Row> rows = new ArrayList<>(merged.size());
        for (var entry : merged.entrySet()) {
            GroupKey gk = entry.getKey();
            AggFunction[] aggs = entry.getValue();

            Datum[] values = new Datum[outputColumns.size()];
            int idx = 0;
            for (int i = 0; i < gk.keys.size() && idx < values.length; i++) {
                values[idx++] = gk.keys.get(i);
            }
            for (int i = 0; i < aggs.length && idx < values.length; i++) {
                values[idx++] = aggs[i].result();
            }
            while (idx < values.length) {
                values[idx++] = Datum.nil();
            }
            rows.add(new Row(values));
        }
        return rows;
    }

    private boolean isHandleColumn(ColumnInfo col) {
        if (col.isAutoIncrement()) return true;
        var pk = table.getPrimaryIndex();
        if (pk != null && pk.getColumns().size() == 1) {
            return pk.getColumns().get(0).getColumnId() == col.getId();
        }
        return false;
    }

    private List<ColumnDesc> buildColumnDescs() {
        List<ColumnDesc> descs = new ArrayList<>(table.getColumns().size());
        for (ColumnInfo col : table.getColumns()) {
            descs.add(new ColumnDesc(col.getId(), col.getType(), isHandleColumn(col), col.getOffset()));
        }
        return descs;
    }

    private List<Integer> buildOutputIndices() {
        List<Integer> indices = new ArrayList<>(table.getColumns().size());
        for (int i = 0; i < table.getColumns().size(); i++) {
            indices.add(i);
        }
        return indices;
    }

    static final class GroupKey {
        static final GroupKey EMPTY = new GroupKey(List.of());

        final List<Datum> keys;

        GroupKey(List<Datum> keys) {
            this.keys = keys;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof GroupKey other)) return false;
            if (keys.size() != other.keys.size()) return false;
            for (int i = 0; i < keys.size(); i++) {
                Datum a = keys.get(i);
                Datum b = other.keys.get(i);
                if (a.isNull() && b.isNull()) continue;
                if (a.isNull() || b.isNull()) return false;
                if (DatumComparator.compare(a, b) != 0) return false;
            }
            return true;
        }

        @Override
        public int hashCode() {
            int h = 1;
            for (Datum d : keys) {
                h = 31 * h + (d.isNull() ? 0 : d.toStringValue().hashCode());
            }
            return h;
        }
    }
}
