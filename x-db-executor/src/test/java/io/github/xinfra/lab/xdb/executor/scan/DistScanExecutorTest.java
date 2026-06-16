package io.github.xinfra.lab.xdb.executor.scan;

import com.google.protobuf.ByteString;
import io.github.xinfra.lab.xdb.executor.TransactionContext;
import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.EvalContext;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DistScanExecutorTest {

    private TableInfo table;
    private List<ColumnInfo> outputColumns;

    @BeforeEach
    void setUp() {
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
        pk.setTableId(100);
        pk.setPrimary(true);
        pk.setUnique(true);
        pk.setColumns(List.of(new IndexColumn("id", 1, 0)));

        table = new TableInfo();
        table.setId(100);
        table.setName("users");
        table.setColumns(new ArrayList<>(List.of(idCol, nameCol)));
        table.setIndices(new ArrayList<>(List.of(pk)));

        outputColumns = List.of(idCol, nameCol);
    }

    private byte[] encodeRegionData(Map<Long, String> rows) {
        List<KvPairDecoder.KvPair> pairs = new ArrayList<>();
        for (var entry : rows.entrySet()) {
            long handle = entry.getKey();
            byte[] key = TableCodec.encodeRowKey(table.getId(), handle);
            byte[] value = RowCodec.encode(List.of(2L), List.of(Datum.of(entry.getValue())));
            pairs.add(new KvPairDecoder.KvPair(key, value));
        }
        byte[] kvData = KvPairDecoder.encode(pairs);
        return Tipb.SelectResponse.newBuilder()
                .setKvPairData(ByteString.copyFrom(kvData))
                .setIsAgg(false)
                .build().toByteArray();
    }

    private TransactionContext buildCtx(List<TransactionContext.KVCopProcessor.CopRegionResult> results) {
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
    void singleRegionScan() throws Exception {
        var data = encodeRegionData(Map.of(1L, "Alice", 2L, "Bob"));
        var results = List.of(
                new TransactionContext.KVCopProcessor.CopRegionResult(1, data, ""));

        var exec = new DistScanExecutor(buildCtx(results), table, outputColumns, List.of(), 2);
        exec.open();

        List<Row> rows = drainRows(exec);
        exec.close();

        assertThat(rows).hasSize(2);
    }

    @Test
    void multipleRegionScan() throws Exception {
        var data1 = encodeRegionData(Map.of(1L, "Alice", 2L, "Bob"));
        var data2 = encodeRegionData(Map.of(3L, "Charlie"));
        var data3 = encodeRegionData(Map.of(4L, "Diana", 5L, "Eve", 6L, "Frank"));

        var results = List.of(
                new TransactionContext.KVCopProcessor.CopRegionResult(1, data1, ""),
                new TransactionContext.KVCopProcessor.CopRegionResult(2, data2, ""),
                new TransactionContext.KVCopProcessor.CopRegionResult(3, data3, ""));

        var exec = new DistScanExecutor(buildCtx(results), table, outputColumns, List.of(), 4);
        exec.open();

        List<Row> rows = drainRows(exec);
        exec.close();

        assertThat(rows).hasSize(6);
    }

    @Test
    void emptyRegion() throws Exception {
        byte[] kvData = KvPairDecoder.encode(List.of());
        byte[] emptyData = Tipb.SelectResponse.newBuilder()
                .setKvPairData(ByteString.copyFrom(kvData))
                .setIsAgg(false)
                .build().toByteArray();
        var results = List.of(
                new TransactionContext.KVCopProcessor.CopRegionResult(1, emptyData, ""));

        var exec = new DistScanExecutor(buildCtx(results), table, outputColumns, List.of(), 2);
        exec.open();

        List<Row> rows = drainRows(exec);
        exec.close();

        assertThat(rows).isEmpty();
    }

    @Test
    void noRegions() throws Exception {
        var exec = new DistScanExecutor(buildCtx(List.of()), table, outputColumns, List.of(), 2);
        exec.open();

        List<Row> rows = drainRows(exec);
        exec.close();

        assertThat(rows).isEmpty();
    }

    @Test
    void errorFromRegion() throws Exception {
        var results = List.of(
                new TransactionContext.KVCopProcessor.CopRegionResult(1, new byte[0], "region unavailable"));

        var exec = new DistScanExecutor(buildCtx(results), table, outputColumns, List.of(), 2);
        exec.open();

        assertThatThrownBy(() -> drainRows(exec))
                .hasMessageContaining("Coprocessor error");
        exec.close();
    }

    @Test
    void rowValuesDecodedCorrectly() throws Exception {
        byte[] key = TableCodec.encodeRowKey(table.getId(), 42L);
        byte[] value = RowCodec.encode(List.of(2L), List.of(Datum.of("TestName")));
        byte[] kvData = KvPairDecoder.encode(List.of(new KvPairDecoder.KvPair(key, value)));
        byte[] data = Tipb.SelectResponse.newBuilder()
                .setKvPairData(ByteString.copyFrom(kvData))
                .setIsAgg(false)
                .build().toByteArray();

        var results = List.of(
                new TransactionContext.KVCopProcessor.CopRegionResult(1, data, ""));

        var exec = new DistScanExecutor(buildCtx(results), table, outputColumns, List.of(), 1);
        exec.open();

        Row row = exec.next();
        assertThat(row).isNotNull();
        assertThat(row.get(0).toLong()).isEqualTo(42L);
        assertThat(row.get(1).toStringValue()).isEqualTo("TestName");

        assertThat(exec.next()).isNull();
        exec.close();
    }

    @Test
    void requestSerializesDAGRequest() throws Exception {
        var capturedData = new byte[1][];
        TransactionContext.KVCopProcessor cop =
                (tp, data, startTs, start, end, conc) -> {
                    assertThat(tp).isEqualTo(1);
                    capturedData[0] = data;
                    byte[] kvData = KvPairDecoder.encode(List.of());
                    byte[] respData = Tipb.SelectResponse.newBuilder()
                            .setKvPairData(ByteString.copyFrom(kvData))
                            .setIsAgg(false)
                            .build().toByteArray();
                    return List.of(new TransactionContext.KVCopProcessor.CopRegionResult(1, respData, "")).iterator();
                };
        var ctx = new TransactionContext(
                (s, e, l) -> List.of(), k -> null, (k, v) -> {}, k -> {}, cop, new EvalContext());

        var exec = new DistScanExecutor(ctx, table, outputColumns, List.of(), 2);
        exec.open();
        exec.close();

        assertThat(capturedData[0]).isNotNull();
        var decoded = Tipb.DAGRequest.parseFrom(capturedData[0]);
        assertThat(decoded.getTableId()).isEqualTo(100);
        assertThat(decoded.getColumnsCount()).isEqualTo(2);
    }

    private List<Row> drainRows(DistScanExecutor exec) throws Exception {
        List<Row> rows = new ArrayList<>();
        Row row;
        while ((row = exec.next()) != null) {
            rows.add(row);
        }
        return rows;
    }
}
