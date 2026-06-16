package io.github.xinfra.lab.xdb.planner.cost;

import io.github.xinfra.lab.xdb.expression.BinaryOp;
import io.github.xinfra.lab.xdb.expression.ColumnRef;
import io.github.xinfra.lab.xdb.expression.Constant;
import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.meta.TableInfo;
import io.github.xinfra.lab.xdb.planner.physical.PhysicalTableScan;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class CostEstimatorTest {

    private StatsStore statsStore;
    private CostEstimator estimator;
    private TableInfo table;

    @BeforeEach
    void setUp() {
        statsStore = new StatsStore() {};
        estimator = new CostEstimator(statsStore);

        table = new TableInfo();
        table.setId(1L);
        table.setName("users");
        ColumnInfo col = new ColumnInfo();
        col.setId(1L);
        col.setName("age");
        col.setType(DataType.INT);
        table.setColumns(List.of(col));
    }

    @Test
    void noStats_defaultRowEstimate() {
        long rows = estimator.estimateTableScanRows(table, List.of());
        assertThat(rows).isEqualTo(10000);
    }

    @Test
    void withStats_noConditions_returnsFullRowCount() {
        TableStatistics ts = new TableStatistics(50000, 1024);
        statsStore.putTableStats(1L, ts);

        long rows = estimator.estimateTableScanRows(table, List.of());
        assertThat(rows).isEqualTo(50000);
    }

    @Test
    void withStats_equalityCondition_usesNdvSelectivity() {
        TableStatistics ts = new TableStatistics(10000, 1024);
        ColumnStatistics cs = new ColumnStatistics(100, Datum.of(1L), Datum.of(100L), 0, 10000);
        ts.putColumnStat("age", cs);
        statsStore.putTableStats(1L, ts);

        Expression cond = new BinaryOp(
                new ColumnRef(null, "age", 0, DataType.INT),
                BinaryOp.Op.EQ,
                new Constant(Datum.of(25L), DataType.INT));

        long rows = estimator.estimateTableScanRows(table, List.of(cond));
        assertThat(rows).isEqualTo(100); // 10000 * (1/100) = 100
    }

    @Test
    void withHistogram_betterEstimate() {
        List<Datum> values = new ArrayList<>();
        for (int i = 1; i <= 1000; i++) values.add(Datum.of((long) i));
        Histogram histogram = Histogram.buildFromSorted(values, 50);

        TableStatistics ts = new TableStatistics(1000, 1024);
        ColumnStatistics cs = new ColumnStatistics(1000, Datum.of(1L), Datum.of(1000L), 0, 1000);
        cs.setHistogram(histogram);
        ts.putColumnStat("age", cs);
        statsStore.putTableStats(1L, ts);

        Expression eqCond = new BinaryOp(
                new ColumnRef(null, "age", 0, DataType.INT),
                BinaryOp.Op.EQ,
                new Constant(Datum.of(500L), DataType.INT));

        long rows = estimator.estimateTableScanRows(table, List.of(eqCond));
        assertThat(rows).isGreaterThanOrEqualTo(1).isLessThanOrEqualTo(10);
    }

    @Test
    void selectivity_multipleConditions_multiply() {
        TableStatistics ts = new TableStatistics(10000, 1024);
        ColumnStatistics cs = new ColumnStatistics(100, Datum.of(1L), Datum.of(100L), 0, 10000);
        ts.putColumnStat("age", cs);
        statsStore.putTableStats(1L, ts);

        Expression cond1 = new BinaryOp(
                new ColumnRef(null, "age", 0, DataType.INT),
                BinaryOp.Op.EQ,
                new Constant(Datum.of(25L), DataType.INT));
        Expression cond2 = new BinaryOp(
                new ColumnRef(null, "age", 0, DataType.INT),
                BinaryOp.Op.EQ,
                new Constant(Datum.of(30L), DataType.INT));

        double sel = estimator.estimateSelectivity(List.of(cond1, cond2), ts);
        assertThat(sel).isCloseTo(0.0001, within(0.001));
    }

    @Test
    void tableScanCost_proportionalToRows() {
        TableStatistics ts = new TableStatistics(5000, 1024);
        statsStore.putTableStats(1L, ts);

        PhysicalTableScan scan = new PhysicalTableScan(table, null,
                table.getColumns(), List.of());

        double cost = estimator.estimateCost(scan);
        assertThat(cost).isEqualTo(5000 * 2.0);
    }
}
