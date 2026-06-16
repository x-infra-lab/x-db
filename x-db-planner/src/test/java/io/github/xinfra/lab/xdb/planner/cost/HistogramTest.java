package io.github.xinfra.lab.xdb.planner.cost;

import io.github.xinfra.lab.xdb.expression.Datum;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.*;

class HistogramTest {

    @Test
    void buildFromSorted_emptyValues() {
        Histogram h = Histogram.buildFromSorted(List.of(), 10);
        assertThat(h.getBuckets()).isEmpty();
        assertThat(h.getTotalCount()).isEqualTo(0);
        assertThat(h.getNdv()).isEqualTo(0);
    }

    @Test
    void buildFromSorted_singleValue() {
        Histogram h = Histogram.buildFromSorted(List.of(Datum.of(42L)), 10);
        assertThat(h.getBuckets()).hasSize(1);
        assertThat(h.getTotalCount()).isEqualTo(1);
        assertThat(h.getNdv()).isEqualTo(1);
        assertThat(h.getBuckets().get(0).count()).isEqualTo(1);
        assertThat(h.getBuckets().get(0).repeats()).isEqualTo(1);
    }

    @Test
    void buildFromSorted_uniformDistribution() {
        List<Datum> values = new ArrayList<>();
        for (int i = 1; i <= 100; i++) {
            values.add(Datum.of((long) i));
        }
        Histogram h = Histogram.buildFromSorted(values, 10);
        assertThat(h.getTotalCount()).isEqualTo(100);
        assertThat(h.getNdv()).isEqualTo(100);
        assertThat(h.getBuckets()).isNotEmpty();

        long totalBucketCount = h.getBuckets().stream()
                .mapToLong(Histogram.Bucket::count).sum();
        assertThat(totalBucketCount).isEqualTo(100);
    }

    @Test
    void buildFromSorted_withDuplicates() {
        List<Datum> values = new ArrayList<>();
        for (int i = 0; i < 50; i++) values.add(Datum.of(1L));
        for (int i = 0; i < 50; i++) values.add(Datum.of(2L));
        Histogram h = Histogram.buildFromSorted(values, 4);
        assertThat(h.getTotalCount()).isEqualTo(100);
        assertThat(h.getNdv()).isEqualTo(2);
    }

    @Test
    void estimateEqual_matchesUpperBound() {
        List<Datum> values = new ArrayList<>();
        for (int i = 1; i <= 100; i++) values.add(Datum.of((long) i));
        Histogram h = Histogram.buildFromSorted(values, 10);

        Datum lastBucketUpper = h.getBuckets().get(h.getBuckets().size() - 1).upperBound();
        double sel = h.estimateEqual(lastBucketUpper);
        assertThat(sel).isGreaterThan(0.0).isLessThanOrEqualTo(1.0);
    }

    @Test
    void estimateEqual_nullValue() {
        List<Datum> values = List.of(Datum.of(1L), Datum.of(2L), Datum.of(3L));
        Histogram h = Histogram.buildFromSorted(values, 2);
        assertThat(h.estimateEqual(Datum.nil())).isEqualTo(0.0);
    }

    @Test
    void estimateEqual_outOfRange() {
        List<Datum> values = List.of(Datum.of(10L), Datum.of(20L), Datum.of(30L));
        Histogram h = Histogram.buildFromSorted(values, 2);
        assertThat(h.estimateEqual(Datum.of(100L))).isEqualTo(0.0);
    }

    @Test
    void estimateLessThan_boundaries() {
        List<Datum> values = new ArrayList<>();
        for (int i = 1; i <= 100; i++) values.add(Datum.of((long) i));
        Histogram h = Histogram.buildFromSorted(values, 10);

        assertThat(h.estimateLessThan(Datum.of(0L))).isEqualTo(0.0);
        assertThat(h.estimateLessThan(Datum.of(50L))).isCloseTo(0.49, within(0.15));
        double selAll = h.estimateLessThan(Datum.of(101L));
        assertThat(selAll).isCloseTo(1.0, within(0.02));
    }

    @Test
    void estimateGreaterThan_complementary() {
        List<Datum> values = new ArrayList<>();
        for (int i = 1; i <= 100; i++) values.add(Datum.of((long) i));
        Histogram h = Histogram.buildFromSorted(values, 10);

        Datum probe = Datum.of(50L);
        double lt = h.estimateLessThan(probe);
        double eq = h.estimateEqual(probe);
        double gt = h.estimateGreaterThan(probe);
        assertThat(lt + eq + gt).isCloseTo(1.0, within(0.001));
    }

    @Test
    void estimateRange() {
        List<Datum> values = new ArrayList<>();
        for (int i = 1; i <= 100; i++) values.add(Datum.of((long) i));
        Histogram h = Histogram.buildFromSorted(values, 10);

        double sel = h.estimateRange(Datum.of(20L), Datum.of(80L), true, false);
        assertThat(sel).isGreaterThan(0.3).isLessThan(0.8);
    }

    @Test
    void stringValues() {
        List<Datum> values = List.of(
                Datum.of("alice"), Datum.of("bob"), Datum.of("charlie"),
                Datum.of("dave"), Datum.of("eve"));
        Histogram h = Histogram.buildFromSorted(values, 3);
        assertThat(h.getTotalCount()).isEqualTo(5);
        assertThat(h.getNdv()).isEqualTo(5);

        double sel = h.estimateEqual(Datum.of("bob"));
        assertThat(sel).isGreaterThan(0.0);
    }
}
