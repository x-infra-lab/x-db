package io.github.xinfra.lab.xdb.common;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BytesUtilTest {

    @Test
    void emptyConstant() {
        assertThat(BytesUtil.EMPTY).isEmpty();
    }

    @Nested
    class IsEmpty {

        @Test
        void nullIsEmpty() {
            assertThat(BytesUtil.isEmpty(null)).isTrue();
        }

        @Test
        void emptyArrayIsEmpty() {
            assertThat(BytesUtil.isEmpty(new byte[0])).isTrue();
        }

        @Test
        void nonEmptyIsNotEmpty() {
            assertThat(BytesUtil.isEmpty(new byte[]{1})).isFalse();
        }
    }

    @Nested
    class NextKey {

        @Test
        void appendsZeroByte() {
            byte[] key = {0x01, 0x02};
            byte[] next = BytesUtil.nextKey(key);
            assertThat(next).hasSize(3);
            assertThat(next[0]).isEqualTo((byte) 0x01);
            assertThat(next[1]).isEqualTo((byte) 0x02);
            assertThat(next[2]).isEqualTo((byte) 0x00);
        }

        @Test
        void emptyKey() {
            byte[] next = BytesUtil.nextKey(new byte[0]);
            assertThat(next).hasSize(1);
            assertThat(next[0]).isEqualTo((byte) 0x00);
        }

        @Test
        void nextKeyIsGreater() {
            byte[] key = {0x01, 0x02};
            byte[] next = BytesUtil.nextKey(key);
            assertThat(BytesUtil.compare(key, next)).isLessThan(0);
        }
    }

    @Nested
    class PrefixEndKey {

        @Test
        void simpleIncrement() {
            byte[] prefix = {0x01, 0x02};
            byte[] end = BytesUtil.prefixEndKey(prefix);
            assertThat(end).isEqualTo(new byte[]{0x01, 0x03});
        }

        @Test
        void carryOver() {
            byte[] prefix = {0x01, (byte) 0xFF};
            byte[] end = BytesUtil.prefixEndKey(prefix);
            assertThat(end).isEqualTo(new byte[]{0x02, 0x00});
        }

        @Test
        void allFFs() {
            byte[] prefix = {(byte) 0xFF, (byte) 0xFF};
            byte[] end = BytesUtil.prefixEndKey(prefix);
            assertThat(end).isNull();
        }

        @Test
        void singleByte() {
            byte[] prefix = {0x41};
            byte[] end = BytesUtil.prefixEndKey(prefix);
            assertThat(end).isEqualTo(new byte[]{0x42});
        }
    }

    @Nested
    class HasPrefix {

        @Test
        void matchingPrefix() {
            byte[] key = {0x01, 0x02, 0x03};
            byte[] prefix = {0x01, 0x02};
            assertThat(BytesUtil.hasPrefix(key, prefix)).isTrue();
        }

        @Test
        void exactMatch() {
            byte[] key = {0x01, 0x02};
            assertThat(BytesUtil.hasPrefix(key, key.clone())).isTrue();
        }

        @Test
        void noMatch() {
            byte[] key = {0x01, 0x02, 0x03};
            byte[] prefix = {0x01, 0x03};
            assertThat(BytesUtil.hasPrefix(key, prefix)).isFalse();
        }

        @Test
        void keyTooShort() {
            byte[] key = {0x01};
            byte[] prefix = {0x01, 0x02};
            assertThat(BytesUtil.hasPrefix(key, prefix)).isFalse();
        }

        @Test
        void emptyPrefix() {
            byte[] key = {0x01};
            assertThat(BytesUtil.hasPrefix(key, new byte[0])).isTrue();
        }
    }

    @Nested
    class Compare {

        @Test
        void equal() {
            byte[] a = {1, 2, 3};
            assertThat(BytesUtil.compare(a, a.clone())).isEqualTo(0);
        }

        @Test
        void lessThan() {
            byte[] a = {1, 2};
            byte[] b = {1, 3};
            assertThat(BytesUtil.compare(a, b)).isLessThan(0);
        }

        @Test
        void greaterThan() {
            byte[] a = {1, 3};
            byte[] b = {1, 2};
            assertThat(BytesUtil.compare(a, b)).isGreaterThan(0);
        }

        @Test
        void delegatesToCodecCompareBytes() {
            byte[] a = {(byte) 0xFF};
            byte[] b = {0x01};
            // Unsigned comparison: 0xFF > 0x01
            assertThat(BytesUtil.compare(a, b)).isGreaterThan(0);
        }
    }

    @Nested
    class ToHex {

        @Test
        void emptyArray() {
            assertThat(BytesUtil.toHex(new byte[0])).isEmpty();
        }

        @Test
        void singleByte() {
            assertThat(BytesUtil.toHex(new byte[]{0x0A})).isEqualTo("0a");
        }

        @Test
        void multipleBytes() {
            assertThat(BytesUtil.toHex(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE}))
                    .isEqualTo("cafebabe");
        }

        @Test
        void zeroPadding() {
            assertThat(BytesUtil.toHex(new byte[]{0x00, 0x01})).isEqualTo("0001");
        }
    }
}
