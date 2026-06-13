package io.github.xinfra.lab.xdb.common;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDateTime;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CodecTest {

    // ==================== Int64 ====================

    @Nested
    class Int64 {

        @ParameterizedTest
        @ValueSource(longs = {0, 1, -1, 42, -42, Long.MAX_VALUE, Long.MIN_VALUE,
                              127, -128, 256, -256, 65535, -65536, Integer.MAX_VALUE,
                              Integer.MIN_VALUE})
        void roundTrip(long value) {
            byte[] encoded = Codec.encodeInt64(value);
            assertThat(encoded).hasSize(8);
            long decoded = Codec.decodeInt64(encoded, 0);
            assertThat(decoded).isEqualTo(value);
        }

        @Test
        void decodeAtOffset() {
            byte[] prefix = {0x01, 0x02, 0x03};
            byte[] encoded = Codec.encodeInt64(999L);
            byte[] combined = Codec.concat(prefix, encoded);
            assertThat(Codec.decodeInt64(combined, 3)).isEqualTo(999L);
        }

        @Test
        void preservesComparableOrder() {
            long[] values = {Long.MIN_VALUE, -1000, -1, 0, 1, 1000, Long.MAX_VALUE};
            byte[][] encoded = new byte[values.length][];
            for (int i = 0; i < values.length; i++) {
                encoded[i] = Codec.encodeInt64(values[i]);
            }
            for (int i = 0; i < encoded.length - 1; i++) {
                assertThat(Codec.compareBytes(encoded[i], encoded[i + 1]))
                        .as("encodeInt64(%d) < encodeInt64(%d)", values[i], values[i + 1])
                        .isLessThan(0);
            }
        }
    }

    // ==================== Uint64 ====================

    @Nested
    class Uint64 {

        @ParameterizedTest
        @ValueSource(longs = {0, 1, 42, 255, 65535, Long.MAX_VALUE, -1 /* all bits set */})
        void roundTrip(long value) {
            byte[] encoded = Codec.encodeUint64(value);
            assertThat(encoded).hasSize(8);
            long decoded = Codec.decodeUint64(encoded, 0);
            assertThat(decoded).isEqualTo(value);
        }

        @Test
        void decodeAtOffset() {
            byte[] prefix = new byte[5];
            byte[] encoded = Codec.encodeUint64(12345L);
            byte[] combined = Codec.concat(prefix, encoded);
            assertThat(Codec.decodeUint64(combined, 5)).isEqualTo(12345L);
        }

        @Test
        void preservesBigEndianUnsignedOrder() {
            long[] values = {0, 1, 255, 256, 65535, Long.MAX_VALUE};
            byte[][] encoded = new byte[values.length][];
            for (int i = 0; i < values.length; i++) {
                encoded[i] = Codec.encodeUint64(values[i]);
            }
            for (int i = 0; i < encoded.length - 1; i++) {
                assertThat(Codec.compareBytes(encoded[i], encoded[i + 1]))
                        .isLessThan(0);
            }
        }
    }

    // ==================== Float64 ====================

    @Nested
    class Float64 {

        @ParameterizedTest
        @ValueSource(doubles = {0.0, -0.0, 1.0, -1.0, 3.14, -3.14,
                                Double.MAX_VALUE, Double.MIN_VALUE,
                                Double.MIN_NORMAL})
        void roundTrip(double value) {
            byte[] encoded = Codec.encodeFloat64(value);
            assertThat(encoded).hasSize(8);
            double decoded = Codec.decodeFloat64(encoded, 0);
            assertThat(decoded).isEqualTo(value);
        }

        @Test
        void preservesComparableOrder() {
            double[] values = {-Double.MAX_VALUE, -1.0, -0.001, 0.0, 0.001, 1.0, Double.MAX_VALUE};
            byte[][] encoded = new byte[values.length][];
            for (int i = 0; i < values.length; i++) {
                encoded[i] = Codec.encodeFloat64(values[i]);
            }
            for (int i = 0; i < encoded.length - 1; i++) {
                assertThat(Codec.compareBytes(encoded[i], encoded[i + 1]))
                        .as("encodeFloat64(%f) < encodeFloat64(%f)", values[i], values[i + 1])
                        .isLessThan(0);
            }
        }
    }

    // ==================== Bytes (comparable encoding) ====================

    @Nested
    class BytesEncoding {

        @Test
        void emptyBytes() {
            byte[] encoded = Codec.encodeBytes(new byte[0]);
            int[] bytesRead = {0};
            byte[] decoded = Codec.decodeBytes(encoded, 0, bytesRead);
            assertThat(decoded).isEmpty();
            assertThat(bytesRead[0]).isEqualTo(encoded.length);
        }

        @Test
        void singleByte() {
            byte[] data = {0x42};
            byte[] encoded = Codec.encodeBytes(data);
            int[] bytesRead = {0};
            byte[] decoded = Codec.decodeBytes(encoded, 0, bytesRead);
            assertThat(decoded).isEqualTo(data);
        }

        @Test
        void exactlyEightBytes() {
            byte[] data = {1, 2, 3, 4, 5, 6, 7, 8};
            byte[] encoded = Codec.encodeBytes(data);
            int[] bytesRead = {0};
            byte[] decoded = Codec.decodeBytes(encoded, 0, bytesRead);
            assertThat(decoded).isEqualTo(data);
        }

        @Test
        void moreThanEightBytes() {
            byte[] data = "hello world!".getBytes();
            byte[] encoded = Codec.encodeBytes(data);
            int[] bytesRead = {0};
            byte[] decoded = Codec.decodeBytes(encoded, 0, bytesRead);
            assertThat(decoded).isEqualTo(data);
        }

        @Test
        void preservesComparableOrder() {
            byte[] a = "abc".getBytes();
            byte[] b = "abd".getBytes();
            byte[] c = "abcd".getBytes();

            byte[] ea = Codec.encodeBytes(a);
            byte[] eb = Codec.encodeBytes(b);
            byte[] ec = Codec.encodeBytes(c);

            assertThat(Codec.compareBytes(ea, eb)).isLessThan(0);
            assertThat(Codec.compareBytes(ea, ec)).isLessThan(0);
        }

        @Test
        void decodeAtOffset() {
            byte[] prefix = {(byte) 0xAA, (byte) 0xBB};
            byte[] data = {1, 2, 3};
            byte[] encoded = Codec.encodeBytes(data);
            byte[] combined = Codec.concat(prefix, encoded);
            int[] bytesRead = {0};
            byte[] decoded = Codec.decodeBytes(combined, 2, bytesRead);
            assertThat(decoded).isEqualTo(data);
        }

        @Test
        void insufficientBytesThrows() {
            byte[] bad = {1, 2, 3}; // less than 9 bytes
            int[] bytesRead = {0};
            assertThatThrownBy(() -> Codec.decodeBytes(bad, 0, bytesRead))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("insufficient bytes");
        }
    }

    // ==================== Varint ====================

    @Nested
    class Varint {

        @ParameterizedTest
        @ValueSource(longs = {0, 1, -1, 63, -64, 127, -128, 300, -300,
                              Long.MAX_VALUE, Long.MIN_VALUE})
        void roundTrip(long value) {
            byte[] encoded = Codec.encodeVarint(value);
            int[] bytesRead = {0};
            long decoded = Codec.decodeVarint(encoded, 0, bytesRead);
            assertThat(decoded).isEqualTo(value);
            assertThat(bytesRead[0]).isEqualTo(encoded.length);
        }

        @Test
        void smallValuesAreCompact() {
            // 0 should be encoded in 1 byte
            assertThat(Codec.encodeVarint(0)).hasSize(1);
            assertThat(Codec.encodeVarint(1)).hasSize(1);
            assertThat(Codec.encodeVarint(-1)).hasSize(1);
        }

        @Test
        void decodeAtOffset() {
            byte[] prefix = {0x00, 0x00};
            byte[] encoded = Codec.encodeVarint(42);
            byte[] combined = Codec.concat(prefix, encoded);
            int[] bytesRead = {0};
            long decoded = Codec.decodeVarint(combined, 2, bytesRead);
            assertThat(decoded).isEqualTo(42L);
        }
    }

    // ==================== Uvarint ====================

    @Nested
    class Uvarint {

        @ParameterizedTest
        @ValueSource(longs = {0, 1, 127, 128, 255, 256, 16383, 16384,
                              Long.MAX_VALUE})
        void roundTrip(long value) {
            byte[] encoded = Codec.encodeUvarint(value);
            int[] bytesRead = {0};
            long decoded = Codec.decodeUvarint(encoded, 0, bytesRead);
            assertThat(decoded).isEqualTo(value);
            assertThat(bytesRead[0]).isEqualTo(encoded.length);
        }

        @Test
        void smallValuesAreCompact() {
            assertThat(Codec.encodeUvarint(0)).hasSize(1);
            assertThat(Codec.encodeUvarint(127)).hasSize(1);
            assertThat(Codec.encodeUvarint(128)).hasSize(2);
        }
    }

    // ==================== CompactBytes ====================

    @Nested
    class CompactBytes {

        @Test
        void roundTrip() {
            byte[] data = "test data".getBytes();
            byte[] encoded = Codec.encodeCompactBytes(data);
            // First part is varint-encoded length, then raw bytes
            int[] bytesRead = {0};
            long len = Codec.decodeVarint(encoded, 0, bytesRead);
            assertThat(len).isEqualTo(data.length);
            byte[] decoded = Arrays.copyOfRange(encoded, bytesRead[0], encoded.length);
            assertThat(decoded).isEqualTo(data);
        }

        @Test
        void emptyBytes() {
            byte[] encoded = Codec.encodeCompactBytes(new byte[0]);
            int[] bytesRead = {0};
            long len = Codec.decodeVarint(encoded, 0, bytesRead);
            assertThat(len).isEqualTo(0);
        }
    }

    // ==================== Datetime ====================

    @Nested
    class Datetime {

        @Test
        void roundTrip() {
            LocalDateTime dt = LocalDateTime.of(2024, 6, 15, 14, 30, 45);
            byte[] encoded = Codec.encodeDatetime(dt);
            assertThat(encoded).hasSize(8);
            LocalDateTime decoded = Codec.decodeDatetime(encoded, 0);
            assertThat(decoded).isEqualTo(dt);
        }

        @Test
        void edgeCases() {
            LocalDateTime epoch = LocalDateTime.of(1970, 1, 1, 0, 0, 0);
            LocalDateTime decoded = Codec.decodeDatetime(Codec.encodeDatetime(epoch), 0);
            assertThat(decoded).isEqualTo(epoch);

            LocalDateTime endOfYear = LocalDateTime.of(2099, 12, 31, 23, 59, 59);
            decoded = Codec.decodeDatetime(Codec.encodeDatetime(endOfYear), 0);
            assertThat(decoded).isEqualTo(endOfYear);
        }

        @Test
        void preservesChronologicalOrder() {
            LocalDateTime dt1 = LocalDateTime.of(2024, 1, 1, 0, 0, 0);
            LocalDateTime dt2 = LocalDateTime.of(2024, 6, 15, 12, 0, 0);
            LocalDateTime dt3 = LocalDateTime.of(2025, 1, 1, 0, 0, 0);

            byte[] e1 = Codec.encodeDatetime(dt1);
            byte[] e2 = Codec.encodeDatetime(dt2);
            byte[] e3 = Codec.encodeDatetime(dt3);

            assertThat(Codec.compareBytes(e1, e2)).isLessThan(0);
            assertThat(Codec.compareBytes(e2, e3)).isLessThan(0);
        }
    }

    // ==================== compareBytes ====================

    @Nested
    class CompareBytes {

        @Test
        void equalArrays() {
            byte[] a = {1, 2, 3};
            assertThat(Codec.compareBytes(a, a.clone())).isEqualTo(0);
        }

        @Test
        void emptyArrays() {
            assertThat(Codec.compareBytes(new byte[0], new byte[0])).isEqualTo(0);
        }

        @Test
        void differentLengths() {
            byte[] shorter = {1, 2};
            byte[] longer = {1, 2, 3};
            assertThat(Codec.compareBytes(shorter, longer)).isLessThan(0);
            assertThat(Codec.compareBytes(longer, shorter)).isGreaterThan(0);
        }

        @Test
        void unsignedComparison() {
            // 0xFF (255 unsigned) should be > 0x01
            byte[] a = {(byte) 0xFF};
            byte[] b = {0x01};
            assertThat(Codec.compareBytes(a, b)).isGreaterThan(0);
        }
    }

    // ==================== concat ====================

    @Nested
    class Concat {

        @Test
        void emptyArrays() {
            assertThat(Codec.concat()).isEmpty();
            assertThat(Codec.concat(new byte[0])).isEmpty();
        }

        @Test
        void singleArray() {
            byte[] a = {1, 2, 3};
            assertThat(Codec.concat(a)).isEqualTo(a);
        }

        @Test
        void multipleArrays() {
            byte[] a = {1, 2};
            byte[] b = {3, 4};
            byte[] c = {5};
            assertThat(Codec.concat(a, b, c)).isEqualTo(new byte[]{1, 2, 3, 4, 5});
        }
    }
}
