package io.github.xinfra.lab.xdb.expression;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class ScalarFunctionsTest {

    private final EvalContext ctx = new EvalContext();

    private Datum eval(String name, Datum... args) {
        return ScalarFunctions.eval(name, java.util.List.of(args), ctx);
    }

    // ==================== SUBSTRING ====================

    @Nested
    class Substring {

        @Test
        void negativeStartCountsFromEnd() {
            Datum result = eval("SUBSTRING", Datum.of("hello"), Datum.of(-3L));
            assertThat(result.toStringValue()).isEqualTo("llo");
        }

        @Test
        void negativeOneReturnsLastChar() {
            Datum result = eval("SUBSTRING", Datum.of("hello"), Datum.of(-1L));
            assertThat(result.toStringValue()).isEqualTo("o");
        }

        @Test
        void zeroPositionReturnsEmpty() {
            Datum result = eval("SUBSTRING", Datum.of("hello"), Datum.of(0L));
            assertThat(result.toStringValue()).isEmpty();
        }

        @Test
        void positiveStartIsOneIndexed() {
            Datum result = eval("SUBSTRING", Datum.of("hello"), Datum.of(2L));
            assertThat(result.toStringValue()).isEqualTo("ello");
        }

        @Test
        void positiveStartWithLength() {
            Datum result = eval("SUBSTRING", Datum.of("hello"), Datum.of(2L), Datum.of(3L));
            assertThat(result.toStringValue()).isEqualTo("ell");
        }

        @Test
        void negativeStartWithLength() {
            Datum result = eval("SUBSTRING", Datum.of("hello"), Datum.of(-3L), Datum.of(2L));
            assertThat(result.toStringValue()).isEqualTo("ll");
        }
    }

    // ==================== ROUND ====================

    @Nested
    class Round {

        @Test
        void roundHalfUp_notBankersRounding() {
            Datum result = eval("ROUND", Datum.of(new BigDecimal("2.5")));
            assertThat(result.toLong()).isEqualTo(3);
        }

        @Test
        void roundWithPrecision() {
            Datum result = eval("ROUND", Datum.of(new BigDecimal("2.55")), Datum.of(1L));
            assertThat(result.toDouble()).isEqualTo(2.6);
        }

        @Test
        void roundNegativeHalfUp() {
            Datum result = eval("ROUND", Datum.of(new BigDecimal("-1.5")));
            assertThat(result.toLong()).isEqualTo(-2);
        }

        @Test
        void roundWithTwoDecimalPlaces() {
            Datum result = eval("ROUND", Datum.of(new BigDecimal("123.456")), Datum.of(2L));
            assertThat(result.toDouble()).isEqualTo(123.46);
        }
    }

    // ==================== ABS ====================

    @Nested
    class Abs {

        @Test
        void absLongMinValueReturnsDouble() {
            Datum result = eval("ABS", Datum.of(Long.MIN_VALUE));
            assertThat(result).isInstanceOf(Datum.DoubleDatum.class);
            assertThat(result.toDouble()).isEqualTo(9.223372036854776E18);
        }

        @Test
        void absNegativeInteger() {
            Datum result = eval("ABS", Datum.of(-42L));
            assertThat(result.toLong()).isEqualTo(42);
        }

        @Test
        void absZero() {
            Datum result = eval("ABS", Datum.of(0L));
            assertThat(result.toLong()).isEqualTo(0);
        }
    }

    // ==================== CONCAT ====================

    @Nested
    class Concat {

        @Test
        void concatWithNullReturnsNull() {
            Datum result = eval("CONCAT", Datum.of("hello"), Datum.nil(), Datum.of("world"));
            assertThat(result.isNull()).isTrue();
        }
    }

    // ==================== LENGTH ====================

    @Nested
    class Length {

        @Test
        void lengthOfString() {
            Datum result = eval("LENGTH", Datum.of("hello"));
            assertThat(result.toLong()).isEqualTo(5);
        }
    }

    // ==================== UPPER / LOWER ====================

    @Nested
    class UpperLower {

        @Test
        void upper() {
            Datum result = eval("UPPER", Datum.of("hello"));
            assertThat(result.toStringValue()).isEqualTo("HELLO");
        }

        @Test
        void lower() {
            Datum result = eval("LOWER", Datum.of("HELLO"));
            assertThat(result.toStringValue()).isEqualTo("hello");
        }
    }

    // ==================== MOD ====================

    @Nested
    class Mod {

        @Test
        void modDivisionByZeroReturnsNull() {
            Datum result = eval("MOD", Datum.of(10L), Datum.of(0L));
            assertThat(result.isNull()).isTrue();
        }
    }
}
