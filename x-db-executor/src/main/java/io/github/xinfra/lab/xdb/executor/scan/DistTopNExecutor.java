package io.github.xinfra.lab.xdb.executor.scan;

import io.github.xinfra.lab.xdb.common.XDBException;
import io.github.xinfra.lab.xdb.executor.Executor;
import io.github.xinfra.lab.xdb.executor.TransactionContext;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.DatumComparator;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.meta.TableInfo;
import io.github.xinfra.lab.xdb.table.CopRequestCodec;
import io.github.xinfra.lab.xdb.table.CopRequestCodec.ColumnDesc;
import io.github.xinfra.lab.xdb.table.CopRequestCodec.CopRequest;
import io.github.xinfra.lab.xdb.table.CopRequestCodec.CopResponse;
import io.github.xinfra.lab.xdb.table.KvPairDecoder;
import io.github.xinfra.lab.xdb.table.RowCodec;
import io.github.xinfra.lab.xdb.table.TableCodec;
import io.github.xinfra.lab.xdb.table.TipbCodec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Distributed TopN executor with ORDER BY + LIMIT push-down.
 *
 * <p>Each region applies the TopN locally (heap sort), returning at most
 * limit+offset rows. This executor merges results from all regions,
 * performs a global sort, skips offset, and returns limit rows.
 */
public class DistTopNExecutor implements Executor {

    private static final int DEFAULT_CONCURRENCY = 4;

    private final TransactionContext txnCtx;
    private final TableInfo table;
    private final List<ColumnInfo> outputColumns;
    private final List<Expression> conditions;
    private final List<Expression> orderByExprs;
    private final List<Boolean> ascending;
    private final long limitCount;
    private final long limitOffset;
    private final int concurrency;

    private Iterator<Row> resultIterator;

    public DistTopNExecutor(TransactionContext txnCtx, TableInfo table,
                            List<ColumnInfo> outputColumns,
                            List<Expression> conditions,
                            List<Expression> orderByExprs,
                            List<Boolean> ascending,
                            long limitCount, long limitOffset,
                            int concurrency) {
        this.txnCtx = txnCtx;
        this.table = table;
        this.outputColumns = outputColumns;
        this.conditions = conditions;
        this.orderByExprs = orderByExprs;
        this.ascending = ascending;
        this.limitCount = limitCount;
        this.limitOffset = limitOffset;
        this.concurrency = concurrency > 0 ? concurrency : DEFAULT_CONCURRENCY;
    }

    @Override
    public void open() throws Exception {
        byte[] startKey = TableCodec.tableRecordPrefix(table.getId());
        byte[] endKey = TableCodec.encodeRowKeyMax(table.getId());

        CopRequest copReq = new CopRequest(
                table.getId(), buildColumnDescs(), buildOutputIndices(),
                conditions, List.of(), List.of(),
                limitCount + limitOffset, 0,
                orderByExprs, ascending);
        byte[] data = TipbCodec.encodeDAGRequest(copReq);

        var results = txnCtx.copProcessor().scanParallel(
                1, data, 0, startKey, endKey, concurrency);

        List<Row> allRows = collectRows(results);
        allRows.sort(buildComparator());

        int start = (int) Math.min(limitOffset, allRows.size());
        int end = (int) Math.min(limitOffset + limitCount, allRows.size());
        resultIterator = allRows.subList(start, end).iterator();
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

    private List<Row> collectRows(
            Iterator<TransactionContext.KVCopProcessor.CopRegionResult> results) {
        List<Row> allRows = new ArrayList<>();
        while (results.hasNext()) {
            var result = results.next();
            if (result.error() != null && !result.error().isEmpty()) {
                throw XDBException.internal("Coprocessor error: " + result.error());
            }
            CopResponse resp = TipbCodec.decodeResponse(result.data());
            if (resp instanceof CopResponse.FilteredRows fr) {
                List<KvPairDecoder.KvPair> kvPairs = KvPairDecoder.decode(fr.kvPairData());
                for (KvPairDecoder.KvPair pair : kvPairs) {
                    long handle = TableCodec.decodeRowHandle(pair.key());
                    Map<Long, Datum> colValues = RowCodec.decode(pair.value());
                    allRows.add(buildRow(handle, colValues));
                }
            }
        }
        return allRows;
    }

    private Comparator<Row> buildComparator() {
        EvalContext evalCtx = txnCtx.evalContext();
        return (r1, r2) -> {
            for (int i = 0; i < orderByExprs.size(); i++) {
                Datum v1 = orderByExprs.get(i).eval(evalCtx, r1);
                Datum v2 = orderByExprs.get(i).eval(evalCtx, r2);
                int cmp = DatumComparator.compare(v1, v2);
                if (cmp != 0) {
                    return ascending.get(i) ? cmp : -cmp;
                }
            }
            return 0;
        };
    }

    private Row buildRow(long handle, Map<Long, Datum> colValues) {
        Datum[] values = new Datum[outputColumns.size()];
        for (int i = 0; i < outputColumns.size(); i++) {
            ColumnInfo col = outputColumns.get(i);
            Datum v = colValues.get(col.getId());
            if (v != null) {
                values[i] = v;
            } else if (isHandleColumn(col)) {
                values[i] = Datum.of(handle);
            } else {
                values[i] = Datum.nil();
            }
        }
        return new Row(values);
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
        List<Integer> indices = new ArrayList<>(outputColumns.size());
        for (ColumnInfo outCol : outputColumns) {
            for (int i = 0; i < table.getColumns().size(); i++) {
                if (table.getColumns().get(i).getId() == outCol.getId()) {
                    indices.add(i);
                    break;
                }
            }
        }
        return indices;
    }
}
