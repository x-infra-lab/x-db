package io.github.xinfra.lab.xdb.expression;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class DatumTest {

    // ==================== Factory methods ====================

    @Nested
    class FactoryMethods {

        @Test
        void ofLong() {
            Datum d = Datum.of(42L);
            assertThat(d).isInstanceOf(Datum.IntDatum.class);
            assertThat(((Datum.IntDatum) d).value()).isEqualTo(42L);
        }

        @Test
        void ofDouble() {
            Datum d = Datum.of(3.14);
            assertThat(d).isInstanceOf(Datum.DoubleDatum.class);
            assertThat(((Datum.DoubleDatum) d).value()).isEqualTo(3.14);
        }

        @Test
        void ofBigDecimal() {
            Datum d = Datum.of(new BigDecimal("12.345"));
            assertThat(d).isInstanceOf(Datum.DecimalDatum.class);
            assertThat(((Datum.DecimalDatum) d).value()).isEqualTo(new BigDecimal("12.345"));
        }

        @Test
        void ofBigDecimalNull() {
            Datum d = Datum.of((BigDecimal) null);
            assertThat(d.isNull()).isTrue();
        }

        @Test
        void ofString() {
            Datum d = Datum.of("hello");
            assertThat(d).isInstanceOf(Datum.StringDatum.class);
            assertThat(((Datum.StringDatum) d).value()).isEqualTo("hello");
        }

        @Test
        void ofStringNull() {
            Datum d = Datum.of((String) null);
            assertThat(d.isNull()).isTrue();
        }

        @Test
        void ofBytes() {
            byte[] data = {1, 2, 3};
            Datum d = Datum.of(data);
            assertThat(d).isInstanceOf(Datum.BytesDatum.class);
            assertThat(((Datum.BytesDatum) d).value()).isEqualTo(data);
        }

        @Test
        void ofBytesNull() {
            Datum d = Datum.of((byte[]) null);
            assertThat(d.isNull()).isTrue();
        }

        @Test
        void ofDateTime() {
            LocalDateTime dt = LocalDateTime.of(2024, 6, 15, 12, 0, 0);
            Datum d = Datum.of(dt);
            assertThat(d).isInstanceOf(Datum.DateTimeDatum.class);
            assertThat(((Datum.DateTimeDatum) d).value()).isEqualTo(dt);
        }

        @Test
        void ofDateTimeNull() {
            Datum d = Datum.of((LocalDateTime) null);
            assertThat(d.isNull()).isTrue();
        }

        @Test
        void nilReturnsSingletonNull() {
            assertThat(Datum.nil()).isSameAs(Datum.NULL_INSTANCE);
            assertThat(Datum.nil().isNull()).isTrue();
        }
    }

    // ==================== isNull ====================

    @Nested
    class IsNull {

        @Test
        void nullDatumIsNull() {
            assertThat(Datum.nil().isNull()).isTrue();
        }

        @Test
        void intDatumIsNotNull() {
            assertThat(Datum.of(0L).isNull()).isFalse();
        }

        @Test
        void stringDatumIsNotNull() {
            assertThat(Datum.of("").isNull()).isFalse();
        }
    }

    // ==================== toLong ====================

    @Nested
    class ToLong {

        @Test
        void fromInt() {
            assertThat(Datum.of(42L).toLong()).isEqualTo(42L);
        }

        @Test
        void fromDouble() {
            assertThat(Datum.of(3.9).toLong()).isEqualTo(3L);
        }

        @Test
        void fromDecimal() {
            assertThat(Datum.of(new BigDecimal("99.99")).toLong()).isEqualTo(99L);
        }

        @Test
        void fromString() {
            assertThat(Datum.of("123").toLong()).isEqualTo(123L);
        }

        @Test
        void fromStringWithWhitespace() {
            assertThat(Datum.of("  456  ").toLong()).isEqualTo(456L);
        }

        @Test
        void fromInvalidString() {
            assertThat(Datum.of("abc").toLong()).isEqualTo(0L);
        }

        @Test
        void fromNull() {
            assertThat(Datum.nil().toLong()).isEqualTo(0L);
        }
    }

    // ==================== toDouble ====================

    @Nested
    class ToDouble {

        @Test
        void fromInt() {
            assertThat(Datum.of(42L).toDouble()).isEqualTo(42.0);
        }

        @Test
        void fromDouble() {
            assertThat(Datum.of(3.14).toDouble()).isEqualTo(3.14);
        }

        @Test
        void fromDecimal() {
            assertThat(Datum.of(new BigDecimal("2.5")).toDouble()).isEqualTo(2.5);
        }

        @Test
        void fromString() {
            assertThat(Datum.of("1.5").toDouble()).isEqualTo(1.5);
        }

        @Test
        void fromInvalidString() {
            assertThat(Datum.of("abc").toDouble()).isEqualTo(0.0);
        }

        @Test
        void fromNull() {
            assertThat(Datum.nil().toDouble()).isEqualTo(0.0);
        }
    }

    // ==================== toBoolean ====================

    @Nested
    class ToBoolean {

        @Test
        void nullIsFalse() {
            assertThat(Datum.nil().toBoolean()).isFalse();
        }

        @Test
        void zeroIntIsFalse() {
            assertThat(Datum.of(0L).toBoolean()).isFalse();
        }

        @Test
        void nonZeroIntIsTrue() {
            assertThat(Datum.of(1L).toBoolean()).isTrue();
            assertThat(Datum.of(-1L).toBoolean()).isTrue();
        }

        @Test
        void zeroDoubleIsFalse() {
            assertThat(Datum.of(0.0).toBoolean()).isFalse();
        }

        @Test
        void nonZeroDoubleIsTrue() {
            assertThat(Datum.of(0.1).toBoolean()).isTrue();
        }

        @Test
        void emptyStringIsFalse() {
            assertThat(Datum.of("").toBoolean()).isFalse();
        }

        @Test
        void zeroStringIsFalse() {
            assertThat(Datum.of("0").toBoolean()).isFalse();
        }

        @Test
        void nonEmptyStringIsTrue() {
            assertThat(Datum.of("hello").toBoolean()).isTrue();
        }

        @Test
        void otherTypesAreTrue() {
            assertThat(Datum.of(new byte[]{1}).toBoolean()).isTrue();
            assertThat(Datum.of(LocalDateTime.now()).toBoolean()).isTrue();
            assertThat(Datum.of(BigDecimal.ZERO).toBoolean()).isTrue();
        }
    }

    // ==================== toString ====================

    @Nested
    class ToStringMethod {

        @Test
        void intDatum() {
            assertThat(Datum.of(42L).toString()).isEqualTo("42");
        }

        @Test
        void doubleDatum() {
            assertThat(Datum.of(3.14).toString()).isEqualTo("3.14");
        }

        @Test
        void decimalDatum() {
            assertThat(Datum.of(new BigDecimal("12.340")).toString()).isEqualTo("12.340");
        }

        @Test
        void stringDatum() {
            assertThat(Datum.of("hello").toString()).isEqualTo("hello");
        }

        @Test
        void bytesDatum() {
            assertThat(Datum.of(new byte[]{(byte) 0xCA, (byte) 0xFE}).toString())
                    .isEqualTo("0xcafe");
        }

        @Test
        void dateTimeDatum() {
            LocalDateTime dt = LocalDateTime.of(2024, 6, 15, 14, 30, 45);
            assertThat(Datum.of(dt).toString()).isEqualTo("2024-06-15 14:30:45");
        }

        @Test
        void nullDatum() {
            assertThat(Datum.nil().toString()).isEqualTo("NULL");
        }
    }

    // ==================== BytesDatum equality ====================

    @Nested
    class BytesDatumEquality {

        @Test
        void equalBytes() {
            Datum a = Datum.of(new byte[]{1, 2, 3});
            Datum b = Datum.of(new byte[]{1, 2, 3});
            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        void differentBytes() {
            Datum a = Datum.of(new byte[]{1, 2, 3});
            Datum b = Datum.of(new byte[]{1, 2, 4});
            assertThat(a).isNotEqualTo(b);
        }
    }

    // ==================== DatumComparator ====================

    @Nested
    class ComparatorTest {

        @Test
        void nullHandling() {
            assertThat(DatumComparator.compare(Datum.nil(), Datum.nil())).isEqualTo(0);
            assertThat(DatumComparator.compare(Datum.nil(), Datum.of(1L))).isLessThan(0);
            assertThat(DatumComparator.compare(Datum.of(1L), Datum.nil())).isGreaterThan(0);
        }

        @Test
        void sameTypeInt() {
            assertThat(DatumComparator.compare(Datum.of(1L), Datum.of(2L))).isLessThan(0);
            assertThat(DatumComparator.compare(Datum.of(2L), Datum.of(1L))).isGreaterThan(0);
            assertThat(DatumComparator.compare(Datum.of(5L), Datum.of(5L))).isEqualTo(0);
        }

        @Test
        void sameTypeDouble() {
            assertThat(DatumComparator.compare(Datum.of(1.0), Datum.of(2.0))).isLessThan(0);
            assertThat(DatumComparator.compare(Datum.of(2.0), Datum.of(1.0))).isGreaterThan(0);
        }

        @Test
        void sameTypeString() {
            assertThat(DatumComparator.compare(Datum.of("abc"), Datum.of("abd"))).isLessThan(0);
            assertThat(DatumComparator.compare(Datum.of("z"), Datum.of("a"))).isGreaterThan(0);
        }

        @Test
        void sameTypeDecimal() {
            assertThat(DatumComparator.compare(
                    Datum.of(new BigDecimal("1.5")),
                    Datum.of(new BigDecimal("2.5"))
            )).isLessThan(0);
        }

        @Test
        void sameTypeDateTime() {
            LocalDateTime dt1 = LocalDateTime.of(2024, 1, 1, 0, 0);
            LocalDateTime dt2 = LocalDateTime.of(2024, 12, 31, 23, 59);
            assertThat(DatumComparator.compare(Datum.of(dt1), Datum.of(dt2))).isLessThan(0);
        }

        @Test
        void sameTypeBytes() {
            assertThat(DatumComparator.compare(
                    Datum.of(new byte[]{1, 2}),
                    Datum.of(new byte[]{1, 3})
            )).isLessThan(0);
        }

        @Test
        void crossNumericTypes() {
            // int vs double
            assertThat(DatumComparator.compare(Datum.of(1L), Datum.of(2.0))).isLessThan(0);
            // int vs decimal
            assertThat(DatumComparator.compare(Datum.of(10L), Datum.of(new BigDecimal("5")))).isGreaterThan(0);
            // double vs decimal
            assertThat(DatumComparator.compare(Datum.of(1.5), Datum.of(new BigDecimal("1.5")))).isEqualTo(0);
        }

        @Test
        void nonNumericFallsBackToString() {
            // string vs int: compare as strings
            assertThat(DatumComparator.compare(Datum.of("42"), Datum.of(42L)))
                    .isEqualTo("42".compareTo("42"));
        }
    }
}
