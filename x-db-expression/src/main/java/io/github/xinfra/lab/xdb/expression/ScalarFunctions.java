package io.github.xinfra.lab.xdb.expression;

import java.util.List;

public final class ScalarFunctions {
    private ScalarFunctions() {}

    public static Datum eval(String name, List<Datum> args, EvalContext ctx) {
        return switch (name.toUpperCase()) {
            case "IF" -> args.size() >= 3 ? (args.get(0).toBoolean() ? args.get(1) : args.get(2)) : Datum.nil();
            case "IFNULL" -> args.size() >= 2 ? (args.get(0).isNull() ? args.get(1) : args.get(0)) : Datum.nil();
            case "NULLIF" -> args.size() >= 2 && DatumComparator.compare(args.get(0), args.get(1)) == 0
                    ? Datum.nil() : (args.isEmpty() ? Datum.nil() : args.get(0));
            case "COALESCE" -> {
                for (Datum d : args) if (!d.isNull()) yield d;
                yield Datum.nil();
            }
            case "CONCAT" -> {
                StringBuilder sb = new StringBuilder();
                for (Datum d : args) { if (d.isNull()) yield Datum.nil(); sb.append(d.toStringValue()); }
                yield Datum.of(sb.toString());
            }
            case "LENGTH", "CHAR_LENGTH", "CHARACTER_LENGTH" -> nullSafe1(args, d -> Datum.of(d.toStringValue().length()));
            case "UPPER", "UCASE" -> nullSafe1(args, d -> Datum.of(d.toStringValue().toUpperCase()));
            case "LOWER", "LCASE" -> nullSafe1(args, d -> Datum.of(d.toStringValue().toLowerCase()));
            case "TRIM" -> nullSafe1(args, d -> Datum.of(d.toStringValue().trim()));
            case "SUBSTRING", "SUBSTR", "MID" -> {
                if (args.size() < 2 || args.get(0).isNull()) yield Datum.nil();
                String s = args.get(0).toStringValue();
                int start = Math.max(0, (int) args.get(1).toLong() - 1);
                if (start >= s.length()) yield Datum.of("");
                int len = args.size() >= 3 ? (int) args.get(2).toLong() : s.length() - start;
                yield Datum.of(s.substring(start, Math.min(start + len, s.length())));
            }
            case "REPLACE" -> {
                if (args.size() < 3 || args.get(0).isNull()) yield Datum.nil();
                yield Datum.of(args.get(0).toStringValue()
                        .replace(args.get(1).toStringValue(), args.get(2).toStringValue()));
            }
            case "LEFT" -> {
                if (args.size() < 2 || args.get(0).isNull()) yield Datum.nil();
                String s = args.get(0).toStringValue();
                yield Datum.of(s.substring(0, Math.min((int) args.get(1).toLong(), s.length())));
            }
            case "RIGHT" -> {
                if (args.size() < 2 || args.get(0).isNull()) yield Datum.nil();
                String s = args.get(0).toStringValue();
                yield Datum.of(s.substring(Math.max(0, s.length() - (int) args.get(1).toLong())));
            }
            case "ABS" -> nullSafe1(args, d -> {
                if (d instanceof Datum.IntDatum i) return Datum.of(Math.abs(i.value()));
                return Datum.of(Math.abs(d.toDouble()));
            });
            case "CEIL", "CEILING" -> nullSafe1(args, d -> Datum.of((long) Math.ceil(d.toDouble())));
            case "FLOOR" -> nullSafe1(args, d -> Datum.of((long) Math.floor(d.toDouble())));
            case "ROUND" -> {
                if (args.isEmpty() || args.get(0).isNull()) yield Datum.nil();
                double v = args.get(0).toDouble();
                int scale = args.size() >= 2 ? (int) args.get(1).toLong() : 0;
                double factor = Math.pow(10, scale);
                yield Datum.of(Math.round(v * factor) / factor);
            }
            case "MOD" -> {
                if (args.size() < 2 || args.get(0).isNull() || args.get(1).isNull()) yield Datum.nil();
                long b = args.get(1).toLong();
                yield b == 0 ? Datum.nil() : Datum.of(args.get(0).toLong() % b);
            }
            case "POWER", "POW" -> {
                if (args.size() < 2 || args.get(0).isNull() || args.get(1).isNull()) yield Datum.nil();
                yield Datum.of(Math.pow(args.get(0).toDouble(), args.get(1).toDouble()));
            }
            case "SQRT" -> nullSafe1(args, d -> {
                double v = d.toDouble();
                return v < 0 ? Datum.nil() : Datum.of(Math.sqrt(v));
            });
            case "RAND" -> Datum.of(Math.random());
            case "NOW", "CURRENT_TIMESTAMP", "LOCALTIME", "LOCALTIMESTAMP" -> Datum.of(ctx.currentTime());
            case "CURDATE", "CURRENT_DATE" -> Datum.of(ctx.currentTime().toLocalDate().atStartOfDay());
            case "YEAR" -> nullSafe1(args, d -> {
                if (d instanceof Datum.DateTimeDatum dt) return Datum.of(dt.value().getYear());
                return Datum.nil();
            });
            case "MONTH" -> nullSafe1(args, d -> {
                if (d instanceof Datum.DateTimeDatum dt) return Datum.of(dt.value().getMonthValue());
                return Datum.nil();
            });
            case "DAY", "DAYOFMONTH" -> nullSafe1(args, d -> {
                if (d instanceof Datum.DateTimeDatum dt) return Datum.of(dt.value().getDayOfMonth());
                return Datum.nil();
            });
            case "VERSION" -> Datum.of("8.0.30-x-db");
            case "DATABASE", "SCHEMA" -> Datum.nil();
            case "CONNECTION_ID" -> Datum.of(1L);
            case "LAST_INSERT_ID" -> Datum.of(0L);
            case "ROW_COUNT" -> Datum.of(-1L);
            case "FOUND_ROWS" -> Datum.of(0L);
            default -> throw new UnsupportedOperationException("Unknown function: " + name);
        };
    }

    @FunctionalInterface
    private interface DatumMapper { Datum apply(Datum d); }

    private static Datum nullSafe1(List<Datum> args, DatumMapper fn) {
        if (args.isEmpty() || args.get(0).isNull()) return Datum.nil();
        return fn.apply(args.get(0));
    }
}
