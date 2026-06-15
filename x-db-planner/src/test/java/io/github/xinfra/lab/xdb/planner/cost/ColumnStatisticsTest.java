package io.github.xinfra.lab.xdb.planner.cost;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class ColumnStatisticsTest {

    @Test
    @DisplayName("Equality selectivity uses NDV")
    void equalitySelectivityUsesNdv() {
        ColumnStatistics cs = new ColumnStatistics(100, null, null, 0, 1000);
        assertThat(cs.equalitySelectivity()).isCloseTo(0.01, within(0.0001));
    }

    @Test
    @DisplayName("Equality selectivity falls back to 0.33 when NDV is 0")
    void equalitySelectivityFallback() {
        ColumnStatistics cs = new ColumnStatistics(0, null, null, 0, 0);
        assertThat(cs.equalitySelectivity()).isCloseTo(0.33, within(0.0001));
    }

    @Test
    @DisplayName("Null fraction is computed correctly")
    void nullFraction() {
        ColumnStatistics cs = new ColumnStatistics(50, null, null, 200, 1000);
        assertThat(cs.nullFraction()).isCloseTo(0.2, within(0.0001));
    }

    @Test
    @DisplayName("Null fraction is 0 when totalCount is 0")
    void nullFractionZeroTotal() {
        ColumnStatistics cs = new ColumnStatistics(0, null, null, 0, 0);
        assertThat(cs.nullFraction()).isCloseTo(0.0, within(0.0001));
    }

    @Test
    @DisplayName("Statistics equality selectivity uses column stats")
    void statisticsEqualitySelectivity() {
        Statistics stats = new Statistics(1000, 10000, 5);
        ColumnStatistics cs = new ColumnStatistics(200, null, null, 0, 1000);
        stats.setColumnStats(java.util.Map.of("name", cs));
        assertThat(stats.equalitySelectivity("name")).isCloseTo(0.005, within(0.0001));
    }

    @Test
    @DisplayName("Statistics equality selectivity falls back without column stats")
    void statisticsEqualitySelectivityFallback() {
        Statistics stats = new Statistics(1000, 10000, 5);
        assertThat(stats.equalitySelectivity("missing")).isCloseTo(0.33, within(0.0001));
    }

    @Test
    @DisplayName("Statistics range selectivity uses NDV")
    void statisticsRangeSelectivity() {
        Statistics stats = new Statistics(1000, 10000, 5);
        ColumnStatistics cs = new ColumnStatistics(100, null, null, 0, 1000);
        stats.setColumnStats(java.util.Map.of("age", cs));
        assertThat(stats.rangeSelectivity("age")).isCloseTo(0.03, within(0.0001));
    }

    @Test
    @DisplayName("TableStatistics stores and retrieves column stats")
    void tableStatisticsColumnStats() {
        TableStatistics ts = new TableStatistics(5000, 100000);
        ColumnStatistics cs = new ColumnStatistics(50, null, null, 10, 5000);
        ts.putColumnStat("name", cs);

        assertThat(ts.getRowCount()).isEqualTo(5000);
        assertThat(ts.getColumnStat("name")).isNotNull();
        assertThat(ts.getColumnStat("name").getNdv()).isEqualTo(50);
        assertThat(ts.getColumnStat("NAME")).isNotNull();
    }

    @Test
    @DisplayName("StatsStore get/put roundtrip")
    void statsStoreRoundTrip() {
        StatsStore store = new StatsStore();
        assertThat(store.getTableStats(99)).isNull();

        TableStatistics stats = new TableStatistics(100, 1000);
        store.putTableStats(99, stats);
        assertThat(store.getTableStats(99)).isSameAs(stats);

        store.removeTableStats(99);
        assertThat(store.getTableStats(99)).isNull();
    }
}
