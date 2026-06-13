package io.github.xinfra.lab.xdb.executor.scan;

import io.github.xinfra.lab.xdb.executor.Executor;
import io.github.xinfra.lab.xdb.executor.TransactionContext;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.meta.TableInfo;
import io.github.xinfra.lab.xdb.table.RowCodec;
import io.github.xinfra.lab.xdb.table.TableCodec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Scans all rows from a table via KV range scan.
 */
public class TableScanExecutor implements Executor {

    private static final int SCAN_BATCH_SIZE = 256;

    private final TransactionContext txnCtx;
    private final TableInfo table;
    private final List<ColumnInfo> outputColumns;
    private final List<Expression> accessConditions;

    private byte[] scanStart;
    private final byte[] scanEnd;
    private List<TransactionContext.KVPair> buffer;
    private int bufferIndex;
    private boolean exhausted;

    public TableScanExecutor(TransactionContext txnCtx, TableInfo table,
                             List<ColumnInfo> outputColumns,
                             List<Expression> accessConditions) {
        this.txnCtx = txnCtx;
        this.table = table;
        this.outputColumns = outputColumns;
        this.accessConditions = accessConditions;
        this.scanStart = TableCodec.tableRecordPrefix(table.getId());
        this.scanEnd = TableCodec.encodeRowKeyMax(table.getId());
    }

    @Override
    public void open() throws Exception {
        this.buffer = new ArrayList<>();
        this.bufferIndex = 0;
        this.exhausted = false;
    }

    @Override
    public Row next() throws Exception {
        EvalContext evalCtx = txnCtx.evalContext();

        while (true) {
            // Refill buffer if needed
            if (bufferIndex >= buffer.size()) {
                if (exhausted) {
                    return null;
                }
                buffer = txnCtx.scanner().scan(scanStart, scanEnd, SCAN_BATCH_SIZE);
                bufferIndex = 0;
                if (buffer.isEmpty()) {
                    exhausted = true;
                    return null;
                }
                if (buffer.size() < SCAN_BATCH_SIZE) {
                    exhausted = true;
                } else {
                    // Next scan starts after the last key
                    byte[] lastKey = buffer.get(buffer.size() - 1).key();
                    scanStart = nextKey(lastKey);
                }
            }

            TransactionContext.KVPair pair = buffer.get(bufferIndex++);

            // Decode row handle
            long handle = TableCodec.decodeRowHandle(pair.key());

            // Decode row value -> map of colId -> Datum
            Map<Long, Datum> colValues = RowCodec.decode(pair.value());

            // Build output row
            Row row = buildRow(handle, colValues);

            // Apply access conditions as filter
            if (passesFilter(row, evalCtx)) {
                return row;
            }
        }
    }

    @Override
    public void close() throws Exception {
        buffer = null;
    }

    @Override
    public List<ColumnInfo> outputSchema() {
        return outputColumns;
    }

    private Row buildRow(long handle, Map<Long, Datum> colValues) {
        Datum[] values = new Datum[outputColumns.size()];
        for (int i = 0; i < outputColumns.size(); i++) {
            ColumnInfo col = outputColumns.get(i);
            Datum v = colValues.get(col.getId());
            if (v != null) {
                values[i] = v;
            } else if (isHandleColumn(col)) {
                // The primary key / auto-increment column value is encoded in the row key
                values[i] = Datum.of(handle);
            } else {
                values[i] = Datum.nil();
            }
        }
        return new Row(values);
    }

    /**
     * Check if a column is the implicit handle (primary key stored in row key).
     * In TiDB-style encoding, single-column integer primary keys are used as
     * the row handle and not stored in the row value.
     */
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

    private boolean passesFilter(Row row, EvalContext evalCtx) {
        if (accessConditions == null || accessConditions.isEmpty()) {
            return true;
        }
        for (Expression cond : accessConditions) {
            Datum result = cond.eval(evalCtx, row);
            if (!result.toBoolean()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Returns the next key after the given key (for scan continuation).
     */
    private static byte[] nextKey(byte[] key) {
        byte[] next = new byte[key.length + 1];
        System.arraycopy(key, 0, next, 0, key.length);
        next[key.length] = 0;
        return next;
    }
}
