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
 * Inserts rows into the KV store.
 * Encodes row key/value, and also inserts index entries for all indices.
 */
public class InsertExecutor implements Executor {

    private final TransactionContext txnCtx;
    private final TableInfo table;
    private final List<ColumnInfo> targetColumns;
    private final List<List<Expression>> rows;

    private long affectedRows;
    private long lastInsertId;
    private boolean executed;

    public InsertExecutor(TransactionContext txnCtx, TableInfo table,
                          List<ColumnInfo> targetColumns,
                          List<List<Expression>> rows) {
        this.txnCtx = txnCtx;
        this.table = table;
        this.targetColumns = targetColumns;
        this.rows = rows;
    }

    @Override
    public void open() throws Exception {
        executed = false;
        affectedRows = 0;
        lastInsertId = 0;
    }

    @Override
    public Row next() throws Exception {
        if (executed) {
            return null;
        }
        executed = true;

        EvalContext evalCtx = txnCtx.evalContext();

        for (List<Expression> rowExprs : rows) {
            // Evaluate value expressions for each target column
            Datum[] rowValues = new Datum[table.getColumns().size()];
            for (int i = 0; i < rowValues.length; i++) {
                rowValues[i] = Datum.nil();
            }

            // Fill in target column values
            for (int i = 0; i < targetColumns.size(); i++) {
                ColumnInfo col = targetColumns.get(i);
                Datum value = rowExprs.get(i).eval(evalCtx, new Row(0));
                rowValues[col.getOffset()] = value;
            }

            // Handle auto-increment column
            long handle = allocateHandle(rowValues);

            if (lastInsertId == 0) {
                lastInsertId = handle;
            }

            // Encode row key and value
            byte[] rowKey = TableCodec.encodeRowKey(table.getId(), handle);

            List<Long> colIds = new ArrayList<>();
            List<Datum> colValues = new ArrayList<>();
            for (ColumnInfo col : table.getColumns()) {
                Datum value = rowValues[col.getOffset()];
                // Skip handle column in value encoding (it's in the key)
                if (isHandleColumn(col) && !value.isNull()) {
                    continue;
                }
                if (!value.isNull()) {
                    colIds.add(col.getId());
                    colValues.add(value);
                }
            }

            byte[] rowValue = RowCodec.encode(colIds, colValues);
            txnCtx.putter().put(rowKey, rowValue);

            // Insert index entries
            insertIndexEntries(handle, rowValues);

            affectedRows++;
        }

        return null;
    }

    @Override
    public void close() throws Exception {
        // no-op
    }

    @Override
    public List<ColumnInfo> outputSchema() {
        return Collections.emptyList();
    }

    public long affectedRows() {
        return affectedRows;
    }

    public long lastInsertId() {
        return lastInsertId;
    }

    private long allocateHandle(Datum[] rowValues) {
        // Check for auto-increment column
        for (ColumnInfo col : table.getColumns()) {
            if (col.isAutoIncrement()) {
                Datum value = rowValues[col.getOffset()];
                if (value.isNull() || value.toLong() == 0) {
                    // Auto allocate
                    long id = table.getAutoIncId() + 1;
                    table.setAutoIncId(id);
                    rowValues[col.getOffset()] = Datum.of(id);
                    return id;
                } else {
                    long id = value.toLong();
                    if (id > table.getAutoIncId()) {
                        table.setAutoIncId(id);
                    }
                    return id;
                }
            }
        }

        // No auto-increment: use explicit primary key value or allocate
        IndexInfo pk = table.getPrimaryIndex();
        if (pk != null && pk.getColumns().size() == 1) {
            long colId = pk.getColumns().get(0).getColumnId();
            ColumnInfo pkCol = table.getColumn(colId);
            if (pkCol != null) {
                Datum value = rowValues[pkCol.getOffset()];
                if (!value.isNull()) {
                    return value.toLong();
                }
            }
        }

        // Fallback: auto-allocate a handle
        long id = table.getAutoIncId() + 1;
        table.setAutoIncId(id);
        return id;
    }

    private void insertIndexEntries(long handle, Datum[] rowValues) throws Exception {
        for (IndexInfo index : table.getIndices()) {
            if (index.isPrimary()) {
                continue; // Primary key is encoded in the row key
            }

            List<Datum> indexValues = new ArrayList<>();
            for (IndexColumn idxCol : index.getColumns()) {
                ColumnInfo col = table.getColumn(idxCol.getColumnId());
                if (col != null) {
                    indexValues.add(rowValues[col.getOffset()]);
                } else {
                    indexValues.add(Datum.nil());
                }
            }

            byte[] indexKey;
            byte[] indexValue;
            if (index.isUnique()) {
                indexKey = TableCodec.encodeIndexKey(table.getId(), index.getId(),
                        indexValues, null);
                // Store handle in the value for unique index
                indexValue = io.github.xinfra.lab.xdb.table.DatumCodec.encode(Datum.of(handle));
            } else {
                // Non-unique: append handle to key
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
