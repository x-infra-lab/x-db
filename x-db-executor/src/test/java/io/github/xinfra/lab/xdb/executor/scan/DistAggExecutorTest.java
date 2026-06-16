package io.github.xinfra.lab.xdb.executor.scan;

import io.github.xinfra.lab.xdb.executor.TransactionContext;
import io.github.xinfra.lab.xdb.expression.AggFunction;
import io.github.xinfra.lab.xdb.expression.AggFunctions;
import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.meta.IndexColumn;
import io.github.xinfra.lab.xdb.meta.IndexInfo;
import io.github.xinfra.lab.xdb.meta.TableInfo;
import io.github.xinfra.lab.xkv.proto.Tipb;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DistAggExecutorTest {

    private TableInfo table;

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

        ColumnInfo ageCol = new ColumnInfo();
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
    }

    private byte[] encodeAggResponse(List<Tipb.AggGroupResult> groups) {
        Tipb.SelectResponse.Builder builder = Tipb.SelectResponse.newBuilder()
                .setIsAgg(true);
        for (Tipb.AggGroupResult g : groups) {
            builder.addAggGroups(g);
        }
        return builder.build().toByteArray();
    }

    private Tipb.AggGroupResult buildGroup(List<Tipb.Datum> groupKeys,
                                            List<Tipb.PartialAggState> states) {
        var b = Tipb.AggGroupResult.newBuilder();
        for (Tipb.Datum k : groupKeys) b.addGroupKeys(k);
        for (Tipb.PartialAggState s : states) b.addPartialStates(s);
        return b.build();
    }

    private Tipb.PartialAggState buildState(int aggTypeOrdinal, boolean distinct,
                                             List<Tipb.Datum> stateData) {
        var b = Tipb.PartialAggState.newBuilder()
                .setAggType(aggTypeOrdinal)
                .setDistinct(distinct);
        for (Tipb.Datum d : stateData) b.addState(d);
        return b.build();
    }

    private Tipb.Datum intDatum(long val) {
        return Tipb.Datum.newBuilder().setIntVal(val).build();
    }

    private Tipb.Datum decimalDatum(String val) {
        return Tipb.Datum.newBuilder().setDecimalVal(val).build();
    }

    private Tipb.Datum stringDatum(String val) {
        return Tipb.Datum.newBuilder().setStringVal(val).build();
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
    void singleRegionCount() throws Exception {
        var group = buildGroup(List.of(),
                List.of(buildState(0, false, List.of(intDatum(5L)))));
        byte[] data = encodeAggResponse(List.of(group));

        var results = List.of(
                new TransactionContext.KVCopProcessor.CopRegionResult(1, data, ""));

        ColumnInfo countCol = new ColumnInfo();
        countCol.setId(100);
        countCol.setName("cnt");
        countCol.setType(DataType.BIGINT);

        List<AggFunction> aggs = List.of(
                AggFunctions.create(AggFunction.Type.COUNT, null, false));

        var exec = new DistAggExecutor(buildCtx(results), table,
                List.of(countCol), List.of(), List.of(), aggs, 2);
        exec.open();

        Row row = exec.next();
        assertThat(row).isNotNull();
        assertThat(row.get(0).toLong()).isEqualTo(5L);
        assertThat(exec.next()).isNull();
        exec.close();
    }

    @Test
    void multiRegionCountMerge() throws Exception {
        var group1 = buildGroup(List.of(),
                List.of(buildState(0, false, List.of(intDatum(3L)))));
        var group2 = buildGroup(List.of(),
                List.of(buildState(0, false, List.of(intDatum(7L)))));

        var results = List.of(
                new TransactionContext.KVCopProcessor.CopRegionResult(1,
                        encodeAggResponse(List.of(group1)), ""),
                new TransactionContext.KVCopProcessor.CopRegionResult(2,
                        encodeAggResponse(List.of(group2)), ""));

        ColumnInfo countCol = new ColumnInfo();
        countCol.setId(100);
        countCol.setName("cnt");
        countCol.setType(DataType.BIGINT);

        List<AggFunction> aggs = List.of(
                AggFunctions.create(AggFunction.Type.COUNT, null, false));

        var exec = new DistAggExecutor(buildCtx(results), table,
                List.of(countCol), List.of(), List.of(), aggs, 2);
        exec.open();

        Row row = exec.next();
        assertThat(row).isNotNull();
        assertThat(row.get(0).toLong()).isEqualTo(10L);
        assertThat(exec.next()).isNull();
        exec.close();
    }

    @Test
    void singleRegionSum() throws Exception {
        var group = buildGroup(List.of(),
                List.of(buildState(1, false, List.of(decimalDatum("150")))));
        byte[] data = encodeAggResponse(List.of(group));

        var results = List.of(
                new TransactionContext.KVCopProcessor.CopRegionResult(1, data, ""));

        ColumnInfo sumCol = new ColumnInfo();
        sumCol.setId(100);
        sumCol.setName("total");
        sumCol.setType(DataType.DECIMAL);

        List<AggFunction> aggs = List.of(
                AggFunctions.create(AggFunction.Type.SUM, null, false));

        var exec = new DistAggExecutor(buildCtx(results), table,
                List.of(sumCol), List.of(), List.of(), aggs, 2);
        exec.open();

        Row row = exec.next();
        assertThat(row).isNotNull();
        assertThat(row.get(0).toDouble()).isEqualTo(150.0);
        assertThat(exec.next()).isNull();
        exec.close();
    }

    @Test
    void multiRegionSumMerge() throws Exception {
        var group1 = buildGroup(List.of(),
                List.of(buildState(1, false, List.of(decimalDatum("100")))));
        var group2 = buildGroup(List.of(),
                List.of(buildState(1, false, List.of(decimalDatum("250")))));

        var results = List.of(
                new TransactionContext.KVCopProcessor.CopRegionResult(1,
                        encodeAggResponse(List.of(group1)), ""),
                new TransactionContext.KVCopProcessor.CopRegionResult(2,
                        encodeAggResponse(List.of(group2)), ""));

        ColumnInfo sumCol = new ColumnInfo();
        sumCol.setId(100);
        sumCol.setName("total");
        sumCol.setType(DataType.DECIMAL);

        List<AggFunction> aggs = List.of(
                AggFunctions.create(AggFunction.Type.SUM, null, false));

        var exec = new DistAggExecutor(buildCtx(results), table,
                List.of(sumCol), List.of(), List.of(), aggs, 2);
        exec.open();

        Row row = exec.next();
        assertThat(row).isNotNull();
        assertThat(row.get(0).toDouble()).isEqualTo(350.0);
        assertThat(exec.next()).isNull();
        exec.close();
    }

    @Test
    void multiRegionAvgMerge() throws Exception {
        var group1 = buildGroup(List.of(),
                List.of(buildState(2, false,
                        List.of(intDatum(3L), decimalDatum("30")))));
        var group2 = buildGroup(List.of(),
                List.of(buildState(2, false,
                        List.of(intDatum(2L), decimalDatum("20")))));

        var results = List.of(
                new TransactionContext.KVCopProcessor.CopRegionResult(1,
                        encodeAggResponse(List.of(group1)), ""),
                new TransactionContext.KVCopProcessor.CopRegionResult(2,
                        encodeAggResponse(List.of(group2)), ""));

        ColumnInfo avgCol = new ColumnInfo();
        avgCol.setId(100);
        avgCol.setName("avg_age");
        avgCol.setType(DataType.DECIMAL);

        List<AggFunction> aggs = List.of(
                AggFunctions.create(AggFunction.Type.AVG, null, false));

        var exec = new DistAggExecutor(buildCtx(results), table,
                List.of(avgCol), List.of(), List.of(), aggs, 2);
        exec.open();

        Row row = exec.next();
        assertThat(row).isNotNull();
        assertThat(row.get(0).toDouble()).isEqualTo(10.0);
        assertThat(exec.next()).isNull();
        exec.close();
    }

    @Test
    void multiRegionMinMerge() throws Exception {
        var group1 = buildGroup(List.of(),
                List.of(buildState(3, false, List.of(intDatum(5L)))));
        var group2 = buildGroup(List.of(),
                List.of(buildState(3, false, List.of(intDatum(2L)))));

        var results = List.of(
                new TransactionContext.KVCopProcessor.CopRegionResult(1,
                        encodeAggResponse(List.of(group1)), ""),
                new TransactionContext.KVCopProcessor.CopRegionResult(2,
                        encodeAggResponse(List.of(group2)), ""));

        ColumnInfo minCol = new ColumnInfo();
        minCol.setId(100);
        minCol.setName("min_age");
        minCol.setType(DataType.BIGINT);

        List<AggFunction> aggs = List.of(
                AggFunctions.create(AggFunction.Type.MIN, null, false));

        var exec = new DistAggExecutor(buildCtx(results), table,
                List.of(minCol), List.of(), List.of(), aggs, 2);
        exec.open();

        Row row = exec.next();
        assertThat(row).isNotNull();
        assertThat(row.get(0).toLong()).isEqualTo(2L);
        assertThat(exec.next()).isNull();
        exec.close();
    }

    @Test
    void multiRegionMaxMerge() throws Exception {
        var group1 = buildGroup(List.of(),
                List.of(buildState(4, false, List.of(intDatum(5L)))));
        var group2 = buildGroup(List.of(),
                List.of(buildState(4, false, List.of(intDatum(8L)))));

        var results = List.of(
                new TransactionContext.KVCopProcessor.CopRegionResult(1,
                        encodeAggResponse(List.of(group1)), ""),
                new TransactionContext.KVCopProcessor.CopRegionResult(2,
                        encodeAggResponse(List.of(group2)), ""));

        ColumnInfo maxCol = new ColumnInfo();
        maxCol.setId(100);
        maxCol.setName("max_age");
        maxCol.setType(DataType.BIGINT);

        List<AggFunction> aggs = List.of(
                AggFunctions.create(AggFunction.Type.MAX, null, false));

        var exec = new DistAggExecutor(buildCtx(results), table,
                List.of(maxCol), List.of(), List.of(), aggs, 2);
        exec.open();

        Row row = exec.next();
        assertThat(row).isNotNull();
        assertThat(row.get(0).toLong()).isEqualTo(8L);
        assertThat(exec.next()).isNull();
        exec.close();
    }

    @Test
    void groupByWithCountAndSum() throws Exception {
        var aliceGroup = buildGroup(List.of(stringDatum("Alice")),
                List.of(
                        buildState(0, false, List.of(intDatum(2L))),
                        buildState(1, false, List.of(decimalDatum("50")))));
        var bobGroup = buildGroup(List.of(stringDatum("Bob")),
                List.of(
                        buildState(0, false, List.of(intDatum(3L))),
                        buildState(1, false, List.of(decimalDatum("90")))));

        byte[] data = encodeAggResponse(List.of(aliceGroup, bobGroup));
        var results = List.of(
                new TransactionContext.KVCopProcessor.CopRegionResult(1, data, ""));

        ColumnInfo nameCol = new ColumnInfo();
        nameCol.setId(2);
        nameCol.setName("name");
        nameCol.setType(DataType.VARCHAR);

        ColumnInfo countCol = new ColumnInfo();
        countCol.setId(100);
        countCol.setName("cnt");
        countCol.setType(DataType.BIGINT);

        ColumnInfo sumCol = new ColumnInfo();
        sumCol.setId(101);
        sumCol.setName("total");
        sumCol.setType(DataType.DECIMAL);

        List<AggFunction> aggs = List.of(
                AggFunctions.create(AggFunction.Type.COUNT, null, false),
                AggFunctions.create(AggFunction.Type.SUM, null, false));

        var exec = new DistAggExecutor(buildCtx(results), table,
                List.of(nameCol, countCol, sumCol), List.of(), List.of(), aggs, 2);
        exec.open();

        List<Row> rows = drainRows(exec);
        exec.close();

        assertThat(rows).hasSize(2);
        boolean foundAlice = false, foundBob = false;
        for (Row row : rows) {
            String name = row.get(0).toStringValue();
            if ("Alice".equals(name)) {
                assertThat(row.get(1).toLong()).isEqualTo(2L);
                assertThat(row.get(2).toDouble()).isEqualTo(50.0);
                foundAlice = true;
            } else if ("Bob".equals(name)) {
                assertThat(row.get(1).toLong()).isEqualTo(3L);
                assertThat(row.get(2).toDouble()).isEqualTo(90.0);
                foundBob = true;
            }
        }
        assertThat(foundAlice).isTrue();
        assertThat(foundBob).isTrue();
    }

    @Test
    void groupByMergeAcrossRegions() throws Exception {
        var r1Alice = buildGroup(List.of(stringDatum("Alice")),
                List.of(buildState(0, false, List.of(intDatum(2L)))));
        var r1Bob = buildGroup(List.of(stringDatum("Bob")),
                List.of(buildState(0, false, List.of(intDatum(1L)))));
        var r2Alice = buildGroup(List.of(stringDatum("Alice")),
                List.of(buildState(0, false, List.of(intDatum(3L)))));
        var r2Charlie = buildGroup(List.of(stringDatum("Charlie")),
                List.of(buildState(0, false, List.of(intDatum(4L)))));

        var results = List.of(
                new TransactionContext.KVCopProcessor.CopRegionResult(1,
                        encodeAggResponse(List.of(r1Alice, r1Bob)), ""),
                new TransactionContext.KVCopProcessor.CopRegionResult(2,
                        encodeAggResponse(List.of(r2Alice, r2Charlie)), ""));

        ColumnInfo nameCol = new ColumnInfo();
        nameCol.setId(2);
        nameCol.setName("name");
        nameCol.setType(DataType.VARCHAR);

        ColumnInfo countCol = new ColumnInfo();
        countCol.setId(100);
        countCol.setName("cnt");
        countCol.setType(DataType.BIGINT);

        List<AggFunction> aggs = List.of(
                AggFunctions.create(AggFunction.Type.COUNT, null, false));

        var exec = new DistAggExecutor(buildCtx(results), table,
                List.of(nameCol, countCol), List.of(), List.of(), aggs, 2);
        exec.open();

        List<Row> rows = drainRows(exec);
        exec.close();

        assertThat(rows).hasSize(3);
        long aliceCount = 0, bobCount = 0, charlieCount = 0;
        for (Row row : rows) {
            String name = row.get(0).toStringValue();
            long cnt = row.get(1).toLong();
            switch (name) {
                case "Alice" -> aliceCount = cnt;
                case "Bob" -> bobCount = cnt;
                case "Charlie" -> charlieCount = cnt;
            }
        }
        assertThat(aliceCount).isEqualTo(5L);
        assertThat(bobCount).isEqualTo(1L);
        assertThat(charlieCount).isEqualTo(4L);
    }

    @Test
    void emptyResultNoGroupBy() throws Exception {
        var results = List.<TransactionContext.KVCopProcessor.CopRegionResult>of();

        ColumnInfo countCol = new ColumnInfo();
        countCol.setId(100);
        countCol.setName("cnt");
        countCol.setType(DataType.BIGINT);

        List<AggFunction> aggs = List.of(
                AggFunctions.create(AggFunction.Type.COUNT, null, false));

        var exec = new DistAggExecutor(buildCtx(results), table,
                List.of(countCol), List.of(), List.of(), aggs, 2);
        exec.open();

        Row row = exec.next();
        assertThat(row).isNotNull();
        assertThat(row.get(0).toLong()).isEqualTo(0L);
        assertThat(exec.next()).isNull();
        exec.close();
    }

    @Test
    void emptyResultWithGroupBy() throws Exception {
        var results = List.<TransactionContext.KVCopProcessor.CopRegionResult>of();

        ColumnInfo nameCol = new ColumnInfo();
        nameCol.setId(2);
        nameCol.setName("name");
        nameCol.setType(DataType.VARCHAR);

        ColumnInfo countCol = new ColumnInfo();
        countCol.setId(100);
        countCol.setName("cnt");
        countCol.setType(DataType.BIGINT);

        List<AggFunction> aggs = List.of(
                AggFunctions.create(AggFunction.Type.COUNT, null, false));
        List<io.github.xinfra.lab.xdb.expression.Expression> groupBy =
                List.of(new io.github.xinfra.lab.xdb.expression.ColumnRef("users", "name", 1, DataType.VARCHAR));

        var exec = new DistAggExecutor(buildCtx(results), table,
                List.of(nameCol, countCol), List.of(), groupBy, aggs, 2);
        exec.open();

        assertThat(exec.next()).isNull();
        exec.close();
    }

    @Test
    void errorFromRegion() throws Exception {
        var results = List.of(
                new TransactionContext.KVCopProcessor.CopRegionResult(1,
                        new byte[0], "region unavailable"));

        ColumnInfo countCol = new ColumnInfo();
        countCol.setId(100);
        countCol.setName("cnt");
        countCol.setType(DataType.BIGINT);

        List<AggFunction> aggs = List.of(
                AggFunctions.create(AggFunction.Type.COUNT, null, false));

        var exec = new DistAggExecutor(buildCtx(results), table,
                List.of(countCol), List.of(), List.of(), aggs, 2);

        assertThatThrownBy(exec::open)
                .hasMessageContaining("Coprocessor error");
        exec.close();
    }

    @Test
    void groupConcatMerge() throws Exception {
        var group1 = buildGroup(List.of(),
                List.of(buildState(5, false, List.of(stringDatum("a,b")))));
        var group2 = buildGroup(List.of(),
                List.of(buildState(5, false, List.of(stringDatum("c")))));

        var results = List.of(
                new TransactionContext.KVCopProcessor.CopRegionResult(1,
                        encodeAggResponse(List.of(group1)), ""),
                new TransactionContext.KVCopProcessor.CopRegionResult(2,
                        encodeAggResponse(List.of(group2)), ""));

        ColumnInfo concatCol = new ColumnInfo();
        concatCol.setId(100);
        concatCol.setName("names");
        concatCol.setType(DataType.VARCHAR);

        List<AggFunction> aggs = List.of(
                AggFunctions.create(AggFunction.Type.GROUP_CONCAT, null, false));

        var exec = new DistAggExecutor(buildCtx(results), table,
                List.of(concatCol), List.of(), List.of(), aggs, 2);
        exec.open();

        Row row = exec.next();
        assertThat(row).isNotNull();
        assertThat(row.get(0).toStringValue()).isEqualTo("a,b,c");
        assertThat(exec.next()).isNull();
        exec.close();
    }

    @Test
    void multipleAggFunctionsInSameQuery() throws Exception {
        var group = buildGroup(List.of(),
                List.of(
                        buildState(0, false, List.of(intDatum(10L))),
                        buildState(1, false, List.of(decimalDatum("500"))),
                        buildState(3, false, List.of(intDatum(5L))),
                        buildState(4, false, List.of(intDatum(95L)))));

        byte[] data = encodeAggResponse(List.of(group));
        var results = List.of(
                new TransactionContext.KVCopProcessor.CopRegionResult(1, data, ""));

        ColumnInfo countCol = new ColumnInfo();
        countCol.setId(100); countCol.setName("cnt"); countCol.setType(DataType.BIGINT);
        ColumnInfo sumCol = new ColumnInfo();
        sumCol.setId(101); sumCol.setName("total"); sumCol.setType(DataType.DECIMAL);
        ColumnInfo minCol = new ColumnInfo();
        minCol.setId(102); minCol.setName("mn"); minCol.setType(DataType.BIGINT);
        ColumnInfo maxCol = new ColumnInfo();
        maxCol.setId(103); maxCol.setName("mx"); maxCol.setType(DataType.BIGINT);

        List<AggFunction> aggs = List.of(
                AggFunctions.create(AggFunction.Type.COUNT, null, false),
                AggFunctions.create(AggFunction.Type.SUM, null, false),
                AggFunctions.create(AggFunction.Type.MIN, null, false),
                AggFunctions.create(AggFunction.Type.MAX, null, false));

        var exec = new DistAggExecutor(buildCtx(results), table,
                List.of(countCol, sumCol, minCol, maxCol),
                List.of(), List.of(), aggs, 2);
        exec.open();

        Row row = exec.next();
        assertThat(row).isNotNull();
        assertThat(row.get(0).toLong()).isEqualTo(10L);
        assertThat(row.get(1).toDouble()).isEqualTo(500.0);
        assertThat(row.get(2).toLong()).isEqualTo(5L);
        assertThat(row.get(3).toLong()).isEqualTo(95L);
        assertThat(exec.next()).isNull();
        exec.close();
    }

    @Test
    void outputSchemaReturnsColumns() {
        ColumnInfo col = new ColumnInfo();
        col.setId(100); col.setName("cnt"); col.setType(DataType.BIGINT);

        List<ColumnInfo> output = List.of(col);
        var exec = new DistAggExecutor(buildCtx(List.of()), table,
                output, List.of(), List.of(),
                List.of(AggFunctions.create(AggFunction.Type.COUNT, null, false)), 2);

        assertThat(exec.outputSchema()).isSameAs(output);
    }

    private List<Row> drainRows(DistAggExecutor exec) throws Exception {
        List<Row> rows = new ArrayList<>();
        Row row;
        while ((row = exec.next()) != null) {
            rows.add(row);
        }
        return rows;
    }
}
