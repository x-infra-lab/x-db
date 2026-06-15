package io.github.xinfra.lab.xdb.executor.spill;

import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.Row;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Serializes and deserializes {@link Row} instances to/from binary streams.
 * Used for spilling rows to disk during external sort and hash spill operations.
 */
public class RowSerializer {

    private static final byte TAG_NULL = 0;
    private static final byte TAG_INT = 1;
    private static final byte TAG_DOUBLE = 2;
    private static final byte TAG_STRING = 3;
    private static final byte TAG_BYTES = 4;
    private static final byte TAG_DECIMAL = 5;
    private static final byte TAG_DATETIME = 6;

    public void writeRow(Row row, DataOutputStream out) throws IOException {
        out.writeInt(row.size());
        for (int i = 0; i < row.size(); i++) {
            writeDatum(row.get(i), out);
        }
    }

    public Row readRow(DataInputStream in) throws IOException {
        int size = in.readInt();
        Datum[] values = new Datum[size];
        for (int i = 0; i < size; i++) {
            values[i] = readDatum(in);
        }
        return new Row(values);
    }

    private void writeDatum(Datum datum, DataOutputStream out) throws IOException {
        if (datum == null || datum.isNull()) {
            out.writeByte(TAG_NULL);
        } else if (datum instanceof Datum.IntDatum d) {
            out.writeByte(TAG_INT);
            out.writeLong(d.value());
        } else if (datum instanceof Datum.DoubleDatum d) {
            out.writeByte(TAG_DOUBLE);
            out.writeDouble(d.value());
        } else if (datum instanceof Datum.StringDatum d) {
            out.writeByte(TAG_STRING);
            byte[] bytes = d.value().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.writeInt(bytes.length);
            out.write(bytes);
        } else if (datum instanceof Datum.BytesDatum d) {
            out.writeByte(TAG_BYTES);
            out.writeInt(d.value().length);
            out.write(d.value());
        } else if (datum instanceof Datum.DecimalDatum d) {
            out.writeByte(TAG_DECIMAL);
            String s = d.value().toPlainString();
            byte[] bytes = s.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            out.writeInt(bytes.length);
            out.write(bytes);
        } else if (datum instanceof Datum.DateTimeDatum d) {
            out.writeByte(TAG_DATETIME);
            LocalDateTime dt = d.value();
            out.writeInt(dt.getYear());
            out.writeByte(dt.getMonthValue());
            out.writeByte(dt.getDayOfMonth());
            out.writeByte(dt.getHour());
            out.writeByte(dt.getMinute());
            out.writeByte(dt.getSecond());
            out.writeInt(dt.getNano());
        } else {
            out.writeByte(TAG_NULL);
        }
    }

    private Datum readDatum(DataInputStream in) throws IOException {
        byte tag = in.readByte();
        return switch (tag) {
            case TAG_NULL -> Datum.nil();
            case TAG_INT -> Datum.of(in.readLong());
            case TAG_DOUBLE -> Datum.of(in.readDouble());
            case TAG_STRING -> {
                int len = in.readInt();
                byte[] bytes = new byte[len];
                in.readFully(bytes);
                yield Datum.of(new String(bytes, java.nio.charset.StandardCharsets.UTF_8));
            }
            case TAG_BYTES -> {
                int len = in.readInt();
                byte[] bytes = new byte[len];
                in.readFully(bytes);
                yield Datum.of(bytes);
            }
            case TAG_DECIMAL -> {
                int len = in.readInt();
                byte[] bytes = new byte[len];
                in.readFully(bytes);
                yield Datum.of(new BigDecimal(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)));
            }
            case TAG_DATETIME -> {
                int year = in.readInt();
                int month = in.readByte();
                int day = in.readByte();
                int hour = in.readByte();
                int minute = in.readByte();
                int second = in.readByte();
                int nano = in.readInt();
                yield Datum.of(LocalDateTime.of(year, month, day, hour, minute, second, nano));
            }
            default -> Datum.nil();
        };
    }
}
