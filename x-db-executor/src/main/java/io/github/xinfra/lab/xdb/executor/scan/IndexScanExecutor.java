package io.github.xinfra.lab.xdb.executor.scan;

import io.github.xinfra.lab.xdb.executor.Executor;
import io.github.xinfra.lab.xdb.executor.TransactionContext;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.meta.IndexInfo;
import io.github.xinfra.lab.xdb.meta.TableInfo;
import io.github.xinfra.lab.xdb.table.DatumCodec;
import io.github.xinfra.lab.xdb.table.TableCodec;

import java.util.ArrayList;
import java.util.List;

/**
 * Scans index entries from the KV store.
 * Decodes index keys to extract column values and the row handle.
 */
public class IndexScanExecutor implements Executor {

    private static final int SCAN_BATCH_SIZE = 256;

    private final TransactionContext txnCtx;
    private final TableInfo table;
    private final IndexInfo index;
    private final List<ColumnInfo> outputColumns;
    private final List<Expression> accessConditions;

    private byte[] scanStart;
    private final byte[] scanEnd;
    private List<TransactionContext.KVPair> buffer;
    private int bufferIndex;
    private boolean exhausted;

    public IndexScanExecutor(TransactionContext txnCtx, TableInfo table,
                             IndexInfo index, List<ColumnInfo> outputColumns,
                             List<Expression> accessConditions) {
        this.txnCtx = txnCtx;
        this.table = table;
        this.index = index;
        this.outputColumns = outputColumns;
        this.accessConditions = accessConditions;

        // Index key range: [prefix, prefix + 1)
        byte[] prefix = TableCodec.encodeIndexKeyPrefix(table.getId(), index.getId());
        this.scanStart = prefix;
        this.scanEnd = nextPrefix(prefix);
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
                    byte[] lastKey = buffer.get(buffer.size() - 1).key();
                    scanStart = nextKey(lastKey);
                }
            }

            TransactionContext.KVPair pair = buffer.get(bufferIndex++);

            // Decode index key to extract column values and handle
            Row row = decodeIndexEntry(pair);

            // Apply access conditions
            if (passesFilter(row, evalCtx)) {
                return row;
            }
        }
    }

    /**
     * Return the handle (row ID) from the last decoded index entry.
     * For unique index, handle is stored in the value; for non-unique, it's in the key.
     */
    public long decodeHandle(TransactionContext.KVPair pair) {
        int numIndexCols = index.getColumns().size();
        if (index.isUnique()) {
            // For unique index, handle is stored in the value
            if (pair.value() != null && pair.value().length > 0) {
                int[] bytesRead = new int[1];
                Datum handleDatum = DatumCodec.decode(pair.value(), 0, bytesRead);
                return handleDatum.toLong();
            }
            return 0;
        } else {
            // For non-unique index, handle is appended to the key
            return TableCodec.decodeIndexHandle(pair.key(), numIndexCols);
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

    private Row decodeIndexEntry(TransactionContext.KVPair pair) {
        int numIndexCols = index.getColumns().size();

        // Decode column values from the index key
        // Skip: t(1) + tableID(8) + _i(2) + indexID(8) = 19
        List<Datum> indexValues = new ArrayList<>();
        int offset = 19;
        int[] bytesRead = new int[1];
        for (int i = 0; i < numIndexCols; i++) {
            Datum value = DatumCodec.decode(pair.key(), offset, bytesRead);
            indexValues.add(value);
            offset += bytesRead[0];
        }

        // Get handle
        long handle = decodeHandle(pair);

        // Build output row: map index columns and handle to output schema
        Datum[] values = new Datum[outputColumns.size()];
        for (int i = 0; i < outputColumns.size(); i++) {
            ColumnInfo outCol = outputColumns.get(i);
            boolean found = false;

            // Check if this output column is one of the index columns
            for (int j = 0; j < index.getColumns().size(); j++) {
                if (index.getColumns().get(j).getColumnId() == outCol.getId()) {
                    values[i] = indexValues.get(j);
                    found = true;
                    break;
                }
            }

            if (!found) {
                // Check if it's the handle column
                if (isHandleColumn(outCol)) {
                    values[i] = Datum.of(handle);
                } else {
                    values[i] = Datum.nil();
                }
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

    private static byte[] nextKey(byte[] key) {
        byte[] next = new byte[key.length + 1];
        System.arraycopy(key, 0, next, 0, key.length);
        next[key.length] = 0;
        return next;
    }

    private static byte[] nextPrefix(byte[] prefix) {
        byte[] next = prefix.clone();
        for (int i = next.length - 1; i >= 0; i--) {
            next[i]++;
            if (next[i] != 0) {
                return next;
            }
        }
        // Overflow: return a key that is larger than any key with this prefix
        byte[] overflow = new byte[prefix.length + 1];
        System.arraycopy(prefix, 0, overflow, 0, prefix.length);
        overflow[prefix.length] = 0;
        return overflow;
    }
}
