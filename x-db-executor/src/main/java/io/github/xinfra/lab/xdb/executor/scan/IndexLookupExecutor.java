package io.github.xinfra.lab.xdb.executor.scan;

import io.github.xinfra.lab.xdb.executor.Executor;
import io.github.xinfra.lab.xdb.executor.TransactionContext;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.meta.TableInfo;
import io.github.xinfra.lab.xdb.table.RowCodec;
import io.github.xinfra.lab.xdb.table.TableCodec;

import java.util.List;
import java.util.Map;

/**
 * Wraps an IndexScanExecutor, performs point-gets for each row handle
 * to retrieve the full row data from the table.
 */
public class IndexLookupExecutor implements Executor {

    private final TransactionContext txnCtx;
    private final IndexScanExecutor indexScan;
    private final TableInfo table;
    private final List<ColumnInfo> outputColumns;

    public IndexLookupExecutor(TransactionContext txnCtx,
                               IndexScanExecutor indexScan,
                               TableInfo table,
                               List<ColumnInfo> outputColumns) {
        this.txnCtx = txnCtx;
        this.indexScan = indexScan;
        this.table = table;
        this.outputColumns = outputColumns;
    }

    @Override
    public void open() throws Exception {
        indexScan.open();
    }

    @Override
    public Row next() throws Exception {
        while (true) {
            Row indexRow = indexScan.next();
            if (indexRow == null) {
                return null;
            }

            long handle = findHandle(indexRow);

            byte[] rowKey = TableCodec.encodeRowKey(table.getId(), handle);
            byte[] rowValue = txnCtx.getter().get(rowKey);

            if (rowValue == null) {
                continue;
            }

            Map<Long, Datum> colValues = RowCodec.decode(rowValue);

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
    }

    @Override
    public void close() throws Exception {
        indexScan.close();
    }

    @Override
    public List<ColumnInfo> outputSchema() {
        return outputColumns;
    }

    /**
     * Extract the handle from the index scan row.
     * The handle column is typically the primary key or auto-increment column.
     */
    private long findHandle(Row indexRow) {
        List<ColumnInfo> indexSchema = indexScan.outputSchema();
        for (int i = 0; i < indexSchema.size(); i++) {
            if (isHandleColumn(indexSchema.get(i))) {
                return indexRow.get(i).toLong();
            }
        }
        // Fallback: assume last column in index output is the handle
        return indexRow.get(indexRow.size() - 1).toLong();
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
