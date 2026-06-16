package io.github.xinfra.lab.xdb.executor.scan;

import com.google.protobuf.ByteString;
import io.github.xinfra.lab.xdb.executor.TransactionContext;
import io.github.xinfra.lab.xdb.expression.ColumnRef;
import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.meta.IndexColumn;
import io.github.xinfra.lab.xdb.meta.IndexInfo;
import io.github.xinfra.lab.xdb.meta.TableInfo;
import io.github.xinfra.lab.xdb.table.KvPairDecoder;
import io.github.xinfra.lab.xdb.table.RowCodec;
import io.github.xinfra.lab.xdb.table.TableCodec;
import io.github.xinfra.lab.xkv.proto.Tipb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DistTopNExecutorTest {

    private TableInfo table;
    private List<ColumnInfo> outputColumns;
    private ColumnInfo idCol;
    private ColumnInfo nameCol;
    private ColumnInfo ageCol;

    @BeforeEach
    void setUp() {
        idCol = new ColumnInfo();
        idCol.setId(1);
        idCol.setName("id");
        idCol.setType(DataType.BIGINT);
        idCol.setOffset(0);
        idCol.setAutoIncrement(true);

        nameCol = new ColumnInfo();
        nameCol.setId(2);
        nameCol.setName("name");
        nameCol.setType(DataType.VARCHAR);
        nameCol.setOffset(1);

        ageCol = new ColumnInfo();
        ageCol.setId(3);
        ageCol.setName("age");
        ageCol.setType(DataType.BIGINT);
        ageCol.setOffset(2);

        IndexInfo pk = new IndexInfo();
        pk.setId(1);
        pk.setName("PRIMARY");
        pk.setTableId(100);
        pk.setPrimary(true);
        pk.setUnique(true);
        pk.setColumns(List.of(new IndexColumn("id", 1, 0)));

        table = new TableInfo();
        table.setId(100);
        table.setName("users");
        table.setColumns(new ArrayList<>(List.of(idCol, nameCol, ageCol)));
        table.setIndices(new ArrayList<>(List.of(pk)));

        outputColumns = List.of(idCol, nameCol, ageCol);
    }

    private byte[] encodeRegionData(Map<Long, Object[]> rows) {
        TreeMap<Long, Object[]> sorted = new TreeMap<>(rows);
        List<KvPairDecoder.KvPair> pairs = new ArrayList<>();
        for (var entry : sorted.entrySet()) {
            long handle = entry.getKey();
            Object[] vals = entry.getValue();
            byte[] key = TableCodec.encodeRowKey(table.getId(), handle);
            byte[] value = RowCodec.encode(
                    List.of(2L, 3L),
                    List.of(Datum.of((String) vals[0]), Datum.of((long) vals[1])));
            pairs.add(new KvPairDecoder.KvPair(key, value));
        }
        byte[] kvData = KvPairDecoder.encode(pairs);
        return Tipb.SelectResponse.newBuilder()
                .setKvPairData(ByteString.copyFrom(kvData))
                .setIsAgg(false)
                .build().toByteArray();
    }

    private TransactionContext buildCtx(
            List<TransactionContext.KVCopProcessor.CopRegionResult> results) {
        TransactionContext.KVCopProcessor cop =
                (tp, data, startTs, start, end, conc) -> results.iterator();
        return new TransactionContext(
                (s, e, l) -> List.of(),
                k -> null,
                (k, v) -> {},
                k -> {},
                cop,
                new EvalContext()
        );
    }

    @Test
    void singleRegionTopNAsc() throws Exception {
        var data = encodeRegionData(Map.of(
                1L, new Object[]{"Alice", 30L},
                2L, new Object[]{"Bob", 25L},
                3L, new Object[]{"Charlie", 35L},
                4L, new Object[]{"Diana", 20L}));
        var results = List.of(
                new TransactionContext.KVCopProcessor.CopRegionResult(1, data, ""));

        Expression orderExpr = new ColumnRef("users", "age", 2, DataType.BIGINT);

        var exec = new DistTopNExecutor(buildCtx(results), table, outputColumns,
                List.of(), List.of(orderExpr), List.of(true),
                2, 0, 2);
        exec.open();

        List<Row> rows = drainRows(exec);
        exec.close();

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get(2).toLong()).isEqualTo(20L);
        assertThat(rows.get(1).get(2).toLong()).isEqualTo(25L);
    }

    @Test
    void singleRegionTopNDesc() throws Exception {
        var data = encodeRegionData(Map.of(
                1L, new Object[]{"Alice", 30L},
                2L, new Object[]{"Bob", 25L},
                3L, new Object[]{"Charlie", 35L}));
        var results = List.of(
                new TransactionContext.KVCopProcessor.CopRegionResult(1, data, ""));

        Expression orderExpr = new ColumnRef("users", "age", 2, DataType.BIGINT);

        var exec = new DistTopNExecutor(buildCtx(results), table, outputColumns,
                List.of(), List.of(orderExpr), List.of(false),
                2, 0, 2);
        exec.open();

        List<Row> rows = drainRows(exec);
        exec.close();

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get(2).toLong()).isEqualTo(35L);
        assertThat(rows.get(1).get(2).toLong()).isEqualTo(30L);
    }

    @Test
    void multiRegionMergeSorted() throws Exception {
        var data1 = encodeRegionData(Map.of(
                1L, new Object[]{"Alice", 30L},
                2L, new Object[]{"Bob", 10L}));
        var data2 = encodeRegionData(Map.of(
                3L, new Object[]{"Charlie", 25L},
                4L, new Object[]{"Diana", 5L},
                5L, new Object[]{"Eve", 40L}));
        var results = List.of(
                new TransactionContext.KVCopProcessor.CopRegionResult(1, data1, ""),
                new TransactionContext.KVCopProcessor.CopRegionResult(2, data2, ""));

        Expression orderExpr = new ColumnRef("users", "age", 2, DataType.BIGINT);

        var exec = new DistTopNExecutor(buildCtx(results), table, outputColumns,
                List.of(), List.of(orderExpr), List.of(true),
                3, 0, 2);
        exec.open();

        List<Row> rows = drainRows(exec);
        exec.close();

        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).get(2).toLong()).isEqualTo(5L);
        assertThat(rows.get(1).get(2).toLong()).isEqualTo(10L);
        assertThat(rows.get(2).get(2).toLong()).isEqualTo(25L);
    }

    @Test
    void topNWithOffset() throws Exception {
        var data = encodeRegionData(Map.of(
                1L, new Object[]{"A", 10L},
                2L, new Object[]{"B", 20L},
                3L, new Object[]{"C", 30L},
                4L, new Object[]{"D", 40L},
                5L, new Object[]{"E", 50L}));
        var results = List.of(
                new TransactionContext.KVCopProcessor.CopRegionResult(1, data, ""));

        Expression orderExpr = new ColumnRef("users", "age", 2, DataType.BIGINT);

        var exec = new DistTopNExecutor(buildCtx(results), table, outputColumns,
                List.of(), List.of(orderExpr), List.of(true),
                2, 1, 2);
        exec.open();

        List<Row> rows = drainRows(exec);
        exec.close();

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get(2).toLong()).isEqualTo(20L);
        assertThat(rows.get(1).get(2).toLong()).isEqualTo(30L);
    }

    @Test
    void emptyResult() throws Exception {
        byte[] kvData = KvPairDecoder.encode(List.of());
        byte[] emptyData = Tipb.SelectResponse.newBuilder()
                .setKvPairData(ByteString.copyFrom(kvData))
                .setIsAgg(false)
                .build().toByteArray();
        var results = List.of(
                new TransactionContext.KVCopProcessor.CopRegionResult(1, emptyData, ""));

        Expression orderExpr = new ColumnRef("users", "age", 2, DataType.BIGINT);

        var exec = new DistTopNExecutor(buildCtx(results), table, outputColumns,
                List.of(), List.of(orderExpr), List.of(true),
                10, 0, 2);
        exec.open();

        assertThat(exec.next()).isNull();
        exec.close();
    }

    @Test
    void noRegions() throws Exception {
        Expression orderExpr = new ColumnRef("users", "age", 2, DataType.BIGINT);

        var exec = new DistTopNExecutor(buildCtx(List.of()), table, outputColumns,
                List.of(), List.of(orderExpr), List.of(true),
                10, 0, 2);
        exec.open();

        assertThat(exec.next()).isNull();
        exec.close();
    }

    @Test
    void errorFromRegion() throws Exception {
        var results = List.of(
                new TransactionContext.KVCopProcessor.CopRegionResult(1,
                        new byte[0], "region unavailable"));

        Expression orderExpr = new ColumnRef("users", "age", 2, DataType.BIGINT);

        var exec = new DistTopNExecutor(buildCtx(results), table, outputColumns,
                List.of(), List.of(orderExpr), List.of(true),
                10, 0, 2);

        assertThatThrownBy(exec::open)
                .hasMessageContaining("Coprocessor error");
        exec.close();
    }

    @Test
    void limitLargerThanResults() throws Exception {
        var data = encodeRegionData(Map.of(
                1L, new Object[]{"Alice", 30L},
                2L, new Object[]{"Bob", 25L}));
        var results = List.of(
                new TransactionContext.KVCopProcessor.CopRegionResult(1, data, ""));

        Expression orderExpr = new ColumnRef("users", "age", 2, DataType.BIGINT);

        var exec = new DistTopNExecutor(buildCtx(results), table, outputColumns,
                List.of(), List.of(orderExpr), List.of(true),
                100, 0, 2);
        exec.open();

        List<Row> rows = drainRows(exec);
        exec.close();

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get(2).toLong()).isEqualTo(25L);
        assertThat(rows.get(1).get(2).toLong()).isEqualTo(30L);
    }

    @Test
    void outputSchemaReturnsColumns() {
        Expression orderExpr = new ColumnRef("users", "age", 2, DataType.BIGINT);

        var exec = new DistTopNExecutor(buildCtx(List.of()), table, outputColumns,
                List.of(), List.of(orderExpr), List.of(true),
                10, 0, 2);

        assertThat(exec.outputSchema()).isSameAs(outputColumns);
    }

    private List<Row> drainRows(DistTopNExecutor exec) throws Exception {
        List<Row> rows = new ArrayList<>();
        Row row;
        while ((row = exec.next()) != null) {
            rows.add(row);
        }
        return rows;
    }
}
