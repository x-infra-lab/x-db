package io.github.xinfra.lab.xdb.expression;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BinaryOpTest {

    private final EvalContext ctx = new EvalContext();
    private final Row emptyRow = new Row(0);

    private Datum eval(Expression left, BinaryOp.Op op, Expression right) {
        return new BinaryOp(left, op, right).eval(ctx, emptyRow);
    }

    private Datum eval(long left, BinaryOp.Op op, long right) {
        return eval(Constant.ofLong(left), op, Constant.ofLong(right));
    }

    private Datum eval(double left, BinaryOp.Op op, double right) {
        return eval(Constant.ofDouble(left), op, Constant.ofDouble(right));
    }

    // ==================== Arithmetic ====================

    @Nested
    class Arithmetic {

        @Test
        void addIntegers() {
            assertThat(eval(3, BinaryOp.Op.ADD, 4).toLong()).isEqualTo(7);
        }

        @Test
        void addDoubles() {
            assertThat(eval(1.5, BinaryOp.Op.ADD, 2.5).toDouble()).isEqualTo(4.0);
        }

        @Test
        void addMixed_intAndDouble_producesDouble() {
            Datum result = eval(Constant.ofLong(3), BinaryOp.Op.ADD, Constant.ofDouble(1.5));
            assertThat(result).isInstanceOf(Datum.DoubleDatum.class);
            assertThat(result.toDouble()).isEqualTo(4.5);
        }

        @Test
        void subtractIntegers() {
            assertThat(eval(10, BinaryOp.Op.SUB, 3).toLong()).isEqualTo(7);
        }

        @Test
        void subtractDoubles() {
            assertThat(eval(5.0, BinaryOp.Op.SUB, 2.0).toDouble()).isEqualTo(3.0);
        }

        @Test
        void multiplyIntegers() {
            assertThat(eval(6, BinaryOp.Op.MUL, 7).toLong()).isEqualTo(42);
        }

        @Test
        void multiplyDoubles() {
            assertThat(eval(2.0, BinaryOp.Op.MUL, 3.5).toDouble()).isEqualTo(7.0);
        }

        @Test
        void divide() {
            Datum result = eval(10, BinaryOp.Op.DIV, 3);
            assertThat(result).isInstanceOf(Datum.DoubleDatum.class);
            assertThat(result.toDouble()).isCloseTo(3.333333, org.assertj.core.data.Offset.offset(0.001));
        }

        @Test
        void divideByZero() {
            assertThat(eval(10, BinaryOp.Op.DIV, 0).isNull()).isTrue();
        }

        @Test
        void modIntegers() {
            assertThat(eval(10, BinaryOp.Op.MOD, 3).toLong()).isEqualTo(1);
        }

        @Test
        void modByZero() {
            assertThat(eval(10, BinaryOp.Op.MOD, 0).isNull()).isTrue();
        }

        @Test
        void modDoubles() {
            Datum result = eval(10.5, BinaryOp.Op.MOD, 3.0);
            assertThat(result.toDouble()).isCloseTo(1.5, org.assertj.core.data.Offset.offset(0.0001));
        }

        @Test
        void modDoubleByZero() {
            assertThat(eval(10.5, BinaryOp.Op.MOD, 0.0).isNull()).isTrue();
        }

        @Test
        void intDiv() {
            assertThat(eval(10, BinaryOp.Op.INT_DIV, 3).toLong()).isEqualTo(3);
        }

        @Test
        void intDivByZero() {
            assertThat(eval(10, BinaryOp.Op.INT_DIV, 0).isNull()).isTrue();
        }

        @Test
        void nullInArithmetic() {
            assertThat(eval(Constant.ofNull(), BinaryOp.Op.ADD, Constant.ofLong(1)).isNull()).isTrue();
            assertThat(eval(Constant.ofLong(1), BinaryOp.Op.ADD, Constant.ofNull()).isNull()).isTrue();
        }
    }

    // ==================== Comparison ====================

    @Nested
    class Comparison {

        @Test
        void equal() {
            assertThat(eval(5, BinaryOp.Op.EQ, 5).toLong()).isEqualTo(1);
            assertThat(eval(5, BinaryOp.Op.EQ, 6).toLong()).isEqualTo(0);
        }

        @Test
        void notEqual() {
            assertThat(eval(5, BinaryOp.Op.NE, 6).toLong()).isEqualTo(1);
            assertThat(eval(5, BinaryOp.Op.NE, 5).toLong()).isEqualTo(0);
        }

        @Test
        void lessThan() {
            assertThat(eval(3, BinaryOp.Op.LT, 5).toLong()).isEqualTo(1);
            assertThat(eval(5, BinaryOp.Op.LT, 5).toLong()).isEqualTo(0);
            assertThat(eval(7, BinaryOp.Op.LT, 5).toLong()).isEqualTo(0);
        }

        @Test
        void lessOrEqual() {
            assertThat(eval(3, BinaryOp.Op.LE, 5).toLong()).isEqualTo(1);
            assertThat(eval(5, BinaryOp.Op.LE, 5).toLong()).isEqualTo(1);
            assertThat(eval(7, BinaryOp.Op.LE, 5).toLong()).isEqualTo(0);
        }

        @Test
        void greaterThan() {
            assertThat(eval(7, BinaryOp.Op.GT, 5).toLong()).isEqualTo(1);
            assertThat(eval(5, BinaryOp.Op.GT, 5).toLong()).isEqualTo(0);
        }

        @Test
        void greaterOrEqual() {
            assertThat(eval(7, BinaryOp.Op.GE, 5).toLong()).isEqualTo(1);
            assertThat(eval(5, BinaryOp.Op.GE, 5).toLong()).isEqualTo(1);
            assertThat(eval(3, BinaryOp.Op.GE, 5).toLong()).isEqualTo(0);
        }

        @Test
        void stringComparison() {
            Datum result = eval(Constant.ofString("abc"), BinaryOp.Op.LT, Constant.ofString("abd"));
            assertThat(result.toLong()).isEqualTo(1);
        }

        @Test
        void nullInComparison() {
            assertThat(eval(Constant.ofNull(), BinaryOp.Op.EQ, Constant.ofLong(1)).isNull()).isTrue();
            assertThat(eval(Constant.ofLong(1), BinaryOp.Op.EQ, Constant.ofNull()).isNull()).isTrue();
        }
    }

    // ==================== Logical ====================

    @Nested
    class Logical {

        @Test
        void andTrueTrue() {
            assertThat(eval(1, BinaryOp.Op.AND, 1).toLong()).isEqualTo(1);
        }

        @Test
        void andTrueFalse() {
            assertThat(eval(1, BinaryOp.Op.AND, 0).toLong()).isEqualTo(0);
        }

        @Test
        void andFalseTrue() {
            assertThat(eval(0, BinaryOp.Op.AND, 1).toLong()).isEqualTo(0);
        }

        @Test
        void andFalseFalse() {
            assertThat(eval(0, BinaryOp.Op.AND, 0).toLong()).isEqualTo(0);
        }

        @Test
        void andShortCircuits_falseSkipsRight() {
            // Left is false (0), right should not matter
            Datum result = eval(0, BinaryOp.Op.AND, 999);
            assertThat(result.toLong()).isEqualTo(0);
        }

        @Test
        void andWithNull() {
            assertThat(eval(Constant.ofNull(), BinaryOp.Op.AND, Constant.ofLong(1)).isNull()).isTrue();
            // true AND null => null
            Datum result = eval(Constant.ofLong(1), BinaryOp.Op.AND, Constant.ofNull());
            assertThat(result.isNull()).isTrue();
        }

        @Test
        void orTrueTrue() {
            assertThat(eval(1, BinaryOp.Op.OR, 1).toLong()).isEqualTo(1);
        }

        @Test
        void orTrueFalse() {
            assertThat(eval(1, BinaryOp.Op.OR, 0).toLong()).isEqualTo(1);
        }

        @Test
        void orFalseTrue() {
            assertThat(eval(0, BinaryOp.Op.OR, 1).toLong()).isEqualTo(1);
        }

        @Test
        void orFalseFalse() {
            assertThat(eval(0, BinaryOp.Op.OR, 0).toLong()).isEqualTo(0);
        }

        @Test
        void orShortCircuits_trueSkipsRight() {
            Datum result = eval(1, BinaryOp.Op.OR, 999);
            assertThat(result.toLong()).isEqualTo(1);
        }

        @Test
        void orWithNull() {
            // null OR null => null
            assertThat(eval(Constant.ofNull(), BinaryOp.Op.OR, Constant.ofNull()).isNull()).isTrue();
            // false OR null => null
            assertThat(eval(Constant.ofLong(0), BinaryOp.Op.OR, Constant.ofNull()).isNull()).isTrue();
        }
    }

    // ==================== returnType ====================

    @Nested
    class ReturnType {

        @Test
        void comparisonReturnsBoolean() {
            BinaryOp op = new BinaryOp(Constant.ofLong(1), BinaryOp.Op.EQ, Constant.ofLong(2));
            assertThat(op.returnType()).isEqualTo(DataType.BOOLEAN);
        }

        @Test
        void logicalReturnsBoolean() {
            BinaryOp op = new BinaryOp(Constant.ofLong(1), BinaryOp.Op.AND, Constant.ofLong(2));
            assertThat(op.returnType()).isEqualTo(DataType.BOOLEAN);
        }

        @Test
        void intPlusIntReturnsBigint() {
            BinaryOp op = new BinaryOp(Constant.ofLong(1), BinaryOp.Op.ADD, Constant.ofLong(2));
            assertThat(op.returnType()).isEqualTo(DataType.BIGINT);
        }

        @Test
        void intPlusDoubleReturnsDouble() {
            BinaryOp op = new BinaryOp(Constant.ofLong(1), BinaryOp.Op.ADD, Constant.ofDouble(2.0));
            assertThat(op.returnType()).isEqualTo(DataType.DOUBLE);
        }
    }

    // ==================== toSQL ====================

    @Test
    void toSQL() {
        BinaryOp op = new BinaryOp(Constant.ofLong(1), BinaryOp.Op.ADD, Constant.ofLong(2));
        assertThat(op.toSQL()).isEqualTo("(1 + 2)");
    }
}
