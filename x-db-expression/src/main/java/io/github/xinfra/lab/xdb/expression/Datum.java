package io.github.xinfra.lab.xdb.expression;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

public sealed interface Datum permits
        Datum.IntDatum, Datum.DoubleDatum, Datum.DecimalDatum,
        Datum.StringDatum, Datum.BytesDatum, Datum.DateTimeDatum,
        Datum.NullDatum {

    record IntDatum(long value) implements Datum {
        @Override public String toString() { return Long.toString(value); }
    }

    record DoubleDatum(double value) implements Datum {
        @Override public String toString() { return Double.toString(value); }
    }

    record DecimalDatum(BigDecimal value) implements Datum {
        @Override public String toString() { return value.toPlainString(); }
    }

    record StringDatum(String value) implements Datum {
        @Override public String toString() { return value; }
    }

    record BytesDatum(byte[] value) implements Datum {
        @Override public boolean equals(Object o) {
            return o instanceof BytesDatum b && Arrays.equals(value, b.value);
        }
        @Override public int hashCode() { return Arrays.hashCode(value); }
        @Override public String toString() {
            StringBuilder sb = new StringBuilder("0x");
            for (byte b : value) sb.append(String.format("%02x", b & 0xFF));
            return sb.toString();
        }
    }

    record DateTimeDatum(LocalDateTime value) implements Datum {
        @Override public String toString() {
            return value.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        }
    }

    record NullDatum() implements Datum {
        @Override public String toString() { return "NULL"; }
    }

    NullDatum NULL_INSTANCE = new NullDatum();

    static Datum of(long v) { return new IntDatum(v); }
    static Datum of(double v) { return new DoubleDatum(v); }
    static Datum of(BigDecimal v) { return v == null ? nil() : new DecimalDatum(v); }
    static Datum of(String v) { return v == null ? nil() : new StringDatum(v); }
    static Datum of(byte[] v) { return v == null ? nil() : new BytesDatum(v); }
    static Datum of(LocalDateTime v) { return v == null ? nil() : new DateTimeDatum(v); }
    static Datum nil() { return NULL_INSTANCE; }

    default boolean isNull() { return this instanceof NullDatum; }

    default long toLong() {
        if (this instanceof IntDatum d) return d.value;
        if (this instanceof DoubleDatum d) return (long) d.value;
        if (this instanceof DecimalDatum d) return d.value.longValue();
        if (this instanceof StringDatum d) {
            try { return Long.parseLong(d.value.trim()); }
            catch (NumberFormatException e) { return 0L; }
        }
        return 0L;
    }

    default double toDouble() {
        if (this instanceof IntDatum d) return (double) d.value;
        if (this instanceof DoubleDatum d) return d.value;
        if (this instanceof DecimalDatum d) return d.value.doubleValue();
        if (this instanceof StringDatum d) {
            try { return Double.parseDouble(d.value.trim()); }
            catch (NumberFormatException e) { return 0.0; }
        }
        return 0.0;
    }

    default String toStringValue() { return toString(); }

    default boolean toBoolean() {
        if (this instanceof NullDatum) return false;
        if (this instanceof IntDatum d) return d.value != 0;
        if (this instanceof DoubleDatum d) return d.value != 0.0;
        if (this instanceof StringDatum d) return !d.value.isEmpty() && !"0".equals(d.value);
        return true;
    }
}
