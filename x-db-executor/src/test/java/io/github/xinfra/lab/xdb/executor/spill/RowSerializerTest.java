package io.github.xinfra.lab.xdb.executor.spill;

import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.Row;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class RowSerializerTest {

    private final RowSerializer serializer = new RowSerializer();

    private Row roundTrip(Row row) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        serializer.writeRow(row, new DataOutputStream(baos));
        return serializer.readRow(new DataInputStream(new ByteArrayInputStream(baos.toByteArray())));
    }

    @Test
    void intDatumRoundTrip() throws IOException {
        Row row = new Row(new Datum[]{Datum.of(42L)});
        Row result = roundTrip(row);
        assertThat(result.size()).isEqualTo(1);
        assertThat(result.get(0).toLong()).isEqualTo(42L);
    }

    @Test
    void doubleDatumRoundTrip() throws IOException {
        Row row = new Row(new Datum[]{Datum.of(3.14)});
        Row result = roundTrip(row);
        assertThat(result.get(0).toDouble()).isEqualTo(3.14);
    }

    @Test
    void stringDatumRoundTrip() throws IOException {
        Row row = new Row(new Datum[]{Datum.of("hello world")});
        Row result = roundTrip(row);
        assertThat(((Datum.StringDatum) result.get(0)).value()).isEqualTo("hello world");
    }

    @Test
    void nullDatumRoundTrip() throws IOException {
        Row row = new Row(new Datum[]{Datum.nil()});
        Row result = roundTrip(row);
        assertThat(result.get(0).isNull()).isTrue();
    }

    @Test
    void bytesDatumRoundTrip() throws IOException {
        byte[] data = {1, 2, 3, 4, 5};
        Row row = new Row(new Datum[]{Datum.of(data)});
        Row result = roundTrip(row);
        assertThat(((Datum.BytesDatum) result.get(0)).value()).isEqualTo(data);
    }

    @Test
    void decimalDatumRoundTrip() throws IOException {
        BigDecimal val = new BigDecimal("12345.6789");
        Row row = new Row(new Datum[]{Datum.of(val)});
        Row result = roundTrip(row);
        assertThat(((Datum.DecimalDatum) result.get(0)).value()).isEqualByComparingTo(val);
    }

    @Test
    void dateTimeDatumRoundTrip() throws IOException {
        LocalDateTime dt = LocalDateTime.of(2025, 6, 15, 10, 30, 45, 123456789);
        Row row = new Row(new Datum[]{Datum.of(dt)});
        Row result = roundTrip(row);
        assertThat(((Datum.DateTimeDatum) result.get(0)).value()).isEqualTo(dt);
    }

    @Test
    void multiColumnRowRoundTrip() throws IOException {
        Row row = new Row(new Datum[]{
                Datum.of(1L),
                Datum.of("Alice"),
                Datum.of(30L),
                Datum.nil(),
                Datum.of(new BigDecimal("99.99"))
        });
        Row result = roundTrip(row);
        assertThat(result.size()).isEqualTo(5);
        assertThat(result.get(0).toLong()).isEqualTo(1L);
        assertThat(((Datum.StringDatum) result.get(1)).value()).isEqualTo("Alice");
        assertThat(result.get(2).toLong()).isEqualTo(30L);
        assertThat(result.get(3).isNull()).isTrue();
        assertThat(((Datum.DecimalDatum) result.get(4)).value()).isEqualByComparingTo(new BigDecimal("99.99"));
    }

    @Test
    void emptyStringRoundTrip() throws IOException {
        Row row = new Row(new Datum[]{Datum.of("")});
        Row result = roundTrip(row);
        assertThat(((Datum.StringDatum) result.get(0)).value()).isEmpty();
    }

    @Test
    void emptyRowRoundTrip() throws IOException {
        Row row = new Row(new Datum[0]);
        Row result = roundTrip(row);
        assertThat(result.size()).isEqualTo(0);
    }

    @Test
    void multipleRowsSequentially() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(baos);

        Row row1 = new Row(new Datum[]{Datum.of(1L), Datum.of("a")});
        Row row2 = new Row(new Datum[]{Datum.of(2L), Datum.of("b")});
        serializer.writeRow(row1, out);
        serializer.writeRow(row2, out);

        DataInputStream in = new DataInputStream(new ByteArrayInputStream(baos.toByteArray()));
        Row r1 = serializer.readRow(in);
        Row r2 = serializer.readRow(in);

        assertThat(r1.get(0).toLong()).isEqualTo(1L);
        assertThat(r2.get(0).toLong()).isEqualTo(2L);
    }

    @Test
    void unicodeStringRoundTrip() throws IOException {
        Row row = new Row(new Datum[]{Datum.of("你好世界🌍")});
        Row result = roundTrip(row);
        assertThat(((Datum.StringDatum) result.get(0)).value()).isEqualTo("你好世界🌍");
    }
}
