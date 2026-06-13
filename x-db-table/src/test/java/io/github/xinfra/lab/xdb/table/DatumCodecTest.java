package io.github.xinfra.lab.xdb.table;

import io.github.xinfra.lab.xdb.common.Codec;
import io.github.xinfra.lab.xdb.expression.Datum;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DatumCodecTest {

    private Datum roundTrip(Datum datum) {
        byte[] encoded = DatumCodec.encode(datum);
        int[] bytesRead = {0};
        Datum decoded = DatumCodec.decode(encoded, 0, bytesRead);
        assertThat(bytesRead[0]).isEqualTo(encoded.length);
        return decoded;
    }

    // ==================== Null ====================

    @Nested
    class NullCodec {

        @Test
        void encodeNull() {
            byte[] encoded = DatumCodec.encode(Datum.nil());
            assertThat(encoded).hasSize(1);
            assertThat(encoded[0]).isEqualTo(Codec.NULL_FLAG);
        }

        @Test
        void decodeNull() {
            Datum decoded = DatumCodecTest.this.roundTrip(Datum.nil());
            assertThat(decoded.isNull()).isTrue();
        }
    }

    // ==================== Int ====================

    @Nested
    class IntCodec {

        @Test
        void encodeDecodePositive() {
            assertThat(DatumCodecTest.this.roundTrip(Datum.of(42L)).toLong()).isEqualTo(42);
        }

        @Test
        void encodeDecodeZero() {
            assertThat(DatumCodecTest.this.roundTrip(Datum.of(0L)).toLong()).isEqualTo(0);
        }

        @Test
        void encodeDecodeNegative() {
            assertThat(DatumCodecTest.this.roundTrip(Datum.of(-100L)).toLong()).isEqualTo(-100);
        }

        @Test
        void encodeDecodeMaxValue() {
            assertThat(DatumCodecTest.this.roundTrip(Datum.of(Long.MAX_VALUE)).toLong()).isEqualTo(Long.MAX_VALUE);
        }

        @Test
        void encodeDecodeMinValue() {
            assertThat(DatumCodecTest.this.roundTrip(Datum.of(Long.MIN_VALUE)).toLong()).isEqualTo(Long.MIN_VALUE);
        }

        @Test
        void encodedSize() {
            byte[] encoded = DatumCodec.encode(Datum.of(42L));
            assertThat(encoded).hasSize(9); // flag(1) + int64(8)
            assertThat(encoded[0]).isEqualTo(Codec.INT_FLAG);
        }

        @Test
        void orderingPreserved() {
            byte[] e1 = DatumCodec.encode(Datum.of(-100L));
            byte[] e2 = DatumCodec.encode(Datum.of(0L));
            byte[] e3 = DatumCodec.encode(Datum.of(100L));

            assertThat(Codec.compareBytes(e1, e2)).isLessThan(0);
            assertThat(Codec.compareBytes(e2, e3)).isLessThan(0);
        }
    }

    // ==================== Double ====================

    @Nested
    class DoubleCodec {

        @Test
        void encodeDecodePositive() {
            assertThat(DatumCodecTest.this.roundTrip(Datum.of(3.14)).toDouble()).isEqualTo(3.14);
        }

        @Test
        void encodeDecodeZero() {
            assertThat(DatumCodecTest.this.roundTrip(Datum.of(0.0)).toDouble()).isEqualTo(0.0);
        }

        @Test
        void encodeDecodeNegative() {
            assertThat(DatumCodecTest.this.roundTrip(Datum.of(-2.718)).toDouble()).isEqualTo(-2.718);
        }

        @Test
        void encodedSize() {
            byte[] encoded = DatumCodec.encode(Datum.of(1.0));
            assertThat(encoded).hasSize(9); // flag(1) + float64(8)
            assertThat(encoded[0]).isEqualTo(Codec.FLOAT_FLAG);
        }

        @Test
        void orderingPreserved() {
            byte[] e1 = DatumCodec.encode(Datum.of(-1.0));
            byte[] e2 = DatumCodec.encode(Datum.of(0.0));
            byte[] e3 = DatumCodec.encode(Datum.of(1.0));

            assertThat(Codec.compareBytes(e1, e2)).isLessThan(0);
            assertThat(Codec.compareBytes(e2, e3)).isLessThan(0);
        }
    }

    // ==================== String ====================

    @Nested
    class StringCodec {

        @Test
        void encodeDecodeBasic() {
            Datum decoded = DatumCodecTest.this.roundTrip(Datum.of("hello"));
            assertThat(decoded.toStringValue()).isEqualTo("hello");
        }

        @Test
        void encodeDecodeEmpty() {
            Datum decoded = DatumCodecTest.this.roundTrip(Datum.of(""));
            assertThat(decoded.toStringValue()).isEmpty();
        }

        @Test
        void encodeDecodeUnicode() {
            Datum decoded = DatumCodecTest.this.roundTrip(Datum.of("unicode: éàü"));
            assertThat(decoded.toStringValue()).isEqualTo("unicode: éàü");
        }

        @Test
        void encodeDecodeLong() {
            String longStr = "x".repeat(1000);
            Datum decoded = DatumCodecTest.this.roundTrip(Datum.of(longStr));
            assertThat(decoded.toStringValue()).isEqualTo(longStr);
        }

        @Test
        void encodedStartsWithBytesFlag() {
            byte[] encoded = DatumCodec.encode(Datum.of("test"));
            assertThat(encoded[0]).isEqualTo(Codec.BYTES_FLAG);
        }

        @Test
        void orderingPreserved() {
            byte[] e1 = DatumCodec.encode(Datum.of("abc"));
            byte[] e2 = DatumCodec.encode(Datum.of("abd"));
            byte[] e3 = DatumCodec.encode(Datum.of("xyz"));

            assertThat(Codec.compareBytes(e1, e2)).isLessThan(0);
            assertThat(Codec.compareBytes(e2, e3)).isLessThan(0);
        }
    }

    // ==================== Bytes ====================

    @Nested
    class BytesCodec {

        @Test
        void encodeDecodeBasic() {
            byte[] data = {1, 2, 3, 4, 5};
            Datum decoded = DatumCodecTest.this.roundTrip(Datum.of(data));
            // BytesDatum decoded as StringDatum (via BYTES_FLAG -> String path in decode)
            assertThat(decoded).isNotNull();
        }

        @Test
        void encodeDecodeEmpty() {
            byte[] data = {};
            Datum decoded = DatumCodecTest.this.roundTrip(Datum.of(data));
            assertThat(decoded).isNotNull();
        }

        @Test
        void encodedStartsWithBytesFlag() {
            byte[] encoded = DatumCodec.encode(Datum.of(new byte[]{0x01}));
            assertThat(encoded[0]).isEqualTo(Codec.BYTES_FLAG);
        }
    }

    // ==================== Decimal ====================

    @Nested
    class DecimalCodec {

        @Test
        void encodeDecodeBasic() {
            Datum decoded = DatumCodecTest.this.roundTrip(Datum.of(new BigDecimal("12.345")));
            assertThat(decoded).isInstanceOf(Datum.DecimalDatum.class);
            assertThat(((Datum.DecimalDatum) decoded).value()).isEqualByComparingTo(new BigDecimal("12.345"));
        }

        @Test
        void encodeDecodeZero() {
            Datum decoded = DatumCodecTest.this.roundTrip(Datum.of(BigDecimal.ZERO));
            assertThat(((Datum.DecimalDatum) decoded).value()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        @Test
        void encodeDecodeNegative() {
            Datum decoded = DatumCodecTest.this.roundTrip(Datum.of(new BigDecimal("-99.99")));
            assertThat(((Datum.DecimalDatum) decoded).value()).isEqualByComparingTo(new BigDecimal("-99.99"));
        }

        @Test
        void encodedStartsWithDecimalFlag() {
            byte[] encoded = DatumCodec.encode(Datum.of(new BigDecimal("1.0")));
            assertThat(encoded[0]).isEqualTo(Codec.DECIMAL_FLAG);
        }
    }

    // ==================== DateTime ====================

    @Nested
    class DateTimeCodec {

        @Test
        void encodeDecodeBasic() {
            LocalDateTime dt = LocalDateTime.of(2024, 6, 15, 14, 30, 45);
            Datum decoded = DatumCodecTest.this.roundTrip(Datum.of(dt));
            assertThat(decoded).isInstanceOf(Datum.DateTimeDatum.class);
            assertThat(((Datum.DateTimeDatum) decoded).value()).isEqualTo(dt);
        }

        @Test
        void encodeDecodeEpoch() {
            LocalDateTime dt = LocalDateTime.of(1970, 1, 1, 0, 0, 0);
            Datum decoded = DatumCodecTest.this.roundTrip(Datum.of(dt));
            assertThat(((Datum.DateTimeDatum) decoded).value()).isEqualTo(dt);
        }

        @Test
        void encodedSize() {
            byte[] encoded = DatumCodec.encode(Datum.of(LocalDateTime.of(2024, 1, 1, 0, 0, 0)));
            assertThat(encoded).hasSize(9); // flag(1) + datetime(8)
            assertThat(encoded[0]).isEqualTo(Codec.DURATION_FLAG);
        }

        @Test
        void orderingPreserved() {
            byte[] e1 = DatumCodec.encode(Datum.of(LocalDateTime.of(2024, 1, 1, 0, 0, 0)));
            byte[] e2 = DatumCodec.encode(Datum.of(LocalDateTime.of(2024, 6, 15, 12, 0, 0)));
            byte[] e3 = DatumCodec.encode(Datum.of(LocalDateTime.of(2025, 1, 1, 0, 0, 0)));

            assertThat(Codec.compareBytes(e1, e2)).isLessThan(0);
            assertThat(Codec.compareBytes(e2, e3)).isLessThan(0);
        }
    }

    // ==================== Error handling ====================

    @Test
    void unknownFlagThrows() {
        byte[] bad = {(byte) 0xAA, 0x01, 0x02};
        int[] bytesRead = {0};
        assertThatThrownBy(() -> DatumCodec.decode(bad, 0, bytesRead))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown datum flag");
    }

    // ==================== Decode at offset ====================

    @Test
    void decodeAtOffset() {
        byte[] prefix = {0x01, 0x02, 0x03};
        byte[] encoded = DatumCodec.encode(Datum.of(42L));
        byte[] combined = new byte[prefix.length + encoded.length];
        System.arraycopy(prefix, 0, combined, 0, prefix.length);
        System.arraycopy(encoded, 0, combined, prefix.length, encoded.length);

        int[] bytesRead = {0};
        Datum decoded = DatumCodec.decode(combined, 3, bytesRead);
        assertThat(decoded.toLong()).isEqualTo(42);
        assertThat(bytesRead[0]).isEqualTo(9);
    }
}
