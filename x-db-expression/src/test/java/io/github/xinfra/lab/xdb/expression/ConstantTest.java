package io.github.xinfra.lab.xdb.expression;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ConstantTest {

    private final EvalContext ctx = new EvalContext();
    private final Row emptyRow = new Row(0);

    @Test
    void ofNull() {
        Constant c = Constant.ofNull();
        assertThat(c.eval(ctx, emptyRow).isNull()).isTrue();
        assertThat(c.returnType()).isEqualTo(DataType.NULL);
    }

    @Test
    void ofLong() {
        Constant c = Constant.ofLong(42);
        assertThat(c.eval(ctx, emptyRow).toLong()).isEqualTo(42);
        assertThat(c.returnType()).isEqualTo(DataType.BIGINT);
    }

    @Test
    void ofDouble() {
        Constant c = Constant.ofDouble(3.14);
        assertThat(c.eval(ctx, emptyRow).toDouble()).isEqualTo(3.14);
        assertThat(c.returnType()).isEqualTo(DataType.DOUBLE);
    }

    @Test
    void ofString() {
        Constant c = Constant.ofString("hello");
        assertThat(c.eval(ctx, emptyRow).toStringValue()).isEqualTo("hello");
        assertThat(c.returnType()).isEqualTo(DataType.VARCHAR);
    }

    @Test
    void ofTrue() {
        Constant c = Constant.ofTrue();
        assertThat(c.eval(ctx, emptyRow).toLong()).isEqualTo(1);
        assertThat(c.returnType()).isEqualTo(DataType.BOOLEAN);
    }

    @Test
    void ofFalse() {
        Constant c = Constant.ofFalse();
        assertThat(c.eval(ctx, emptyRow).toLong()).isEqualTo(0);
        assertThat(c.returnType()).isEqualTo(DataType.BOOLEAN);
    }

    @Test
    void evalAlwaysReturnsSameValue() {
        Constant c = Constant.ofLong(99);
        Datum d1 = c.eval(ctx, emptyRow);
        Datum d2 = c.eval(ctx, emptyRow);
        assertThat(d1).isSameAs(d2);
    }

    @Test
    void valueAccessor() {
        Constant c = Constant.ofLong(7);
        assertThat(c.value()).isEqualTo(Datum.of(7L));
    }

    @Test
    void toStringReflectsValue() {
        assertThat(Constant.ofLong(42).toString()).isEqualTo("42");
        assertThat(Constant.ofString("abc").toString()).isEqualTo("abc");
        assertThat(Constant.ofNull().toString()).isEqualTo("NULL");
    }
}
