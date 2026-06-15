package io.github.xinfra.lab.xdb.executor.scan;

import io.github.xinfra.lab.xdb.executor.Executor;
import io.github.xinfra.lab.xdb.executor.TransactionContext;
import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.meta.IndexColumn;
import io.github.xinfra.lab.xdb.meta.IndexInfo;
import io.github.xinfra.lab.xdb.meta.SchemaState;
import io.github.xinfra.lab.xdb.meta.TableInfo;
import io.github.xinfra.lab.xdb.table.RowCodec;
import io.github.xinfra.lab.xdb.table.TableCodec;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

class IndexLookupExecutorTest {

    @Test
    @DisplayName("Many stale index entries do not cause StackOverflow")
    void manyDeletedRowsDoNotOverflow() throws Exception {
        int staleCount = 20_000;

        ColumnInfo idCol = new ColumnInfo();
        idCol.setId(1);
        idCol.setName("id");
        idCol.setType(DataType.BIGINT);
        idCol.setOffset(0);
        idCol.setAutoIncrement(true);

        ColumnInfo nameCol = new ColumnInfo();
        nameCol.setId(2);
        nameCol.setName("name");
        nameCol.setType(DataType.VARCHAR);
        nameCol.setOffset(1);

        IndexInfo pk = new IndexInfo();
        pk.setId(1);
        pk.setName("PRIMARY");
        pk.setTableId(1);
        pk.setPrimary(true);
        pk.setUnique(true);
        pk.setColumns(List.of(new IndexColumn("id", 1, 0)));

        TableInfo table = new TableInfo();
        table.setId(1);
        table.setName("test");
        table.setColumns(new ArrayList<>(List.of(idCol, nameCol)));
        table.setIndices(new ArrayList<>(List.of(pk)));

        Map<String, byte[]> kvStore = new HashMap<>();

        // Only the last handle has actual row data; all others are stale
        long liveHandle = staleCount + 1;
        byte[] rowKey = TableCodec.encodeRowKey(1, liveHandle);
        byte[] rowValue = RowCodec.encode(List.of(2L), List.of(Datum.of("alive")));
        kvStore.put(hex(rowKey), rowValue);

        TransactionContext txnCtx = new TransactionContext(
                (s, e, l) -> List.of(),
                key -> kvStore.get(hex(key)),
                (k, v) -> {},
                k -> {},
                new EvalContext()
        );

        // Stub IndexScanExecutor that returns staleCount handles pointing to deleted rows,
        // then one handle pointing to a live row
        List<Row> indexRows = new ArrayList<>();
        for (int i = 1; i <= staleCount; i++) {
            indexRows.add(new Row(new Datum[]{Datum.of((long) i)}));
        }
        indexRows.add(new Row(new Datum[]{Datum.of(liveHandle)}));

        StubIndexScan stubScan = new StubIndexScan(indexRows, List.of(idCol));

        IndexLookupExecutor executor = new IndexLookupExecutor(
                txnCtx, stubScan, table, List.of(idCol, nameCol));

        executor.open();
        Row result = executor.next();
        assertThat(result).isNotNull();
        assertThat(result.get(0).toLong()).isEqualTo(liveHandle);
        assertThat(result.get(1).toStringValue()).isEqualTo("alive");

        Row end = executor.next();
        assertThat(end).isNull();
        executor.close();
    }

    @Test
    @DisplayName("All deleted rows returns null without overflow")
    void allDeletedRowsReturnsNull() throws Exception {
        ColumnInfo idCol = new ColumnInfo();
        idCol.setId(1);
        idCol.setName("id");
        idCol.setType(DataType.BIGINT);
        idCol.setOffset(0);
        idCol.setAutoIncrement(true);

        IndexInfo pk = new IndexInfo();
        pk.setId(1);
        pk.setName("PRIMARY");
        pk.setTableId(1);
        pk.setPrimary(true);
        pk.setUnique(true);
        pk.setColumns(List.of(new IndexColumn("id", 1, 0)));

        TableInfo table = new TableInfo();
        table.setId(1);
        table.setName("test");
        table.setColumns(new ArrayList<>(List.of(idCol)));
        table.setIndices(new ArrayList<>(List.of(pk)));

        TransactionContext txnCtx = new TransactionContext(
                (s, e, l) -> List.of(),
                key -> null,
                (k, v) -> {},
                k -> {},
                new EvalContext()
        );

        List<Row> indexRows = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            indexRows.add(new Row(new Datum[]{Datum.of((long) i)}));
        }

        StubIndexScan stubScan = new StubIndexScan(indexRows, List.of(idCol));
        IndexLookupExecutor executor = new IndexLookupExecutor(
                txnCtx, stubScan, table, List.of(idCol));

        executor.open();
        assertThat(executor.next()).isNull();
        executor.close();
    }

    private static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder();
        for (byte b : data) sb.append(String.format("%02x", b));
        return sb.toString();
    }

    /**
     * Stub IndexScanExecutor that returns pre-built rows.
     */
    private static class StubIndexScan extends IndexScanExecutor {
        private final List<Row> rows;
        private final List<ColumnInfo> schema;
        private Iterator<Row> iter;

        StubIndexScan(List<Row> rows, List<ColumnInfo> schema) {
            super(null, dummyTable(), dummyIndex(), schema, List.of());
            this.rows = rows;
            this.schema = schema;
        }

        private static TableInfo dummyTable() {
            TableInfo t = new TableInfo();
            t.setId(99);
            t.setName("stub");
            t.setColumns(new ArrayList<>());
            t.setIndices(new ArrayList<>());
            return t;
        }

        private static IndexInfo dummyIndex() {
            IndexInfo i = new IndexInfo();
            i.setId(99);
            i.setName("stub_idx");
            i.setTableId(99);
            return i;
        }

        @Override
        public void open() {
            iter = rows.iterator();
        }

        @Override
        public Row next() {
            return iter.hasNext() ? iter.next() : null;
        }

        @Override
        public void close() {}

        @Override
        public List<ColumnInfo> outputSchema() {
            return schema;
        }
    }
}
