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
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.github.xinfra.lab.xdb.executor.ListExecutor.col;
import static org.assertj.core.api.Assertions.assertThat;

class HashAggExecutorTest {

    private final EvalContext evalCtx = new EvalContext();

    // Schema: dept (VARCHAR), salary (INT)
    private final List<ColumnInfo> schema = List.of(
            col("dept", DataType.VARCHAR),
            col("salary", DataType.INT)
    );

    private List<Row> sampleRows() {
        List<Row> rows = new ArrayList<>();
        rows.add(new Row(new Datum[]{Datum.of("Engineering"), Datum.of(50000L)}));
        rows.add(new Row(new Datum[]{Datum.of("Engineering"), Datum.of(60000L)}));
        rows.add(new Row(new Datum[]{Datum.of("Engineering"), Datum.of(70000L)}));
        rows.add(new Row(new Datum[]{Datum.of("Marketing"), Datum.of(45000L)}));
        rows.add(new Row(new Datum[]{Datum.of("Marketing"), Datum.of(55000L)}));
        return rows;
    }

    @Test
    void scalarCountStar() throws Exception {
        // SELECT COUNT(*)
        // COUNT(*) with null arg
        AggFunction countStar = AggFunctions.create(AggFunction.Type.COUNT, null, false);
        List<ColumnInfo> outputCols = List.of(col("count", DataType.BIGINT));

        ListExecutor child = new ListExecutor(sampleRows(), schema);
        HashAggExecutor agg = new HashAggExecutor(child, List.of(), List.of(countStar), outputCols, evalCtx);
        agg.open();

        Row row = agg.next();
        assertThat(row).isNotNull();
        assertThat(row.get(0).toLong()).isEqualTo(5L);

        assertThat(agg.next()).isNull();
        agg.close();
    }

    @Test
    void scalarSum() throws Exception {
        // SELECT SUM(salary)
        Expression salaryRef = new ColumnRef(null, "salary", 1, DataType.INT);
        AggFunction sumAgg = AggFunctions.create(AggFunction.Type.SUM, salaryRef, false);
        List<ColumnInfo> outputCols = List.of(col("sum_salary", DataType.DECIMAL));

        ListExecutor child = new ListExecutor(sampleRows(), schema);
        HashAggExecutor agg = new HashAggExecutor(child, List.of(), List.of(sumAgg), outputCols, evalCtx);
        agg.open();

        Row row = agg.next();
        assertThat(row).isNotNull();
        BigDecimal sum = ((Datum.DecimalDatum) row.get(0)).value();
        assertThat(sum).isEqualByComparingTo(new BigDecimal("280000"));

        assertThat(agg.next()).isNull();
        agg.close();
    }

    @Test
    void scalarAvg() throws Exception {
        // SELECT AVG(salary)
        Expression salaryRef = new ColumnRef(null, "salary", 1, DataType.INT);
        AggFunction avgAgg = AggFunctions.create(AggFunction.Type.AVG, salaryRef, false);
        List<ColumnInfo> outputCols = List.of(col("avg_salary", DataType.DECIMAL));

        ListExecutor child = new ListExecutor(sampleRows(), schema);
        HashAggExecutor agg = new HashAggExecutor(child, List.of(), List.of(avgAgg), outputCols, evalCtx);
        agg.open();

        Row row = agg.next();
        assertThat(row).isNotNull();
        BigDecimal avg = ((Datum.DecimalDatum) row.get(0)).value();
        // 280000 / 5 = 56000
        assertThat(avg).isEqualByComparingTo(new BigDecimal("56000"));

        assertThat(agg.next()).isNull();
        agg.close();
    }

    @Test
    void scalarMin() throws Exception {
        // SELECT MIN(salary)
        Expression salaryRef = new ColumnRef(null, "salary", 1, DataType.INT);
        AggFunction minAgg = AggFunctions.create(AggFunction.Type.MIN, salaryRef, false);
        List<ColumnInfo> outputCols = List.of(col("min_salary", DataType.INT));

        ListExecutor child = new ListExecutor(sampleRows(), schema);
        HashAggExecutor agg = new HashAggExecutor(child, List.of(), List.of(minAgg), outputCols, evalCtx);
        agg.open();

        Row row = agg.next();
        assertThat(row).isNotNull();
        assertThat(row.get(0).toLong()).isEqualTo(45000L);

        assertThat(agg.next()).isNull();
        agg.close();
    }

    @Test
    void scalarMax() throws Exception {
        // SELECT MAX(salary)
        Expression salaryRef = new ColumnRef(null, "salary", 1, DataType.INT);
        AggFunction maxAgg = AggFunctions.create(AggFunction.Type.MAX, salaryRef, false);
        List<ColumnInfo> outputCols = List.of(col("max_salary", DataType.INT));

        ListExecutor child = new ListExecutor(sampleRows(), schema);
        HashAggExecutor agg = new HashAggExecutor(child, List.of(), List.of(maxAgg), outputCols, evalCtx);
        agg.open();

        Row row = agg.next();
        assertThat(row).isNotNull();
        assertThat(row.get(0).toLong()).isEqualTo(70000L);

        assertThat(agg.next()).isNull();
        agg.close();
    }

    @Test
    void groupByWithCount() throws Exception {
        // SELECT dept, COUNT(*) FROM ... GROUP BY dept
        Expression deptRef = new ColumnRef(null, "dept", 0, DataType.VARCHAR);
        AggFunction countStar = AggFunctions.create(AggFunction.Type.COUNT, null, false);
        List<ColumnInfo> outputCols = List.of(
                col("dept", DataType.VARCHAR),
                col("count", DataType.BIGINT)
        );

        ListExecutor child = new ListExecutor(sampleRows(), schema);
        HashAggExecutor agg = new HashAggExecutor(
                child, List.of(deptRef), List.of(countStar), outputCols, evalCtx
        );
        agg.open();

        Map<String, Long> results = new HashMap<>();
        Row row;
        while ((row = agg.next()) != null) {
            String dept = ((Datum.StringDatum) row.get(0)).value();
            long count = row.get(1).toLong();
            results.put(dept, count);
        }

        assertThat(results).hasSize(2);
        assertThat(results.get("Engineering")).isEqualTo(3L);
        assertThat(results.get("Marketing")).isEqualTo(2L);
        agg.close();
    }

    @Test
    void groupByWithSum() throws Exception {
        // SELECT dept, SUM(salary) FROM ... GROUP BY dept
        Expression deptRef = new ColumnRef(null, "dept", 0, DataType.VARCHAR);
        Expression salaryRef = new ColumnRef(null, "salary", 1, DataType.INT);
        AggFunction sumAgg = AggFunctions.create(AggFunction.Type.SUM, salaryRef, false);
        List<ColumnInfo> outputCols = List.of(
                col("dept", DataType.VARCHAR),
                col("sum_salary", DataType.DECIMAL)
        );

        ListExecutor child = new ListExecutor(sampleRows(), schema);
        HashAggExecutor agg = new HashAggExecutor(
                child, List.of(deptRef), List.of(sumAgg), outputCols, evalCtx
        );
        agg.open();

        Map<String, BigDecimal> results = new HashMap<>();
        Row row;
        while ((row = agg.next()) != null) {
            String dept = ((Datum.StringDatum) row.get(0)).value();
            BigDecimal sum = ((Datum.DecimalDatum) row.get(1)).value();
            results.put(dept, sum);
        }

        assertThat(results).hasSize(2);
        assertThat(results.get("Engineering")).isEqualByComparingTo(new BigDecimal("180000"));
        assertThat(results.get("Marketing")).isEqualByComparingTo(new BigDecimal("100000"));
        agg.close();
    }

    @Test
    void groupByWithMultipleAggregates() throws Exception {
        // SELECT dept, COUNT(*), MIN(salary), MAX(salary) FROM ... GROUP BY dept
        Expression deptRef = new ColumnRef(null, "dept", 0, DataType.VARCHAR);
        Expression salaryRef = new ColumnRef(null, "salary", 1, DataType.INT);

        AggFunction countStar = AggFunctions.create(AggFunction.Type.COUNT, null, false);
        AggFunction minAgg = AggFunctions.create(AggFunction.Type.MIN, salaryRef, false);
        AggFunction maxAgg = AggFunctions.create(AggFunction.Type.MAX, salaryRef, false);

        List<ColumnInfo> outputCols = List.of(
                col("dept", DataType.VARCHAR),
                col("count", DataType.BIGINT),
                col("min_salary", DataType.INT),
                col("max_salary", DataType.INT)
        );

        ListExecutor child = new ListExecutor(sampleRows(), schema);
        HashAggExecutor agg = new HashAggExecutor(
                child, List.of(deptRef),
                List.of(countStar, minAgg, maxAgg),
                outputCols, evalCtx
        );
        agg.open();

        Map<String, Row> results = new HashMap<>();
        Row row;
        while ((row = agg.next()) != null) {
            String dept = ((Datum.StringDatum) row.get(0)).value();
            results.put(dept, row);
        }

        assertThat(results).hasSize(2);

        Row eng = results.get("Engineering");
        assertThat(eng.get(1).toLong()).isEqualTo(3L);    // count
        assertThat(eng.get(2).toLong()).isEqualTo(50000L); // min
        assertThat(eng.get(3).toLong()).isEqualTo(70000L); // max

        Row mkt = results.get("Marketing");
        assertThat(mkt.get(1).toLong()).isEqualTo(2L);    // count
        assertThat(mkt.get(2).toLong()).isEqualTo(45000L); // min
        assertThat(mkt.get(3).toLong()).isEqualTo(55000L); // max

        agg.close();
    }

    @Test
    void scalarAggOnEmptyInput() throws Exception {
        // SELECT COUNT(*) FROM empty_table -> should return 1 row with count=0
        AggFunction countStar = AggFunctions.create(AggFunction.Type.COUNT, null, false);
        List<ColumnInfo> outputCols = List.of(col("count", DataType.BIGINT));

        ListExecutor child = new ListExecutor(List.of(), schema);
        HashAggExecutor agg = new HashAggExecutor(child, List.of(), List.of(countStar), outputCols, evalCtx);
        agg.open();

        Row row = agg.next();
        assertThat(row).isNotNull();
        assertThat(row.get(0).toLong()).isEqualTo(0L);

        assertThat(agg.next()).isNull();
        agg.close();
    }

    @Test
    void groupByOnEmptyInput() throws Exception {
        // SELECT dept, COUNT(*) FROM empty_table GROUP BY dept -> no rows
        Expression deptRef = new ColumnRef(null, "dept", 0, DataType.VARCHAR);
        AggFunction countStar = AggFunctions.create(AggFunction.Type.COUNT, null, false);
        List<ColumnInfo> outputCols = List.of(
                col("dept", DataType.VARCHAR),
                col("count", DataType.BIGINT)
        );

        ListExecutor child = new ListExecutor(List.of(), schema);
        HashAggExecutor agg = new HashAggExecutor(
                child, List.of(deptRef), List.of(countStar), outputCols, evalCtx
        );
        agg.open();

        // GROUP BY on empty input produces no groups
        assertThat(agg.next()).isNull();
        agg.close();
    }

    @Test
    void sumOnEmptyInputReturnsNull() throws Exception {
        // SELECT SUM(salary) FROM empty_table -> should return NULL
        Expression salaryRef = new ColumnRef(null, "salary", 1, DataType.INT);
        AggFunction sumAgg = AggFunctions.create(AggFunction.Type.SUM, salaryRef, false);
        List<ColumnInfo> outputCols = List.of(col("sum_salary", DataType.DECIMAL));

        ListExecutor child = new ListExecutor(List.of(), schema);
        HashAggExecutor agg = new HashAggExecutor(child, List.of(), List.of(sumAgg), outputCols, evalCtx);
        agg.open();

        Row row = agg.next();
        assertThat(row).isNotNull();
        assertThat(row.get(0).isNull()).isTrue();

        assertThat(agg.next()).isNull();
        agg.close();
    }

    @Test
    void avgWithGroupBy() throws Exception {
        // SELECT dept, AVG(salary) FROM ... GROUP BY dept
        Expression deptRef = new ColumnRef(null, "dept", 0, DataType.VARCHAR);
        Expression salaryRef = new ColumnRef(null, "salary", 1, DataType.INT);
        AggFunction avgAgg = AggFunctions.create(AggFunction.Type.AVG, salaryRef, false);

        List<ColumnInfo> outputCols = List.of(
                col("dept", DataType.VARCHAR),
                col("avg_salary", DataType.DECIMAL)
        );

        ListExecutor child = new ListExecutor(sampleRows(), schema);
        HashAggExecutor agg = new HashAggExecutor(
                child, List.of(deptRef), List.of(avgAgg), outputCols, evalCtx
        );
        agg.open();

        Map<String, BigDecimal> results = new HashMap<>();
        Row row;
        while ((row = agg.next()) != null) {
            String dept = ((Datum.StringDatum) row.get(0)).value();
            BigDecimal avg = ((Datum.DecimalDatum) row.get(1)).value();
            results.put(dept, avg);
        }

        assertThat(results).hasSize(2);
        // Engineering: (50000+60000+70000)/3 = 60000
        assertThat(results.get("Engineering")).isEqualByComparingTo(new BigDecimal("60000"));
        // Marketing: (45000+55000)/2 = 50000
        assertThat(results.get("Marketing")).isEqualByComparingTo(new BigDecimal("50000"));
        agg.close();
    }

    @Test
    void outputSchemaIsCorrect() {
        Expression deptRef = new ColumnRef(null, "dept", 0, DataType.VARCHAR);
        AggFunction countStar = AggFunctions.create(AggFunction.Type.COUNT, null, false);
        List<ColumnInfo> outputCols = List.of(
                col("dept", DataType.VARCHAR),
                col("count", DataType.BIGINT)
        );

        ListExecutor child = new ListExecutor(List.of(), schema);
        HashAggExecutor agg = new HashAggExecutor(
                child, List.of(deptRef), List.of(countStar), outputCols, evalCtx
        );

        assertThat(agg.outputSchema()).hasSize(2);
        assertThat(agg.outputSchema().get(0).getName()).isEqualTo("dept");
        assertThat(agg.outputSchema().get(1).getName()).isEqualTo("count");
    }
}
