package io.github.xinfra.lab.xdb.expression;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class CastExprTest {

    private final EvalContext ctx = new EvalContext();
    private final Row emptyRow = new Row(0);

    private Datum cast(Expression expr, DataType targetType) {
        return new CastExpr(expr, targetType).eval(ctx, emptyRow);
    }

    @Nested
    class ToInteger {

        @Test
        void fromLong() {
            assertThat(cast(Constant.ofLong(42), DataType.BIGINT).toLong()).isEqualTo(42);
        }

        @Test
        void fromDouble() {
            assertThat(cast(Constant.ofDouble(3.9), DataType.INT).toLong()).isEqualTo(3);
        }

        @Test
        void fromString() {
            assertThat(cast(Constant.ofString("123"), DataType.BIGINT).toLong()).isEqualTo(123);
        }

        @Test
        void tinyIntIsAlsoInteger() {
            assertThat(cast(Constant.ofDouble(5.5), DataType.TINYINT).toLong()).isEqualTo(5);
        }
    }

    @Nested
    class ToDouble {

        @Test
        void fromLong() {
            assertThat(cast(Constant.ofLong(42), DataType.DOUBLE).toDouble()).isEqualTo(42.0);
        }

        @Test
        void fromString() {
            assertThat(cast(Constant.ofString("3.14"), DataType.DOUBLE).toDouble()).isEqualTo(3.14);
        }

        @Test
        void toFloat() {
            assertThat(cast(Constant.ofLong(10), DataType.FLOAT).toDouble()).isEqualTo(10.0);
        }
    }

    @Nested
    class ToDecimal {

        @Test
        void fromLong() {
            Datum result = cast(Constant.ofLong(42), DataType.DECIMAL);
            assertThat(result).isInstanceOf(Datum.DecimalDatum.class);
            assertThat(((Datum.DecimalDatum) result).value()).isEqualByComparingTo(BigDecimal.valueOf(42));
        }

        @Test
        void fromDouble() {
            Datum result = cast(Constant.ofDouble(3.14), DataType.DECIMAL);
            assertThat(result).isInstanceOf(Datum.DecimalDatum.class);
        }

        @Test
        void fromDecimal() {
            Expression expr = new Constant(Datum.of(new BigDecimal("1.23")), DataType.DECIMAL);
            Datum result = cast(expr, DataType.DECIMAL);
            assertThat(result).isInstanceOf(Datum.DecimalDatum.class);
        }

        @Test
        void fromInvalidString() {
            Datum result = cast(Constant.ofString("abc"), DataType.DECIMAL);
            assertThat(result.isNull()).isTrue();
        }
    }

    @Nested
    class ToStringType {

        @Test
        void fromLong() {
            assertThat(cast(Constant.ofLong(42), DataType.VARCHAR).toStringValue()).isEqualTo("42");
        }

        @Test
        void fromDouble() {
            assertThat(cast(Constant.ofDouble(3.14), DataType.VARCHAR).toStringValue()).isEqualTo("3.14");
        }

        @Test
        void charType() {
            assertThat(cast(Constant.ofLong(5), DataType.CHAR).toStringValue()).isEqualTo("5");
        }

        @Test
        void textType() {
            assertThat(cast(Constant.ofLong(5), DataType.TEXT).toStringValue()).isEqualTo("5");
        }
    }

    @Nested
    class ToDatetime {

        @Test
        void fromValidString() {
            Datum result = cast(Constant.ofString("2024-06-15 14:30:45"), DataType.DATETIME);
            assertThat(result).isInstanceOf(Datum.DateTimeDatum.class);
            assertThat(((Datum.DateTimeDatum) result).value())
                    .isEqualTo(LocalDateTime.of(2024, 6, 15, 14, 30, 45));
        }

        @Test
        void fromInvalidString() {
            Datum result = cast(Constant.ofString("not a date"), DataType.DATETIME);
            assertThat(result.isNull()).isTrue();
        }

        @Test
        void fromDateTimeDatumIsIdentity() {
            LocalDateTime dt = LocalDateTime.of(2024, 1, 1, 0, 0, 0);
            Expression expr = new Constant(Datum.of(dt), DataType.DATETIME);
            Datum result = cast(expr, DataType.DATETIME);
            assertThat(result).isInstanceOf(Datum.DateTimeDatum.class);
            assertThat(((Datum.DateTimeDatum) result).value()).isEqualTo(dt);
        }

        @Test
        void timestampAlsoWorks() {
            Datum result = cast(Constant.ofString("2024-06-15 00:00:00"), DataType.TIMESTAMP);
            assertThat(result).isInstanceOf(Datum.DateTimeDatum.class);
        }
    }

    @Nested
    class NullHandling {

        @Test
        void nullInputReturnsNull() {
            Datum result = cast(Constant.ofNull(), DataType.BIGINT);
            assertThat(result.isNull()).isTrue();
        }
    }

    @Test
    void returnTypeMatchesTarget() {
        CastExpr expr = new CastExpr(Constant.ofLong(1), DataType.VARCHAR);
        assertThat(expr.returnType()).isEqualTo(DataType.VARCHAR);
    }
}
