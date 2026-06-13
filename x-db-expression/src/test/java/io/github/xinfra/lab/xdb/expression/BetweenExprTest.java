package io.github.xinfra.lab.xdb.expression;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BetweenExprTest {

    private final EvalContext ctx = new EvalContext();
    private final Row emptyRow = new Row(0);

    @Nested
    class Between {

        @Test
        void valueInRange() {
            BetweenExpr expr = new BetweenExpr(
                    Constant.ofLong(5),
                    Constant.ofLong(1),
                    Constant.ofLong(10),
                    false
            );
            assertThat(expr.eval(ctx, emptyRow).toLong()).isEqualTo(1);
        }

        @Test
        void valueAtLowBound() {
            BetweenExpr expr = new BetweenExpr(
                    Constant.ofLong(1),
                    Constant.ofLong(1),
                    Constant.ofLong(10),
                    false
            );
            assertThat(expr.eval(ctx, emptyRow).toLong()).isEqualTo(1);
        }

        @Test
        void valueAtHighBound() {
            BetweenExpr expr = new BetweenExpr(
                    Constant.ofLong(10),
                    Constant.ofLong(1),
                    Constant.ofLong(10),
                    false
            );
            assertThat(expr.eval(ctx, emptyRow).toLong()).isEqualTo(1);
        }

        @Test
        void valueBelowRange() {
            BetweenExpr expr = new BetweenExpr(
                    Constant.ofLong(0),
                    Constant.ofLong(1),
                    Constant.ofLong(10),
                    false
            );
            assertThat(expr.eval(ctx, emptyRow).toLong()).isEqualTo(0);
        }

        @Test
        void valueAboveRange() {
            BetweenExpr expr = new BetweenExpr(
                    Constant.ofLong(11),
                    Constant.ofLong(1),
                    Constant.ofLong(10),
                    false
            );
            assertThat(expr.eval(ctx, emptyRow).toLong()).isEqualTo(0);
        }

        @Test
        void withDoubles() {
            BetweenExpr expr = new BetweenExpr(
                    Constant.ofDouble(5.5),
                    Constant.ofDouble(1.0),
                    Constant.ofDouble(10.0),
                    false
            );
            assertThat(expr.eval(ctx, emptyRow).toLong()).isEqualTo(1);
        }

        @Test
        void withStrings() {
            BetweenExpr expr = new BetweenExpr(
                    Constant.ofString("banana"),
                    Constant.ofString("apple"),
                    Constant.ofString("cherry"),
                    false
            );
            assertThat(expr.eval(ctx, emptyRow).toLong()).isEqualTo(1);
        }
    }

    @Nested
    class NotBetween {

        @Test
        void valueInRange() {
            BetweenExpr expr = new BetweenExpr(
                    Constant.ofLong(5),
                    Constant.ofLong(1),
                    Constant.ofLong(10),
                    true
            );
            assertThat(expr.eval(ctx, emptyRow).toLong()).isEqualTo(0);
        }

        @Test
        void valueOutOfRange() {
            BetweenExpr expr = new BetweenExpr(
                    Constant.ofLong(0),
                    Constant.ofLong(1),
                    Constant.ofLong(10),
                    true
            );
            assertThat(expr.eval(ctx, emptyRow).toLong()).isEqualTo(1);
        }
    }

    @Nested
    class NullHandling {

        @Test
        void nullExprReturnsNull() {
            BetweenExpr expr = new BetweenExpr(
                    Constant.ofNull(),
                    Constant.ofLong(1),
                    Constant.ofLong(10),
                    false
            );
            assertThat(expr.eval(ctx, emptyRow).isNull()).isTrue();
        }

        @Test
        void nullLowReturnsNull() {
            BetweenExpr expr = new BetweenExpr(
                    Constant.ofLong(5),
                    Constant.ofNull(),
                    Constant.ofLong(10),
                    false
            );
            assertThat(expr.eval(ctx, emptyRow).isNull()).isTrue();
        }

        @Test
        void nullHighReturnsNull() {
            BetweenExpr expr = new BetweenExpr(
                    Constant.ofLong(5),
                    Constant.ofLong(1),
                    Constant.ofNull(),
                    false
            );
            assertThat(expr.eval(ctx, emptyRow).isNull()).isTrue();
        }
    }

    @Test
    void returnTypeIsBoolean() {
        BetweenExpr expr = new BetweenExpr(
                Constant.ofLong(5),
                Constant.ofLong(1),
                Constant.ofLong(10),
                false
        );
        assertThat(expr.returnType()).isEqualTo(DataType.BOOLEAN);
    }
}
