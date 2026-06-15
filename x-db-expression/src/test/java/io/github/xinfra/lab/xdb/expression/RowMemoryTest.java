package io.github.xinfra.lab.xdb.expression;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class RowMemoryTest {

    @Test
    void nullDatumSize() {
        assertThat(Datum.estimateMemoryBytes(Datum.nil())).isEqualTo(16);
        assertThat(Datum.estimateMemoryBytes(null)).isEqualTo(16);
    }

    @Test
    void intDatumSize() {
        assertThat(Datum.estimateMemoryBytes(Datum.of(42L))).isEqualTo(24);
    }

    @Test
    void doubleDatumSize() {
        assertThat(Datum.estimateMemoryBytes(Datum.of(3.14))).isEqualTo(24);
    }

    @Test
    void decimalDatumSize() {
        assertThat(Datum.estimateMemoryBytes(Datum.of(new BigDecimal("123.45")))).isEqualTo(56);
    }

    @Test
    void stringDatumSizeScalesWithLength() {
        long small = Datum.estimateMemoryBytes(Datum.of("hi"));
        long large = Datum.estimateMemoryBytes(Datum.of("hello world, this is a longer string"));
        assertThat(large).isGreaterThan(small);
        assertThat(Datum.estimateMemoryBytes(Datum.of(""))).isEqualTo(40);
    }

    @Test
    void bytesDatumSizeScalesWithLength() {
        long small = Datum.estimateMemoryBytes(Datum.of(new byte[]{1, 2}));
        long large = Datum.estimateMemoryBytes(Datum.of(new byte[100]));
        assertThat(large).isGreaterThan(small);
    }

    @Test
    void dateTimeDatumSize() {
        assertThat(Datum.estimateMemoryBytes(Datum.of(LocalDateTime.now()))).isEqualTo(48);
    }

    @Test
    void rowMemorySizeIsPositive() {
        Row row = new Row(new Datum[]{
                Datum.of(1L),
                Datum.of("Alice"),
                Datum.of(30L)
        });
        long size = row.estimateMemoryBytes();
        assertThat(size).isGreaterThan(0);
        assertThat(size).isGreaterThan(3 * 24);
    }

    @Test
    void emptyRowHasMinimalSize() {
        Row row = new Row(new Datum[0]);
        assertThat(row.estimateMemoryBytes()).isEqualTo(32);
    }

    @Test
    void rowWithNullsReportsSize() {
        Row row = new Row(3);
        long size = row.estimateMemoryBytes();
        assertThat(size).isGreaterThan(0);
    }
}
