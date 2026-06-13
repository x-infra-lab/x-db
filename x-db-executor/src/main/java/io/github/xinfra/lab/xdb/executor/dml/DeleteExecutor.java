package io.github.xinfra.lab.xdb.executor.dml;

import io.github.xinfra.lab.xdb.executor.Executor;
import io.github.xinfra.lab.xdb.executor.TransactionContext;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.meta.IndexColumn;
import io.github.xinfra.lab.xdb.meta.IndexInfo;
import io.github.xinfra.lab.xdb.meta.TableInfo;
import io.github.xinfra.lab.xdb.table.TableCodec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads rows from child, deletes KV entries for each row.
 * Deletes row KV and all index entries.
 */
public class DeleteExecutor implements Executor {

    private final TransactionContext txnCtx;
    private final Executor child;
    private final TableInfo table;

    private long affectedRows;
    private boolean executed;

    public DeleteExecutor(TransactionContext txnCtx, Executor child, TableInfo table) {
        this.txnCtx = txnCtx;
        this.child = child;
        this.table = table;
    }

    @Override
    public void open() throws Exception {
        child.open();
        executed = false;
        affectedRows = 0;
    }

    @Override
    public Row next() throws Exception {
        if (executed) {
            return null;
        }
        executed = true;

        List<ColumnInfo> childSchema = child.outputSchema();

        Row row;
        while ((row = child.next()) != null) {
            // Extract handle
            long handle = findHandle(row, childSchema);

            // Build values array for index deletion
            Datum[] values = new Datum[table.getColumns().size()];
            for (int i = 0; i < table.getColumns().size(); i++) {
                values[i] = Datum.nil();
            }
            for (int i = 0; i < childSchema.size(); i++) {
                ColumnInfo col = childSchema.get(i);
                int offset = col.getOffset();
                if (offset >= 0 && offset < values.length) {
                    values[offset] = row.get(i);
                }
            }

            // Delete row KV
            byte[] rowKey = TableCodec.encodeRowKey(table.getId(), handle);
            txnCtx.deleter().delete(rowKey);

            // Delete all index entries
            deleteIndexEntries(handle, values);

            affectedRows++;
        }

        return null;
    }

    @Override
    public void close() throws Exception {
        child.close();
    }

    @Override
    public List<ColumnInfo> outputSchema() {
        return Collections.emptyList();
    }

    public long affectedRows() {
        return affectedRows;
    }

    private long findHandle(Row row, List<ColumnInfo> schema) {
        for (int i = 0; i < schema.size(); i++) {
            if (isHandleColumn(schema.get(i))) {
                return row.get(i).toLong();
            }
        }
        return row.get(0).toLong();
    }

    private void deleteIndexEntries(long handle, Datum[] values) throws Exception {
        for (IndexInfo index : table.getIndices()) {
            if (index.isPrimary()) {
                continue;
            }

            List<Datum> indexValues = new ArrayList<>();
            for (IndexColumn idxCol : index.getColumns()) {
                ColumnInfo col = table.getColumn(idxCol.getColumnId());
                if (col != null) {
                    indexValues.add(values[col.getOffset()]);
                } else {
                    indexValues.add(Datum.nil());
                }
            }

            byte[] indexKey;
            if (index.isUnique()) {
                indexKey = TableCodec.encodeIndexKey(table.getId(), index.getId(),
                        indexValues, null);
            } else {
                indexKey = TableCodec.encodeIndexKey(table.getId(), index.getId(),
                        indexValues, handle);
            }

            txnCtx.deleter().delete(indexKey);
        }
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
}
