package io.github.xinfra.lab.xdb.table;

import io.github.xinfra.lab.xdb.expression.BetweenExpr;
import io.github.xinfra.lab.xdb.expression.BinaryOp;
import io.github.xinfra.lab.xdb.expression.CaseWhenExpr;
import io.github.xinfra.lab.xdb.expression.CastExpr;
import io.github.xinfra.lab.xdb.expression.ColumnRef;
import io.github.xinfra.lab.xdb.expression.Constant;
import io.github.xinfra.lab.xdb.expression.DataType;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.Expression;
import io.github.xinfra.lab.xdb.expression.FunctionCallExpr;
import io.github.xinfra.lab.xdb.expression.InExpr;
import io.github.xinfra.lab.xdb.expression.LikeExpr;
import io.github.xinfra.lab.xdb.expression.UnaryOp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

public final class ExpressionCodec {
    private ExpressionCodec() {}

    private static final byte TAG_CONSTANT = 0x00;
    private static final byte TAG_COLUMN_REF = 0x01;
    private static final byte TAG_BINARY_OP = 0x02;
    private static final byte TAG_UNARY_OP = 0x03;
    private static final byte TAG_LIKE = 0x04;
    private static final byte TAG_IN = 0x05;
    private static final byte TAG_BETWEEN = 0x06;
    private static final byte TAG_CAST = 0x07;
    private static final byte TAG_CASE_WHEN = 0x08;
    private static final byte TAG_FUNCTION_CALL = 0x09;

    private static final byte DATUM_NULL = 0x00;
    private static final byte DATUM_INT = 0x01;
    private static final byte DATUM_DOUBLE = 0x02;
    private static final byte DATUM_DECIMAL = 0x03;
    private static final byte DATUM_STRING = 0x04;
    private static final byte DATUM_BYTES = 0x05;
    private static final byte DATUM_DATETIME = 0x06;

    public static byte[] encodeExpression(Expression expr) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(128);
            DataOutputStream out = new DataOutputStream(bos);
            writeExpression(out, expr);
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("encode failed", e);
        }
    }

    public static Expression decodeExpression(byte[] data) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            return readExpression(in);
        } catch (IOException e) {
            throw new IllegalStateException("decode failed", e);
        }
    }

    public static byte[] encodeDatumWire(Datum datum) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream(32);
            DataOutputStream out = new DataOutputStream(bos);
            writeDatum(out, datum);
            out.flush();
            return bos.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("encode datum failed", e);
        }
    }

    public static Datum decodeDatumWire(byte[] data) {
        try {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(data));
            return readDatum(in);
        } catch (IOException e) {
            throw new IllegalStateException("decode datum failed", e);
        }
    }

    // --- Expression serialization ---

    static void writeExpression(DataOutputStream out, Expression expr) throws IOException {
        switch (expr) {
            case Constant c -> {
                out.writeByte(TAG_CONSTANT);
                writeDatum(out, c.value());
                out.writeByte(c.returnType().ordinal());
            }
            case ColumnRef c -> {
                out.writeByte(TAG_COLUMN_REF);
                writeNullableUTF(out, c.tableName());
                out.writeUTF(c.columnName());
                out.writeInt(c.index());
                out.writeByte(c.returnType().ordinal());
            }
            case BinaryOp b -> {
                out.writeByte(TAG_BINARY_OP);
                out.writeByte(b.op().ordinal());
                writeExpression(out, b.left());
                writeExpression(out, b.right());
            }
            case UnaryOp u -> {
                out.writeByte(TAG_UNARY_OP);
                out.writeByte(u.op().ordinal());
                writeExpression(out, u.operand());
            }
            case LikeExpr l -> {
                out.writeByte(TAG_LIKE);
                out.writeBoolean(l.not());
                writeExpression(out, l.expr());
                writeExpression(out, l.pattern());
            }
            case InExpr i -> {
                out.writeByte(TAG_IN);
                out.writeBoolean(i.not());
                writeExpression(out, i.expr());
                out.writeInt(i.list().size());
                for (Expression e : i.list()) writeExpression(out, e);
            }
            case BetweenExpr b -> {
                out.writeByte(TAG_BETWEEN);
                out.writeBoolean(b.not());
                writeExpression(out, b.expr());
                writeExpression(out, b.low());
                writeExpression(out, b.high());
            }
            case CastExpr c -> {
                out.writeByte(TAG_CAST);
                out.writeByte(c.targetType().ordinal());
                writeExpression(out, c.expr());
            }
            case CaseWhenExpr cw -> {
                out.writeByte(TAG_CASE_WHEN);
                out.writeBoolean(cw.compareExpr() != null);
                if (cw.compareExpr() != null) writeExpression(out, cw.compareExpr());
                out.writeInt(cw.whenClauses().size());
                for (var wc : cw.whenClauses()) {
                    writeExpression(out, wc.condition());
                    writeExpression(out, wc.result());
                }
                out.writeBoolean(cw.elseExpr() != null);
                if (cw.elseExpr() != null) writeExpression(out, cw.elseExpr());
            }
            case FunctionCallExpr f -> {
                out.writeByte(TAG_FUNCTION_CALL);
                out.writeUTF(f.name());
                out.writeInt(f.args().size());
                for (Expression a : f.args()) writeExpression(out, a);
            }
            default -> throw new IllegalArgumentException(
                    "Unsupported expression: " + expr.getClass().getSimpleName());
        }
    }

    static Expression readExpression(DataInputStream in) throws IOException {
        byte tag = in.readByte();
        return switch (tag) {
            case TAG_CONSTANT -> {
                Datum value = readDatum(in);
                DataType type = DataType.values()[in.readByte()];
                yield new Constant(value, type);
            }
            case TAG_COLUMN_REF -> {
                String tableName = readNullableUTF(in);
                String columnName = in.readUTF();
                int index = in.readInt();
                DataType type = DataType.values()[in.readByte()];
                yield new ColumnRef(tableName, columnName, index, type);
            }
            case TAG_BINARY_OP -> {
                BinaryOp.Op op = BinaryOp.Op.values()[in.readByte()];
                Expression left = readExpression(in);
                Expression right = readExpression(in);
                yield new BinaryOp(left, op, right);
            }
            case TAG_UNARY_OP -> {
                UnaryOp.Op op = UnaryOp.Op.values()[in.readByte()];
                Expression operand = readExpression(in);
                yield new UnaryOp(op, operand);
            }
            case TAG_LIKE -> {
                boolean not = in.readBoolean();
                Expression expr = readExpression(in);
                Expression pattern = readExpression(in);
                yield new LikeExpr(expr, pattern, not);
            }
            case TAG_IN -> {
                boolean not = in.readBoolean();
                Expression expr = readExpression(in);
                int size = in.readInt();
                List<Expression> list = new ArrayList<>(size);
                for (int i = 0; i < size; i++) list.add(readExpression(in));
                yield new InExpr(expr, list, not);
            }
            case TAG_BETWEEN -> {
                boolean not = in.readBoolean();
                Expression expr = readExpression(in);
                Expression low = readExpression(in);
                Expression high = readExpression(in);
                yield new BetweenExpr(expr, low, high, not);
            }
            case TAG_CAST -> {
                DataType targetType = DataType.values()[in.readByte()];
                Expression expr = readExpression(in);
                yield new CastExpr(expr, targetType);
            }
            case TAG_CASE_WHEN -> {
                boolean hasCompare = in.readBoolean();
                Expression compare = hasCompare ? readExpression(in) : null;
                int numClauses = in.readInt();
                List<CaseWhenExpr.WhenClause> clauses = new ArrayList<>(numClauses);
                for (int i = 0; i < numClauses; i++) {
                    Expression cond = readExpression(in);
                    Expression result = readExpression(in);
                    clauses.add(new CaseWhenExpr.WhenClause(cond, result));
                }
                boolean hasElse = in.readBoolean();
                Expression elseExpr = hasElse ? readExpression(in) : null;
                yield new CaseWhenExpr(compare, clauses, elseExpr);
            }
            case TAG_FUNCTION_CALL -> {
                String name = in.readUTF();
                int numArgs = in.readInt();
                List<Expression> args = new ArrayList<>(numArgs);
                for (int i = 0; i < numArgs; i++) args.add(readExpression(in));
                yield new FunctionCallExpr(name, args);
            }
            default -> throw new IllegalArgumentException("Unknown expression tag: " + tag);
        };
    }

    // --- Datum wire serialization ---

    static void writeDatum(DataOutputStream out, Datum datum) throws IOException {
        switch (datum) {
            case Datum.NullDatum n -> out.writeByte(DATUM_NULL);
            case Datum.IntDatum d -> { out.writeByte(DATUM_INT); out.writeLong(d.value()); }
            case Datum.DoubleDatum d -> { out.writeByte(DATUM_DOUBLE); out.writeDouble(d.value()); }
            case Datum.DecimalDatum d -> { out.writeByte(DATUM_DECIMAL); out.writeUTF(d.value().toPlainString()); }
            case Datum.StringDatum d -> {
                out.writeByte(DATUM_STRING);
                byte[] bytes = d.value().getBytes(StandardCharsets.UTF_8);
                out.writeInt(bytes.length);
                out.write(bytes);
            }
            case Datum.BytesDatum d -> {
                out.writeByte(DATUM_BYTES);
                out.writeInt(d.value().length);
                out.write(d.value());
            }
            case Datum.DateTimeDatum d -> {
                out.writeByte(DATUM_DATETIME);
                out.writeUTF(d.value().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            }
        }
    }

    static Datum readDatum(DataInputStream in) throws IOException {
        byte tag = in.readByte();
        return switch (tag) {
            case DATUM_NULL -> Datum.nil();
            case DATUM_INT -> Datum.of(in.readLong());
            case DATUM_DOUBLE -> Datum.of(in.readDouble());
            case DATUM_DECIMAL -> Datum.of(new BigDecimal(in.readUTF()));
            case DATUM_STRING -> {
                int len = in.readInt();
                byte[] bytes = new byte[len];
                in.readFully(bytes);
                yield Datum.of(new String(bytes, StandardCharsets.UTF_8));
            }
            case DATUM_BYTES -> {
                int len = in.readInt();
                byte[] bytes = new byte[len];
                in.readFully(bytes);
                yield Datum.of(bytes);
            }
            case DATUM_DATETIME -> Datum.of(
                    LocalDateTime.parse(in.readUTF(), DateTimeFormatter.ISO_LOCAL_DATE_TIME));
            default -> throw new IllegalArgumentException("Unknown datum tag: " + tag);
        };
    }

    private static void writeNullableUTF(DataOutputStream out, String s) throws IOException {
        out.writeBoolean(s != null);
        if (s != null) out.writeUTF(s);
    }

    private static String readNullableUTF(DataInputStream in) throws IOException {
        return in.readBoolean() ? in.readUTF() : null;
    }
}
