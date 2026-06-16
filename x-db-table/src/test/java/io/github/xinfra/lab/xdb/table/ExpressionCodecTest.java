package io.github.xinfra.lab.xdb.table;

import io.github.xinfra.lab.xdb.expression.BetweenExpr;
import io.github.xinfra.lab.xdb.expression.BinaryOp;
import io.github.xinfra.lab.xdb.expression.CaseWhenExpr;
import io.github.xinfra.lab.xdb.expression.CastExpr;
import io.github.xinfra.lab.xdb.expression.ColumnRef;
import io.github.xinfra.lab.xdb.expression.Constant;
import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.EvalContext;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.expression.FunctionCallExpr;
import io.github.xinfra.lab.xdb.expression.InExpr;
import io.github.xinfra.lab.xdb.expression.LikeExpr;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.expression.UnaryOp;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ExpressionCodecTest {

    private final EvalContext ctx = new EvalContext();

    // --- Datum wire roundtrip ---

    @Test
    void datumNullRoundTrip() {
        Datum d = Datum.nil();
        assertThat(ExpressionCodec.decodeDatumWire(ExpressionCodec.encodeDatumWire(d)).isNull()).isTrue();
    }

    @Test
    void datumIntRoundTrip() {
        for (long v : new long[]{0, 1, -1, Long.MAX_VALUE, Long.MIN_VALUE, 42}) {
            Datum d = Datum.of(v);
            Datum decoded = ExpressionCodec.decodeDatumWire(ExpressionCodec.encodeDatumWire(d));
            assertThat(decoded.toLong()).isEqualTo(v);
        }
    }

    @Test
    void datumDoubleRoundTrip() {
        for (double v : new double[]{0.0, 1.5, -3.14, Double.MAX_VALUE, Double.MIN_VALUE}) {
            Datum d = Datum.of(v);
            Datum decoded = ExpressionCodec.decodeDatumWire(ExpressionCodec.encodeDatumWire(d));
            assertThat(decoded.toDouble()).isEqualTo(v);
        }
    }

    @Test
    void datumDecimalRoundTrip() {
        BigDecimal v = new BigDecimal("12345.6789");
        Datum d = Datum.of(v);
        Datum decoded = ExpressionCodec.decodeDatumWire(ExpressionCodec.encodeDatumWire(d));
        assertThat(decoded).isInstanceOf(Datum.DecimalDatum.class);
        assertThat(((Datum.DecimalDatum) decoded).value()).isEqualByComparingTo(v);
    }

    @Test
    void datumStringRoundTrip() {
        for (String v : new String[]{"", "hello", "你好世界", "special chars: \t\n\r"}) {
            Datum d = Datum.of(v);
            Datum decoded = ExpressionCodec.decodeDatumWire(ExpressionCodec.encodeDatumWire(d));
            assertThat(decoded.toStringValue()).isEqualTo(v);
        }
    }

    @Test
    void datumBytesRoundTrip() {
        byte[] v = new byte[]{0, 1, 2, (byte) 0xFF, 127, -128};
        Datum d = Datum.of(v);
        Datum decoded = ExpressionCodec.decodeDatumWire(ExpressionCodec.encodeDatumWire(d));
        assertThat(decoded).isInstanceOf(Datum.BytesDatum.class);
        assertThat(((Datum.BytesDatum) decoded).value()).isEqualTo(v);
    }

    @Test
    void datumDateTimeRoundTrip() {
        LocalDateTime v = LocalDateTime.of(2024, 6, 15, 10, 30, 45);
        Datum d = Datum.of(v);
        Datum decoded = ExpressionCodec.decodeDatumWire(ExpressionCodec.encodeDatumWire(d));
        assertThat(decoded).isInstanceOf(Datum.DateTimeDatum.class);
        assertThat(((Datum.DateTimeDatum) decoded).value()).isEqualTo(v);
    }

    // --- Expression roundtrip ---

    @Test
    void constantRoundTrip() {
        Expression expr = Constant.ofLong(42);
        Expression decoded = roundTrip(expr);
        assertThat(decoded).isInstanceOf(Constant.class);
        assertThat(decoded.eval(ctx, new Row(0)).toLong()).isEqualTo(42);
        assertThat(decoded.returnType()).isEqualTo(DataType.BIGINT);
    }

    @Test
    void constantNullRoundTrip() {
        Expression expr = Constant.ofNull();
        Expression decoded = roundTrip(expr);
        assertThat(decoded.eval(ctx, new Row(0)).isNull()).isTrue();
        assertThat(decoded.returnType()).isEqualTo(DataType.NULL);
    }

    @Test
    void columnRefRoundTrip() {
        Expression expr = new ColumnRef("users", "name", 1, DataType.VARCHAR);
        Expression decoded = roundTrip(expr);
        assertThat(decoded).isInstanceOf(ColumnRef.class);
        ColumnRef col = (ColumnRef) decoded;
        assertThat(col.tableName()).isEqualTo("users");
        assertThat(col.columnName()).isEqualTo("name");
        assertThat(col.index()).isEqualTo(1);
        assertThat(col.returnType()).isEqualTo(DataType.VARCHAR);
    }

    @Test
    void columnRefNullTableRoundTrip() {
        Expression expr = new ColumnRef(null, "id", 0, DataType.BIGINT);
        Expression decoded = roundTrip(expr);
        ColumnRef col = (ColumnRef) decoded;
        assertThat(col.tableName()).isNull();
        assertThat(col.columnName()).isEqualTo("id");
    }

    @Test
    void binaryOpRoundTrip() {
        Expression expr = new BinaryOp(Constant.ofLong(10), BinaryOp.Op.ADD, Constant.ofLong(20));
        Expression decoded = roundTrip(expr);
        assertThat(decoded.eval(ctx, new Row(0)).toLong()).isEqualTo(30);
    }

    @Test
    void binaryOpComparisonRoundTrip() {
        Expression expr = new BinaryOp(
                new ColumnRef(null, "age", 0, DataType.BIGINT),
                BinaryOp.Op.GT,
                Constant.ofLong(18));
        Expression decoded = roundTrip(expr);
        Row row = new Row(new Datum[]{Datum.of(25)});
        assertThat(decoded.eval(ctx, row).toBoolean()).isTrue();
    }

    @Test
    void unaryOpRoundTrip() {
        Expression expr = new UnaryOp(UnaryOp.Op.NEG, Constant.ofLong(5));
        Expression decoded = roundTrip(expr);
        assertThat(decoded.eval(ctx, new Row(0)).toLong()).isEqualTo(-5);
    }

    @Test
    void unaryIsNullRoundTrip() {
        Expression expr = new UnaryOp(UnaryOp.Op.IS_NULL, Constant.ofNull());
        Expression decoded = roundTrip(expr);
        assertThat(decoded.eval(ctx, new Row(0)).toBoolean()).isTrue();
    }

    @Test
    void likeExprRoundTrip() {
        Expression expr = new LikeExpr(
                new ColumnRef(null, "name", 0, DataType.VARCHAR),
                Constant.ofString("%test%"),
                false);
        Expression decoded = roundTrip(expr);
        Row row = new Row(new Datum[]{Datum.of("my_test_string")});
        assertThat(decoded.eval(ctx, row).toBoolean()).isTrue();
    }

    @Test
    void likeExprNotRoundTrip() {
        Expression expr = new LikeExpr(
                Constant.ofString("hello"),
                Constant.ofString("%xyz%"),
                true);
        Expression decoded = roundTrip(expr);
        assertThat(decoded.eval(ctx, new Row(0)).toBoolean()).isTrue();
    }

    @Test
    void inExprRoundTrip() {
        Expression expr = new InExpr(
                new ColumnRef(null, "status", 0, DataType.BIGINT),
                List.of(Constant.ofLong(1), Constant.ofLong(2), Constant.ofLong(3)),
                false);
        Expression decoded = roundTrip(expr);
        Row row = new Row(new Datum[]{Datum.of(2)});
        assertThat(decoded.eval(ctx, row).toBoolean()).isTrue();
    }

    @Test
    void inExprNotRoundTrip() {
        Expression expr = new InExpr(
                Constant.ofLong(5),
                List.of(Constant.ofLong(1), Constant.ofLong(2)),
                true);
        Expression decoded = roundTrip(expr);
        assertThat(decoded.eval(ctx, new Row(0)).toBoolean()).isTrue();
    }

    @Test
    void betweenExprRoundTrip() {
        Expression expr = new BetweenExpr(
                new ColumnRef(null, "age", 0, DataType.BIGINT),
                Constant.ofLong(18), Constant.ofLong(65), false);
        Expression decoded = roundTrip(expr);
        Row row = new Row(new Datum[]{Datum.of(30)});
        assertThat(decoded.eval(ctx, row).toBoolean()).isTrue();
    }

    @Test
    void castExprRoundTrip() {
        Expression expr = new CastExpr(Constant.ofString("42"), DataType.BIGINT);
        Expression decoded = roundTrip(expr);
        assertThat(decoded).isInstanceOf(CastExpr.class);
        assertThat(((CastExpr) decoded).targetType()).isEqualTo(DataType.BIGINT);
    }

    @Test
    void caseWhenSimpleRoundTrip() {
        Expression expr = new CaseWhenExpr(
                null,
                List.of(
                        new CaseWhenExpr.WhenClause(Constant.ofTrue(), Constant.ofString("yes")),
                        new CaseWhenExpr.WhenClause(Constant.ofFalse(), Constant.ofString("no"))),
                Constant.ofString("default"));
        Expression decoded = roundTrip(expr);
        assertThat(decoded.eval(ctx, new Row(0)).toStringValue()).isEqualTo("yes");
    }

    @Test
    void caseWhenWithCompareRoundTrip() {
        Expression expr = new CaseWhenExpr(
                Constant.ofLong(2),
                List.of(
                        new CaseWhenExpr.WhenClause(Constant.ofLong(1), Constant.ofString("one")),
                        new CaseWhenExpr.WhenClause(Constant.ofLong(2), Constant.ofString("two"))),
                null);
        Expression decoded = roundTrip(expr);
        assertThat(decoded.eval(ctx, new Row(0)).toStringValue()).isEqualTo("two");
    }

    @Test
    void functionCallRoundTrip() {
        Expression expr = new FunctionCallExpr("UPPER", List.of(Constant.ofString("hello")));
        Expression decoded = roundTrip(expr);
        assertThat(decoded).isInstanceOf(FunctionCallExpr.class);
        assertThat(((FunctionCallExpr) decoded).name()).isEqualTo("UPPER");
        assertThat(decoded.eval(ctx, new Row(0)).toStringValue()).isEqualTo("HELLO");
    }

    @Test
    void nestedExpressionRoundTrip() {
        // (col0 > 10 AND col1 LIKE '%test%') OR col0 IS NULL
        Expression nested = new BinaryOp(
                new BinaryOp(
                        new BinaryOp(
                                new ColumnRef(null, "age", 0, DataType.BIGINT),
                                BinaryOp.Op.GT,
                                Constant.ofLong(10)),
                        BinaryOp.Op.AND,
                        new LikeExpr(
                                new ColumnRef(null, "name", 1, DataType.VARCHAR),
                                Constant.ofString("%test%"),
                                false)),
                BinaryOp.Op.OR,
                new UnaryOp(UnaryOp.Op.IS_NULL,
                        new ColumnRef(null, "age", 0, DataType.BIGINT)));

        Expression decoded = roundTrip(nested);

        Row match = new Row(new Datum[]{Datum.of(20), Datum.of("my_test")});
        assertThat(decoded.eval(ctx, match).toBoolean()).isTrue();

        Row noMatch = new Row(new Datum[]{Datum.of(5), Datum.of("other")});
        assertThat(decoded.eval(ctx, noMatch).toBoolean()).isFalse();

        Row nullAge = new Row(new Datum[]{Datum.nil(), Datum.of("other")});
        assertThat(decoded.eval(ctx, nullAge).toBoolean()).isTrue();
    }

    @Test
    void allBinaryOpOrdinals() {
        for (BinaryOp.Op op : BinaryOp.Op.values()) {
            Expression expr = new BinaryOp(Constant.ofLong(1), op, Constant.ofLong(1));
            Expression decoded = roundTrip(expr);
            assertThat(decoded).isInstanceOf(BinaryOp.class);
            assertThat(((BinaryOp) decoded).op()).isEqualTo(op);
        }
    }

    @Test
    void allUnaryOpOrdinals() {
        for (UnaryOp.Op op : UnaryOp.Op.values()) {
            Expression expr = new UnaryOp(op, Constant.ofLong(1));
            Expression decoded = roundTrip(expr);
            assertThat(decoded).isInstanceOf(UnaryOp.class);
            assertThat(((UnaryOp) decoded).op()).isEqualTo(op);
        }
    }

    private Expression roundTrip(Expression expr) {
        byte[] encoded = ExpressionCodec.encodeExpression(expr);
        return ExpressionCodec.decodeExpression(encoded);
    }
}
