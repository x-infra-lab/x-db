package io.github.xinfra.lab.xdb.planner.cost;

import io.github.xinfra.lab.xdb.expression.Datum;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class StatsSerializerTest {

    @Test
    void roundTrip_basicStats() {
        TableStatistics stats = new TableStatistics(1000, 8192);
        ColumnStatistics cs = new ColumnStatistics(50, Datum.of(1L), Datum.of(100L), 10, 1000);
        stats.putColumnStat("age", cs);

        byte[] data = StatsSerializer.serialize(stats);
        TableStatistics deserialized = StatsSerializer.deserialize(data);

        assertThat(deserialized.getRowCount()).isEqualTo(1000);
        assertThat(deserialized.getDataSize()).isEqualTo(8192);
        assertThat(deserialized.getColumnStat("age")).isNotNull();
        assertThat(deserialized.getColumnStat("age").getNdv()).isEqualTo(50);
        assertThat(deserialized.getColumnStat("age").getNullCount()).isEqualTo(10);
    }

    @Test
    void roundTrip_withHistogram() {
        List<Datum> values = new ArrayList<>();
        for (int i = 1; i <= 100; i++) values.add(Datum.of((long) i));
        Histogram histogram = Histogram.buildFromSorted(values, 10);

        TableStatistics stats = new TableStatistics(100, 1024);
        ColumnStatistics cs = new ColumnStatistics(100, Datum.of(1L), Datum.of(100L), 0, 100);
        cs.setHistogram(histogram);
        stats.putColumnStat("id", cs);

        byte[] data = StatsSerializer.serialize(stats);
        TableStatistics deserialized = StatsSerializer.deserialize(data);

        ColumnStatistics dcs = deserialized.getColumnStat("id");
        assertThat(dcs.getHistogram()).isNotNull();
        assertThat(dcs.getHistogram().getTotalCount()).isEqualTo(100);
        assertThat(dcs.getHistogram().getNdv()).isEqualTo(100);
        assertThat(dcs.getHistogram().getBuckets()).isNotEmpty();
    }

    @Test
    void roundTrip_differentDatumTypes() {
        TableStatistics stats = new TableStatistics(50, 512);

        ColumnStatistics intCol = new ColumnStatistics(10, Datum.of(1L), Datum.of(100L), 0, 50);
        stats.putColumnStat("int_col", intCol);

        ColumnStatistics dblCol = new ColumnStatistics(10, Datum.of(1.5), Datum.of(99.5), 0, 50);
        stats.putColumnStat("dbl_col", dblCol);

        ColumnStatistics strCol = new ColumnStatistics(5, Datum.of("alice"), Datum.of("zoe"), 2, 50);
        stats.putColumnStat("str_col", strCol);

        ColumnStatistics decCol = new ColumnStatistics(10,
                Datum.of(new BigDecimal("0.01")), Datum.of(new BigDecimal("999.99")), 0, 50);
        stats.putColumnStat("dec_col", decCol);

        ColumnStatistics dtCol = new ColumnStatistics(10,
                Datum.of(LocalDateTime.of(2024, 1, 1, 0, 0)),
                Datum.of(LocalDateTime.of(2024, 12, 31, 23, 59)), 0, 50);
        stats.putColumnStat("dt_col", dtCol);

        byte[] data = StatsSerializer.serialize(stats);
        TableStatistics d = StatsSerializer.deserialize(data);

        assertThat(d.getColumnStat("int_col").getMinValue()).isInstanceOf(Datum.IntDatum.class);
        assertThat(d.getColumnStat("dbl_col").getMinValue()).isInstanceOf(Datum.DoubleDatum.class);
        assertThat(d.getColumnStat("str_col").getMinValue()).isInstanceOf(Datum.StringDatum.class);
        assertThat(d.getColumnStat("dec_col").getMinValue()).isInstanceOf(Datum.DecimalDatum.class);
        assertThat(d.getColumnStat("dt_col").getMinValue()).isInstanceOf(Datum.DateTimeDatum.class);
    }

    @Test
    void roundTrip_emptyStats() {
        TableStatistics stats = new TableStatistics(0, 0);
        byte[] data = StatsSerializer.serialize(stats);
        TableStatistics d = StatsSerializer.deserialize(data);
        assertThat(d.getRowCount()).isEqualTo(0);
        assertThat(d.getColumnStats()).isEmpty();
    }

    @Test
    void roundTrip_nullMinMax() {
        TableStatistics stats = new TableStatistics(10, 100);
        ColumnStatistics cs = new ColumnStatistics(0, null, null, 10, 10);
        stats.putColumnStat("all_nulls", cs);

        byte[] data = StatsSerializer.serialize(stats);
        TableStatistics d = StatsSerializer.deserialize(data);
        assertThat(d.getColumnStat("all_nulls").getNullCount()).isEqualTo(10);
    }
}
