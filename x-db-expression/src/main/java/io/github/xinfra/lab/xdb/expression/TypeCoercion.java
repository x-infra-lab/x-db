package io.github.xinfra.lab.xdb.expression;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

public final class TypeCoercion {
    private TypeCoercion() {}

    public static DataType commonType(DataType a, DataType b) {
        if (a == b) return a;
        if (a == DataType.NULL) return b;
        if (b == DataType.NULL) return a;
        if (a.isNumeric() && b.isNumeric()) {
            if (a == DataType.DECIMAL || b == DataType.DECIMAL) return DataType.DECIMAL;
            if (a == DataType.DOUBLE || b == DataType.DOUBLE) return DataType.DOUBLE;
            if (a == DataType.FLOAT || b == DataType.FLOAT) return DataType.DOUBLE;
            return DataType.BIGINT;
        }
        if (a.isTemporal() && b.isTemporal()) return DataType.DATETIME;
        return DataType.VARCHAR;
    }

    public static Datum coerce(Datum value, DataType targetType) {
        if (value.isNull()) return value;
        if (targetType == null) return value;

        if (targetType.isInteger()) return Datum.of(value.toLong());
        if (targetType == DataType.DOUBLE || targetType == DataType.FLOAT)
            return Datum.of(value.toDouble());
        if (targetType == DataType.DECIMAL) {
            if (value instanceof Datum.DecimalDatum) return value;
            if (value instanceof Datum.IntDatum i) return Datum.of(BigDecimal.valueOf(i.value()));
            if (value instanceof Datum.DoubleDatum d) return Datum.of(BigDecimal.valueOf(d.value()));
            try { return Datum.of(new BigDecimal(value.toStringValue())); }
            catch (NumberFormatException e) { return Datum.nil(); }
        }
        if (targetType.isString()) return Datum.of(value.toStringValue());
        if (targetType == DataType.DATETIME || targetType == DataType.TIMESTAMP) {
            if (value instanceof Datum.DateTimeDatum) return value;
            try {
                return Datum.of(LocalDateTime.parse(value.toStringValue().trim(),
                        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            } catch (DateTimeParseException e) { return Datum.nil(); }
        }
        return value;
    }
}
