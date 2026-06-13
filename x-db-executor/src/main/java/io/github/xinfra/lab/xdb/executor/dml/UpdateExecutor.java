package io.github.xinfra.lab.xdb.executor.dml;

import io.github.xinfra.lab.xdb.executor.Executor;
import io.github.xinfra.lab.xdb.executor.TransactionContext;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.meta.IndexColumn;
import io.github.xinfra.lab.xdb.meta.IndexInfo;
import io.github.xinfra.lab.xdb.meta.TableInfo;
import io.github.xinfra.lab.xdb.table.RowCodec;
import io.github.xinfra.lab.xdb.table.TableCodec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads rows from child, updates column values, and writes back.
 * For each row: read old values, compute new values, delete old index entries,
 * encode new row, put new KV, insert new index entries.
 */
public class UpdateExecutor implements Executor {

    private final TransactionContext txnCtx;
    private final Executor child;
    private final TableInfo table;
    private final List<ColumnInfo> updateColumns;
    private final List<Expression> updateValues;

    private long affectedRows;
    private boolean executed;

    public UpdateExecutor(TransactionContext txnCtx, Executor child,
                          TableInfo table, List<ColumnInfo> updateColumns,
                          List<Expression> updateValues) {
        this.txnCtx = txnCtx;
        this.child = child;
        this.table = table;
        this.updateColumns = updateColumns;
        this.updateValues = updateValues;
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

        EvalContext evalCtx = txnCtx.evalContext();
        List<ColumnInfo> childSchema = child.outputSchema();

        Row row;
        while ((row = child.next()) != null) {
            // Build old values array indexed by column offset
            Datum[] oldValues = new Datum[table.getColumns().size()];
            for (int i = 0; i < table.getColumns().size(); i++) {
                oldValues[i] = Datum.nil();
            }
            for (int i = 0; i < childSchema.size(); i++) {
                ColumnInfo col = childSchema.get(i);
                int offset = col.getOffset();
                if (offset >= 0 && offset < oldValues.length) {
                    oldValues[offset] = row.get(i);
                }
            }

            // Extract old handle
            long handle = findHandle(row, childSchema);

            // Compute new values
            Datum[] newValues = oldValues.clone();
            for (int i = 0; i < updateColumns.size(); i++) {
                ColumnInfo col = updateColumns.get(i);
                Datum newValue = updateValues.get(i).eval(evalCtx, row);
                newValues[col.getOffset()] = newValue;
            }

            // Delete old index entries
            deleteIndexEntries(handle, oldValues);

            // Encode and write new row
            List<Long> colIds = new ArrayList<>();
            List<Datum> colValueList = new ArrayList<>();
            for (ColumnInfo col : table.getColumns()) {
                Datum value = newValues[col.getOffset()];
                if (isHandleColumn(col)) {
                    continue;
                }
                if (!value.isNull()) {
                    colIds.add(col.getId());
                    colValueList.add(value);
                }
            }

            byte[] rowKey = TableCodec.encodeRowKey(table.getId(), handle);
            byte[] rowValue = RowCodec.encode(colIds, colValueList);
            txnCtx.putter().put(rowKey, rowValue);

            // Insert new index entries
            insertIndexEntries(handle, newValues);

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
        // Fallback: first column
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

    private void insertIndexEntries(long handle, Datum[] values) throws Exception {
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
            byte[] indexValue;
            if (index.isUnique()) {
                indexKey = TableCodec.encodeIndexKey(table.getId(), index.getId(),
                        indexValues, null);
                indexValue = io.github.xinfra.lab.xdb.table.DatumCodec.encode(Datum.of(handle));
            } else {
                indexKey = TableCodec.encodeIndexKey(table.getId(), index.getId(),
                        indexValues, handle);
                indexValue = new byte[0];
            }

            txnCtx.putter().put(indexKey, indexValue);
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
