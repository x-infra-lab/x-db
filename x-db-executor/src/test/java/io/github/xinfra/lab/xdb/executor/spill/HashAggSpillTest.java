package io.github.xinfra.lab.xdb.executor.spill;

import io.github.xinfra.lab.xdb.common.MemoryTracker;
import io.github.xinfra.lab.xdb.executor.ListExecutor;
import io.github.xinfra.lab.xdb.executor.rel.HashAggExecutor;
import io.github.xinfra.lab.xdb.expression.AggFunction;
import io.github.xinfra.lab.xdb.expression.AggFunctions;
import io.github.xinfra.lab.xdb.expression.ColumnRef;
import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.xinfra.lab.xdb.executor.ListExecutor.col;
import static org.assertj.core.api.Assertions.assertThat;

class HashAggSpillTest {

    private final EvalContext evalCtx = new EvalContext();

    private final List<ColumnInfo> schema = List.of(
            col("dept", DataType.VARCHAR),
            col("salary", DataType.INT)
    );

    private List<Row> generateRows(int count, int numDepts) {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            String dept = "dept-" + (i % numDepts);
            long salary = (i + 1) * 100L;
            rows.add(new Row(new Datum[]{Datum.of(dept), Datum.of(salary)}));
        }
        return rows;
    }

    @Test
    void countWithSpill() throws Exception {
        int rowCount = 500;
        int numDepts = 10;
        List<Row> rows = generateRows(rowCount, numDepts);

        MemoryTracker tracker = new MemoryTracker("hash_agg", null, 300);

        Expression deptRef = new ColumnRef(null, "dept", 0, DataType.VARCHAR);
        AggFunction countStar = AggFunctions.create(AggFunction.Type.COUNT, null, false);
        List<ColumnInfo> outputCols = List.of(
                col("dept", DataType.VARCHAR),
                col("count", DataType.BIGINT)
        );

        ListExecutor child = new ListExecutor(rows, schema);
        HashAggExecutor agg = new HashAggExecutor(
                child, List.of(deptRef), List.of(countStar), outputCols, evalCtx, tracker);
        agg.open();

        Map<String, Long> results = new HashMap<>();
        Row row;
        while ((row = agg.next()) != null) {
            String dept = ((Datum.StringDatum) row.get(0)).value();
            long count = row.get(1).toLong();
            results.put(dept, count);
        }
        agg.close();

        assertThat(results).hasSize(numDepts);
        for (int i = 0; i < numDepts; i++) {
            assertThat(results.get("dept-" + i)).isEqualTo(rowCount / numDepts);
        }
    }

    @Test
    void sumWithSpill() throws Exception {
        int rowCount = 200;
        int numDepts = 5;
        List<Row> rows = generateRows(rowCount, numDepts);

        MemoryTracker tracker = new MemoryTracker("hash_agg", null, 200);

        Expression deptRef = new ColumnRef(null, "dept", 0, DataType.VARCHAR);
        Expression salaryRef = new ColumnRef(null, "salary", 1, DataType.INT);
        AggFunction sumAgg = AggFunctions.create(AggFunction.Type.SUM, salaryRef, false);
        List<ColumnInfo> outputCols = List.of(
                col("dept", DataType.VARCHAR),
                col("sum_salary", DataType.DECIMAL)
        );

        ListExecutor child = new ListExecutor(rows, schema);
        HashAggExecutor agg = new HashAggExecutor(
                child, List.of(deptRef), List.of(sumAgg), outputCols, evalCtx, tracker);
        agg.open();

        Map<String, BigDecimal> results = new HashMap<>();
        Row row;
        while ((row = agg.next()) != null) {
            String dept = ((Datum.StringDatum) row.get(0)).value();
            BigDecimal sum = ((Datum.DecimalDatum) row.get(1)).value();
            results.put(dept, sum);
        }
        agg.close();

        assertThat(results).hasSize(numDepts);

        // Verify total across all departments
        BigDecimal totalSum = results.values().stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Each row has salary (i+1)*100, total = sum(1..200)*100
        BigDecimal expected = BigDecimal.valueOf(200L * 201 / 2 * 100);
        assertThat(totalSum).isEqualByComparingTo(expected);
    }

    @Test
    void avgWithSpill() throws Exception {
        int rowCount = 300;
        int numDepts = 6;
        List<Row> rows = generateRows(rowCount, numDepts);

        MemoryTracker tracker = new MemoryTracker("hash_agg", null, 200);

        Expression deptRef = new ColumnRef(null, "dept", 0, DataType.VARCHAR);
        Expression salaryRef = new ColumnRef(null, "salary", 1, DataType.INT);
        AggFunction avgAgg = AggFunctions.create(AggFunction.Type.AVG, salaryRef, false);
        List<ColumnInfo> outputCols = List.of(
                col("dept", DataType.VARCHAR),
                col("avg_salary", DataType.DECIMAL)
        );

        ListExecutor child = new ListExecutor(rows, schema);
        HashAggExecutor agg = new HashAggExecutor(
                child, List.of(deptRef), List.of(avgAgg), outputCols, evalCtx, tracker);
        agg.open();

        Map<String, BigDecimal> results = new HashMap<>();
        Row row;
        while ((row = agg.next()) != null) {
            String dept = ((Datum.StringDatum) row.get(0)).value();
            BigDecimal avg = ((Datum.DecimalDatum) row.get(1)).value();
            results.put(dept, avg);
        }
        agg.close();

        assertThat(results).hasSize(numDepts);

        // Each dept has 50 rows. The averages should all be positive.
        for (BigDecimal avg : results.values()) {
            assertThat(avg).isGreaterThan(BigDecimal.ZERO);
        }
    }

    @Test
    void minMaxWithSpill() throws Exception {
        int rowCount = 200;
        int numDepts = 4;
        List<Row> rows = generateRows(rowCount, numDepts);

        MemoryTracker tracker = new MemoryTracker("hash_agg", null, 200);

        Expression deptRef = new ColumnRef(null, "dept", 0, DataType.VARCHAR);
        Expression salaryRef = new ColumnRef(null, "salary", 1, DataType.INT);
        AggFunction minAgg = AggFunctions.create(AggFunction.Type.MIN, salaryRef, false);
        AggFunction maxAgg = AggFunctions.create(AggFunction.Type.MAX, salaryRef, false);
        List<ColumnInfo> outputCols = List.of(
                col("dept", DataType.VARCHAR),
                col("min_salary", DataType.INT),
                col("max_salary", DataType.INT)
        );

        ListExecutor child = new ListExecutor(rows, schema);
        HashAggExecutor agg = new HashAggExecutor(
                child, List.of(deptRef), List.of(minAgg, maxAgg), outputCols, evalCtx, tracker);
        agg.open();

        Map<String, Row> results = new HashMap<>();
        Row row;
        while ((row = agg.next()) != null) {
            String dept = ((Datum.StringDatum) row.get(0)).value();
            results.put(dept, row);
        }
        agg.close();

        assertThat(results).hasSize(numDepts);

        for (int d = 0; d < numDepts; d++) {
            Row r = results.get("dept-" + d);
            long min = r.get(1).toLong();
            long max = r.get(2).toLong();
            assertThat(max).isGreaterThan(min);
        }
    }

    @Test
    void inMemoryAggStillWorks() throws Exception {
        List<Row> rows = generateRows(10, 3);

        MemoryTracker tracker = new MemoryTracker("hash_agg", null, MemoryTracker.UNLIMITED);

        Expression deptRef = new ColumnRef(null, "dept", 0, DataType.VARCHAR);
        AggFunction countStar = AggFunctions.create(AggFunction.Type.COUNT, null, false);
        List<ColumnInfo> outputCols = List.of(
                col("dept", DataType.VARCHAR),
                col("count", DataType.BIGINT)
        );

        ListExecutor child = new ListExecutor(rows, schema);
        HashAggExecutor agg = new HashAggExecutor(
                child, List.of(deptRef), List.of(countStar), outputCols, evalCtx, tracker);
        agg.open();

        Map<String, Long> results = new HashMap<>();
        Row row;
        while ((row = agg.next()) != null) {
            String dept = ((Datum.StringDatum) row.get(0)).value();
            results.put(dept, row.get(1).toLong());
        }
        agg.close();

        assertThat(results).hasSize(3);
    }

    @Test
    void scalarAggWithSpill() throws Exception {
        int rowCount = 200;
        List<Row> rows = generateRows(rowCount, 5);

        MemoryTracker tracker = new MemoryTracker("hash_agg", null, 200);

        AggFunction countStar = AggFunctions.create(AggFunction.Type.COUNT, null, false);
        List<ColumnInfo> outputCols = List.of(col("count", DataType.BIGINT));

        ListExecutor child = new ListExecutor(rows, schema);
        HashAggExecutor agg = new HashAggExecutor(
                child, List.of(), List.of(countStar), outputCols, evalCtx, tracker);
        agg.open();

        Row row = agg.next();
        assertThat(row).isNotNull();
        assertThat(row.get(0).toLong()).isEqualTo(rowCount);

        assertThat(agg.next()).isNull();
        agg.close();
    }

    @Test
    void emptyInputWithSpill() throws Exception {
        MemoryTracker tracker = new MemoryTracker("hash_agg", null, 100);

        AggFunction countStar = AggFunctions.create(AggFunction.Type.COUNT, null, false);
        List<ColumnInfo> outputCols = List.of(col("count", DataType.BIGINT));

        ListExecutor child = new ListExecutor(List.of(), schema);
        HashAggExecutor agg = new HashAggExecutor(
                child, List.of(), List.of(countStar), outputCols, evalCtx, tracker);
        agg.open();

        Row row = agg.next();
        assertThat(row).isNotNull();
        assertThat(row.get(0).toLong()).isEqualTo(0L);

        assertThat(agg.next()).isNull();
        agg.close();
    }
}
