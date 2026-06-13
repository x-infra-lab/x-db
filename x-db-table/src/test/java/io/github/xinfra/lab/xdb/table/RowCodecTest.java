package io.github.xinfra.lab.xdb.table;

import io.github.xinfra.lab.xdb.expression.Datum;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RowCodecTest {

    @Nested
    class BasicRoundTrip {

        @Test
        void singleIntColumn() {
            List<Long> colIds = List.of(1L);
            List<Datum> values = List.of(Datum.of(42L));

            byte[] encoded = RowCodec.encode(colIds, values);
            Map<Long, Datum> decoded = RowCodec.decode(encoded);

            assertThat(decoded).hasSize(1);
            assertThat(decoded.get(1L).toLong()).isEqualTo(42);
        }

        @Test
        void singleStringColumn() {
            List<Long> colIds = List.of(1L);
            List<Datum> values = List.of(Datum.of("hello"));

            byte[] encoded = RowCodec.encode(colIds, values);
            Map<Long, Datum> decoded = RowCodec.decode(encoded);

            assertThat(decoded).hasSize(1);
            assertThat(decoded.get(1L).toStringValue()).isEqualTo("hello");
        }

        @Test
        void singleDoubleColumn() {
            List<Long> colIds = List.of(1L);
            List<Datum> values = List.of(Datum.of(3.14));

            byte[] encoded = RowCodec.encode(colIds, values);
            Map<Long, Datum> decoded = RowCodec.decode(encoded);

            assertThat(decoded).hasSize(1);
            assertThat(decoded.get(1L).toDouble()).isEqualTo(3.14);
        }

        @Test
        void singleDecimalColumn() {
            List<Long> colIds = List.of(1L);
            List<Datum> values = List.of(Datum.of(new BigDecimal("12.345")));

            byte[] encoded = RowCodec.encode(colIds, values);
            Map<Long, Datum> decoded = RowCodec.decode(encoded);

            assertThat(decoded).hasSize(1);
            assertThat(decoded.get(1L)).isInstanceOf(Datum.DecimalDatum.class);
            assertThat(((Datum.DecimalDatum) decoded.get(1L)).value())
                    .isEqualByComparingTo(new BigDecimal("12.345"));
        }

        @Test
        void singleDateTimeColumn() {
            LocalDateTime dt = LocalDateTime.of(2024, 6, 15, 14, 30, 45);
            List<Long> colIds = List.of(1L);
            List<Datum> values = List.of(Datum.of(dt));

            byte[] encoded = RowCodec.encode(colIds, values);
            Map<Long, Datum> decoded = RowCodec.decode(encoded);

            assertThat(decoded).hasSize(1);
            assertThat(decoded.get(1L)).isInstanceOf(Datum.DateTimeDatum.class);
            assertThat(((Datum.DateTimeDatum) decoded.get(1L)).value()).isEqualTo(dt);
        }
    }

    @Nested
    class MultipleColumns {

        @Test
        void multipleColumnsOfDifferentTypes() {
            List<Long> colIds = List.of(1L, 2L, 3L, 4L);
            List<Datum> values = List.of(
                    Datum.of(42L),
                    Datum.of("hello"),
                    Datum.of(3.14),
                    Datum.of(new BigDecimal("99.99"))
            );

            byte[] encoded = RowCodec.encode(colIds, values);
            Map<Long, Datum> decoded = RowCodec.decode(encoded);

            assertThat(decoded).hasSize(4);
            assertThat(decoded.get(1L).toLong()).isEqualTo(42);
            assertThat(decoded.get(2L).toStringValue()).isEqualTo("hello");
            assertThat(decoded.get(3L).toDouble()).isEqualTo(3.14);
            assertThat(((Datum.DecimalDatum) decoded.get(4L)).value())
                    .isEqualByComparingTo(new BigDecimal("99.99"));
        }

        @Test
        void manyColumns() {
            List<Long> colIds = List.of(1L, 2L, 3L, 4L, 5L);
            List<Datum> values = List.of(
                    Datum.of(1L),
                    Datum.of(2L),
                    Datum.of(3L),
                    Datum.of(4L),
                    Datum.of(5L)
            );

            byte[] encoded = RowCodec.encode(colIds, values);
            Map<Long, Datum> decoded = RowCodec.decode(encoded);

            assertThat(decoded).hasSize(5);
            for (int i = 1; i <= 5; i++) {
                assertThat(decoded.get((long) i).toLong()).isEqualTo(i);
            }
        }
    }

    @Nested
    class NullHandling {

        @Test
        void nullColumnsAreSkipped() {
            List<Long> colIds = List.of(1L, 2L, 3L);
            List<Datum> values = List.of(
                    Datum.of(42L),
                    Datum.nil(),
                    Datum.of("hello")
            );

            byte[] encoded = RowCodec.encode(colIds, values);
            Map<Long, Datum> decoded = RowCodec.decode(encoded);

            // Null column 2 is skipped
            assertThat(decoded).hasSize(2);
            assertThat(decoded.get(1L).toLong()).isEqualTo(42);
            assertThat(decoded.containsKey(2L)).isFalse();
            assertThat(decoded.get(3L).toStringValue()).isEqualTo("hello");
        }

        @Test
        void allNullColumns() {
            List<Long> colIds = List.of(1L, 2L);
            List<Datum> values = List.of(Datum.nil(), Datum.nil());

            byte[] encoded = RowCodec.encode(colIds, values);
            Map<Long, Datum> decoded = RowCodec.decode(encoded);

            assertThat(decoded).isEmpty();
        }
    }

    @Nested
    class EdgeCases {

        @Test
        void decodeNullInput() {
            Map<Long, Datum> decoded = RowCodec.decode(null);
            assertThat(decoded).isEmpty();
        }

        @Test
        void decodeEmptyInput() {
            Map<Long, Datum> decoded = RowCodec.decode(new byte[0]);
            assertThat(decoded).isEmpty();
        }

        @Test
        void largeColumnIds() {
            List<Long> colIds = List.of(1000L, 2000L);
            List<Datum> values = List.of(Datum.of(1L), Datum.of(2L));

            byte[] encoded = RowCodec.encode(colIds, values);
            Map<Long, Datum> decoded = RowCodec.decode(encoded);

            assertThat(decoded.get(1000L).toLong()).isEqualTo(1);
            assertThat(decoded.get(2000L).toLong()).isEqualTo(2);
        }

        @Test
        void emptyStringValue() {
            List<Long> colIds = List.of(1L);
            List<Datum> values = List.of(Datum.of(""));

            byte[] encoded = RowCodec.encode(colIds, values);
            Map<Long, Datum> decoded = RowCodec.decode(encoded);

            assertThat(decoded.get(1L).toStringValue()).isEmpty();
        }

        @Test
        void negativeIntValue() {
            List<Long> colIds = List.of(1L);
            List<Datum> values = List.of(Datum.of(-999L));

            byte[] encoded = RowCodec.encode(colIds, values);
            Map<Long, Datum> decoded = RowCodec.decode(encoded);

            assertThat(decoded.get(1L).toLong()).isEqualTo(-999);
        }

        @Test
        void rowFlagIsPresentInEncoded() {
            List<Long> colIds = List.of(1L);
            List<Datum> values = List.of(Datum.of(1L));
            byte[] encoded = RowCodec.encode(colIds, values);
            assertThat(encoded[0]).isEqualTo((byte) 0x80);
        }
    }
}
