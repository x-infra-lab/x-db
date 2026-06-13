package io.github.xinfra.lab.xdb.expression;

public final class Constant implements Expression {
    private final Datum value;
    private final DataType type;

    public Constant(Datum value, DataType type) {
        this.value = value;
        this.type = type;
    }

    public Datum value() { return value; }

    @Override public Datum eval(EvalContext ctx, Row row) { return value; }
    @Override public DataType returnType() { return type; }
    @Override public String toString() { return value.toString(); }

    public static Constant ofNull() { return new Constant(Datum.nil(), DataType.NULL); }
    public static Constant ofLong(long v) { return new Constant(Datum.of(v), DataType.BIGINT); }
    public static Constant ofDouble(double v) { return new Constant(Datum.of(v), DataType.DOUBLE); }
    public static Constant ofString(String v) { return new Constant(Datum.of(v), DataType.VARCHAR); }
    public static Constant ofTrue() { return new Constant(Datum.of(1L), DataType.BOOLEAN); }
    public static Constant ofFalse() { return new Constant(Datum.of(0L), DataType.BOOLEAN); }
}
