package io.github.xinfra.lab.xdb.expression;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UnaryOpTest {

    private final EvalContext ctx = new EvalContext();
    private final Row emptyRow = new Row(0);

    private Datum eval(UnaryOp.Op op, Expression operand) {
        return new UnaryOp(op, operand).eval(ctx, emptyRow);
    }

    @Nested
    class Not {

        @Test
        void notTrue() {
            assertThat(eval(UnaryOp.Op.NOT, Constant.ofLong(1)).toLong()).isEqualTo(0);
        }

        @Test
        void notFalse() {
            assertThat(eval(UnaryOp.Op.NOT, Constant.ofLong(0)).toLong()).isEqualTo(1);
        }

        @Test
        void notNull() {
            assertThat(eval(UnaryOp.Op.NOT, Constant.ofNull()).isNull()).isTrue();
        }

        @Test
        void notNonZero() {
            assertThat(eval(UnaryOp.Op.NOT, Constant.ofLong(42)).toLong()).isEqualTo(0);
        }
    }

    @Nested
    class Negation {

        @Test
        void negateInt() {
            assertThat(eval(UnaryOp.Op.NEG, Constant.ofLong(42)).toLong()).isEqualTo(-42);
        }

        @Test
        void negateNegativeInt() {
            assertThat(eval(UnaryOp.Op.NEG, Constant.ofLong(-5)).toLong()).isEqualTo(5);
        }

        @Test
        void negateDouble() {
            assertThat(eval(UnaryOp.Op.NEG, Constant.ofDouble(3.14)).toDouble()).isEqualTo(-3.14);
        }

        @Test
        void negateNull() {
            assertThat(eval(UnaryOp.Op.NEG, Constant.ofNull()).isNull()).isTrue();
        }

        @Test
        void negateZero() {
            assertThat(eval(UnaryOp.Op.NEG, Constant.ofLong(0)).toLong()).isEqualTo(0);
        }
    }

    @Nested
    class IsNull {

        @Test
        void nullIsNull() {
            assertThat(eval(UnaryOp.Op.IS_NULL, Constant.ofNull()).toLong()).isEqualTo(1);
        }

        @Test
        void nonNullIsNotNull() {
            assertThat(eval(UnaryOp.Op.IS_NULL, Constant.ofLong(42)).toLong()).isEqualTo(0);
        }

        @Test
        void stringIsNotNull() {
            assertThat(eval(UnaryOp.Op.IS_NULL, Constant.ofString("hello")).toLong()).isEqualTo(0);
        }
    }

    @Nested
    class IsNotNull {

        @Test
        void nullIsNotNotNull() {
            assertThat(eval(UnaryOp.Op.IS_NOT_NULL, Constant.ofNull()).toLong()).isEqualTo(0);
        }

        @Test
        void nonNullIsNotNull() {
            assertThat(eval(UnaryOp.Op.IS_NOT_NULL, Constant.ofLong(42)).toLong()).isEqualTo(1);
        }
    }

    @Nested
    class ReturnType {

        @Test
        void notReturnsBoolean() {
            UnaryOp op = new UnaryOp(UnaryOp.Op.NOT, Constant.ofLong(1));
            assertThat(op.returnType()).isEqualTo(DataType.BOOLEAN);
        }

        @Test
        void isNullReturnsBoolean() {
            UnaryOp op = new UnaryOp(UnaryOp.Op.IS_NULL, Constant.ofLong(1));
            assertThat(op.returnType()).isEqualTo(DataType.BOOLEAN);
        }

        @Test
        void isNotNullReturnsBoolean() {
            UnaryOp op = new UnaryOp(UnaryOp.Op.IS_NOT_NULL, Constant.ofLong(1));
            assertThat(op.returnType()).isEqualTo(DataType.BOOLEAN);
        }

        @Test
        void negReturnsSameType() {
            UnaryOp op = new UnaryOp(UnaryOp.Op.NEG, Constant.ofDouble(1.0));
            assertThat(op.returnType()).isEqualTo(DataType.DOUBLE);
        }
    }
}
