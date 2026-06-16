package io.github.xinfra.lab.xdb.executor.scan;

import io.github.xinfra.lab.xdb.common.XDBException;
import io.github.xinfra.lab.xdb.executor.Executor;
import io.github.xinfra.lab.xdb.executor.TransactionContext;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.meta.TableInfo;
import io.github.xinfra.lab.xdb.table.CopRequestCodec;
import io.github.xinfra.lab.xdb.table.CopRequestCodec.ColumnDesc;
import io.github.xinfra.lab.xdb.table.CopRequestCodec.CopResponse;
import io.github.xinfra.lab.xdb.table.KvPairDecoder;
import io.github.xinfra.lab.xdb.table.RowCodec;
import io.github.xinfra.lab.xdb.table.TableCodec;
import io.github.xinfra.lab.xdb.table.TipbCodec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class DistScanExecutor implements Executor {

    private static final int DEFAULT_CONCURRENCY = 4;

    private final TransactionContext txnCtx;
    private final TableInfo table;
    private final List<ColumnInfo> outputColumns;
    private final List<Expression> accessConditions;
    private final int concurrency;

    private Iterator<TransactionContext.KVCopProcessor.CopRegionResult> pendingResults;
    private Iterator<Row> currentBatch;

    public DistScanExecutor(TransactionContext txnCtx, TableInfo table,
                            List<ColumnInfo> outputColumns,
                            List<Expression> accessConditions,
                            int concurrency) {
        this.txnCtx = txnCtx;
        this.table = table;
        this.outputColumns = outputColumns;
        this.accessConditions = accessConditions;
        this.concurrency = concurrency > 0 ? concurrency : DEFAULT_CONCURRENCY;
    }

    @Override
    public void open() throws Exception {
        byte[] startKey = TableCodec.tableRecordPrefix(table.getId());
        byte[] endKey = TableCodec.encodeRowKeyMax(table.getId());

        CopRequestCodec.CopRequest copReq = new CopRequestCodec.CopRequest(
                table.getId(), buildColumnDescs(), buildOutputIndices(),
                accessConditions,
                List.of(), List.of(), -1, 0, List.of(), List.of());
        byte[] data = TipbCodec.encodeDAGRequest(copReq);

        var results = txnCtx.copProcessor().scanParallel(
                1, data, 0,
                startKey, endKey, concurrency);

        pendingResults = results;
        currentBatch = Collections.emptyIterator();
    }

    @Override
    public Row next() throws Exception {
        while (true) {
            if (currentBatch.hasNext()) {
                return currentBatch.next();
            }
            if (!pendingResults.hasNext()) {
                return null;
            }
            var regionResult = pendingResults.next();
            if (regionResult.error() != null && !regionResult.error().isEmpty()) {
                throw XDBException.internal("Coprocessor error: " + regionResult.error());
            }
            currentBatch = decodeRegionResponse(regionResult.data()).iterator();
        }
    }

    @Override
    public void close() throws Exception {
        pendingResults = null;
        currentBatch = null;
    }

    @Override
    public List<ColumnInfo> outputSchema() {
        return outputColumns;
    }

    private List<Row> decodeRegionResponse(byte[] data) {
        CopResponse resp = TipbCodec.decodeResponse(data);
        if (resp instanceof CopResponse.FilteredRows fr) {
            return decodeKvPairs(fr.kvPairData());
        }
        return List.of();
    }

    private List<Row> decodeKvPairs(byte[] data) {
        List<KvPairDecoder.KvPair> kvPairs = KvPairDecoder.decode(data);
        List<Row> rows = new ArrayList<>(kvPairs.size());
        for (KvPairDecoder.KvPair pair : kvPairs) {
            long handle = TableCodec.decodeRowHandle(pair.key());
            Map<Long, Datum> colValues = RowCodec.decode(pair.value());
            rows.add(buildRow(handle, colValues));
        }
        return rows;
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
        if (col.isAutoIncrement()) {
            return true;
        }
        var pk = table.getPrimaryIndex();
        if (pk != null && pk.getColumns().size() == 1) {
            return pk.getColumns().get(0).getColumnId() == col.getId();
        }
        return false;
    }

    List<ColumnDesc> buildColumnDescs() {
        List<ColumnDesc> descs = new ArrayList<>(table.getColumns().size());
        for (ColumnInfo col : table.getColumns()) {
            descs.add(new ColumnDesc(col.getId(), col.getType(), isHandleColumn(col), col.getOffset()));
        }
        return descs;
    }

    List<Integer> buildOutputIndices() {
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
