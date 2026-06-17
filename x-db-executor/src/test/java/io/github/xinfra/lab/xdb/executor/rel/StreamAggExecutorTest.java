package io.github.xinfra.lab.xdb.executor.rel;

import io.github.xinfra.lab.xdb.executor.ListExecutor;
import io.github.xinfra.lab.xdb.expression.AggFunction;
import io.github.xinfra.lab.xdb.expression.AggFunctions;
import io.github.xinfra.lab.xdb.expression.ColumnRef;
import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static io.github.xinfra.lab.xdb.executor.ListExecutor.col;
import static org.assertj.core.api.Assertions.assertThat;

class StreamAggExecutorTest {

    private final EvalContext evalCtx = new EvalContext();

    private final List<ColumnInfo> inputSchema = List.of(
            col("dept", DataType.VARCHAR),
            col("salary", DataType.BIGINT)
    );

    @Test
    @DisplayName("grouped COUNT with pre-sorted input")
    void groupedCount() throws Exception {
        // dept=A (3 rows), dept=B (2 rows) — pre-sorted by dept
        List<Row> rows = List.of(
                new Row(new Datum[]{Datum.of("A"), Datum.of(100L)}),
                new Row(new Datum[]{Datum.of("A"), Datum.of(200L)}),
                new Row(new Datum[]{Datum.of("A"), Datum.of(150L)}),
                new Row(new Datum[]{Datum.of("B"), Datum.of(300L)}),
                new Row(new Datum[]{Datum.of("B"), Datum.of(400L)})
        );

        Expression deptRef = new ColumnRef(null, "dept", 0, DataType.VARCHAR);
        AggFunction countAgg = AggFunctions.create(AggFunction.Type.COUNT, null, false);

        List<ColumnInfo> outputSchema = List.of(col("dept", DataType.VARCHAR), col("cnt", DataType.BIGINT));
        StreamAggExecutor exec = new StreamAggExecutor(
                new ListExecutor(rows, inputSchema),
                List.of(deptRef), List.of(countAgg), outputSchema, evalCtx);
        exec.open();

        Row r1 = exec.next();
        assertThat(r1).isNotNull();
        assertThat(r1.get(0).toStringValue()).isEqualTo("A");
        assertThat(r1.get(1).toLong()).isEqualTo(3L);

        Row r2 = exec.next();
        assertThat(r2).isNotNull();
        assertThat(r2.get(0).toStringValue()).isEqualTo("B");
        assertThat(r2.get(1).toLong()).isEqualTo(2L);

        assertThat(exec.next()).isNull();
        exec.close();
    }

    @Test
    @DisplayName("grouped SUM")
    void groupedSum() throws Exception {
        List<Row> rows = List.of(
                new Row(new Datum[]{Datum.of("A"), Datum.of(100L)}),
                new Row(new Datum[]{Datum.of("A"), Datum.of(200L)}),
                new Row(new Datum[]{Datum.of("B"), Datum.of(50L)})
        );

        Expression deptRef = new ColumnRef(null, "dept", 0, DataType.VARCHAR);
        Expression salaryRef = new ColumnRef(null, "salary", 1, DataType.BIGINT);
        AggFunction sumAgg = AggFunctions.create(AggFunction.Type.SUM, salaryRef, false);

        List<ColumnInfo> outputSchema = List.of(col("dept", DataType.VARCHAR), col("total", DataType.BIGINT));
        StreamAggExecutor exec = new StreamAggExecutor(
                new ListExecutor(rows, inputSchema),
                List.of(deptRef), List.of(sumAgg), outputSchema, evalCtx);
        exec.open();

        Row r1 = exec.next();
        assertThat(r1.get(0).toStringValue()).isEqualTo("A");
        assertThat(r1.get(1).toLong()).isEqualTo(300L);

        Row r2 = exec.next();
        assertThat(r2.get(0).toStringValue()).isEqualTo("B");
        assertThat(r2.get(1).toLong()).isEqualTo(50L);

        assertThat(exec.next()).isNull();
        exec.close();
    }

    @Test
    @DisplayName("scalar aggregation (no GROUP BY)")
    void scalarAgg() throws Exception {
        List<Row> rows = List.of(
                new Row(new Datum[]{Datum.of("A"), Datum.of(10L)}),
                new Row(new Datum[]{Datum.of("B"), Datum.of(20L)}),
                new Row(new Datum[]{Datum.of("C"), Datum.of(30L)})
        );

        AggFunction countAgg = AggFunctions.create(AggFunction.Type.COUNT, null, false);
        List<ColumnInfo> outputSchema = List.of(col("cnt", DataType.BIGINT));

        StreamAggExecutor exec = new StreamAggExecutor(
                new ListExecutor(rows, inputSchema),
                List.of(), List.of(countAgg), outputSchema, evalCtx);
        exec.open();

        Row r = exec.next();
        assertThat(r).isNotNull();
        assertThat(r.get(0).toLong()).isEqualTo(3L);

        assertThat(exec.next()).isNull();
        exec.close();
    }

    @Test
    @DisplayName("scalar aggregation on empty input returns default")
    void scalarAggEmpty() throws Exception {
        AggFunction countAgg = AggFunctions.create(AggFunction.Type.COUNT, null, false);
        List<ColumnInfo> outputSchema = List.of(col("cnt", DataType.BIGINT));

        StreamAggExecutor exec = new StreamAggExecutor(
                new ListExecutor(List.of(), inputSchema),
                List.of(), List.of(countAgg), outputSchema, evalCtx);
        exec.open();

        Row r = exec.next();
        assertThat(r).isNotNull();
        assertThat(r.get(0).toLong()).isEqualTo(0L);

        assertThat(exec.next()).isNull();
        exec.close();
    }

    @Test
    @DisplayName("grouped aggregation on empty input returns nothing")
    void groupedAggEmpty() throws Exception {
        Expression deptRef = new ColumnRef(null, "dept", 0, DataType.VARCHAR);
        AggFunction countAgg = AggFunctions.create(AggFunction.Type.COUNT, null, false);
        List<ColumnInfo> outputSchema = List.of(col("dept", DataType.VARCHAR), col("cnt", DataType.BIGINT));

        StreamAggExecutor exec = new StreamAggExecutor(
                new ListExecutor(List.of(), inputSchema),
                List.of(deptRef), List.of(countAgg), outputSchema, evalCtx);
        exec.open();

        assertThat(exec.next()).isNull();
        exec.close();
    }

    @Test
    @DisplayName("single group returns one row")
    void singleGroup() throws Exception {
        List<Row> rows = List.of(
                new Row(new Datum[]{Datum.of("X"), Datum.of(10L)}),
                new Row(new Datum[]{Datum.of("X"), Datum.of(20L)})
        );

        Expression deptRef = new ColumnRef(null, "dept", 0, DataType.VARCHAR);
        AggFunction countAgg = AggFunctions.create(AggFunction.Type.COUNT, null, false);
        List<ColumnInfo> outputSchema = List.of(col("dept", DataType.VARCHAR), col("cnt", DataType.BIGINT));

        StreamAggExecutor exec = new StreamAggExecutor(
                new ListExecutor(rows, inputSchema),
                List.of(deptRef), List.of(countAgg), outputSchema, evalCtx);
        exec.open();

        Row r = exec.next();
        assertThat(r.get(0).toStringValue()).isEqualTo("X");
        assertThat(r.get(1).toLong()).isEqualTo(2L);

        assertThat(exec.next()).isNull();
        exec.close();
    }

    @Test
    @DisplayName("outputSchema returns configured columns")
    void outputSchema() {
        List<ColumnInfo> out = List.of(col("dept", DataType.VARCHAR), col("cnt", DataType.BIGINT));
        StreamAggExecutor exec = new StreamAggExecutor(
                new ListExecutor(List.of(), inputSchema),
                List.of(), List.of(), out, evalCtx);
        assertThat(exec.outputSchema()).isEqualTo(out);
    }
}
