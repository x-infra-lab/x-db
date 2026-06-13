package io.github.xinfra.lab.xdb.table;

import io.github.xinfra.lab.xdb.common.Codec;
import io.github.xinfra.lab.xdb.expression.Datum;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Row value codec. Uses a compact column-aware format:
 *   [flag(1)] [colID1(uvarint)] [value1] [colID2(uvarint)] [value2] ...
 *
 * Each value is prefixed by a type flag (same constants as DatumCodec)
 * followed by the value data. This format is NOT memcomparable — it's
 * optimized for space efficiency and random-column access.
 */
public final class RowCodec {
    private static final byte ROW_FLAG = (byte) 0x80;

    private RowCodec() {}

    /**
     * Encode a row (list of column IDs + values) into a compact byte format.
     * @param colIds column IDs in order
     * @param values corresponding values
     */
    public static byte[] encode(List<Long> colIds, List<Datum> values) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(128);
        out.write(ROW_FLAG);

        for (int i = 0; i < colIds.size(); i++) {
            Datum v = values.get(i);
            if (v.isNull()) continue; // skip null columns

            byte[] colIdBytes = Codec.encodeUvarint(colIds.get(i));
            out.write(colIdBytes, 0, colIdBytes.length);

            byte[] encoded = encodeValue(v);
            out.write(encoded, 0, encoded.length);
        }

        return out.toByteArray();
    }

    /**
     * Decode a row value into a map of columnId → Datum.
     */
    public static Map<Long, Datum> decode(byte[] data) {
        Map<Long, Datum> result = new HashMap<>();
        if (data == null || data.length == 0) return result;

        int offset = 0;
        if (data[0] == ROW_FLAG) offset = 1;

        int[] bytesRead = new int[1];
        while (offset < data.length) {
            long colId = Codec.decodeUvarint(data, offset, bytesRead);
            offset += bytesRead[0];

            Datum value = decodeValue(data, offset, bytesRead);
            offset += bytesRead[0];

            result.put(colId, value);
        }

        return result;
    }

    private static byte[] encodeValue(Datum datum) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(16);
        switch (datum) {
            case Datum.IntDatum d -> {
                out.write(Codec.INT_FLAG);
                byte[] b = Codec.encodeVarint(d.value());
                out.write(b, 0, b.length);
            }
            case Datum.DoubleDatum d -> {
                out.write(Codec.FLOAT_FLAG);
                long bits = Double.doubleToLongBits(d.value());
                byte[] b = Codec.encodeUint64(bits);
                out.write(b, 0, b.length);
            }
            case Datum.StringDatum d -> {
                out.write(Codec.COMPACT_BYTES_FLAG);
                byte[] raw = d.value().getBytes(StandardCharsets.UTF_8);
                byte[] lenB = Codec.encodeVarint(raw.length);
                out.write(lenB, 0, lenB.length);
                out.write(raw, 0, raw.length);
            }
            case Datum.BytesDatum d -> {
                out.write(Codec.COMPACT_BYTES_FLAG);
                byte[] lenB = Codec.encodeVarint(d.value().length);
                out.write(lenB, 0, lenB.length);
                out.write(d.value(), 0, d.value().length);
            }
            case Datum.DecimalDatum d -> {
                out.write(Codec.DECIMAL_FLAG);
                byte[] raw = d.value().toPlainString().getBytes(StandardCharsets.UTF_8);
                byte[] lenB = Codec.encodeVarint(raw.length);
                out.write(lenB, 0, lenB.length);
                out.write(raw, 0, raw.length);
            }
            case Datum.DateTimeDatum d -> {
                out.write(Codec.DURATION_FLAG);
                byte[] b = Codec.encodeDatetime(d.value());
                out.write(b, 0, b.length);
            }
            case Datum.NullDatum n -> out.write(Codec.NULL_FLAG);
        }
        return out.toByteArray();
    }

    private static Datum decodeValue(byte[] data, int offset, int[] bytesRead) {
        byte flag = data[offset];
        int pos = offset + 1;

        return switch (flag) {
            case Codec.NULL_FLAG -> { bytesRead[0] = 1; yield Datum.nil(); }
            case Codec.INT_FLAG -> {
                int[] innerRead = new int[1];
                long v = Codec.decodeVarint(data, pos, innerRead);
                bytesRead[0] = 1 + innerRead[0];
                yield Datum.of(v);
            }
            case Codec.FLOAT_FLAG -> {
                long bits = Codec.decodeUint64(data, pos);
                bytesRead[0] = 9;
                yield Datum.of(Double.longBitsToDouble(bits));
            }
            case Codec.COMPACT_BYTES_FLAG -> {
                int[] innerRead = new int[1];
                long len = Codec.decodeVarint(data, pos, innerRead);
                pos += innerRead[0];
                byte[] raw = new byte[(int) len];
                System.arraycopy(data, pos, raw, 0, (int) len);
                bytesRead[0] = 1 + innerRead[0] + (int) len;
                yield Datum.of(new String(raw, StandardCharsets.UTF_8));
            }
            case Codec.DECIMAL_FLAG -> {
                int[] innerRead = new int[1];
                long len = Codec.decodeVarint(data, pos, innerRead);
                pos += innerRead[0];
                byte[] raw = new byte[(int) len];
                System.arraycopy(data, pos, raw, 0, (int) len);
                bytesRead[0] = 1 + innerRead[0] + (int) len;
                yield Datum.of(new BigDecimal(new String(raw, StandardCharsets.UTF_8)));
            }
            case Codec.DURATION_FLAG -> {
                LocalDateTime dt = Codec.decodeDatetime(data, pos);
                bytesRead[0] = 9;
                yield Datum.of(dt);
            }
            default -> throw new IllegalArgumentException("Unknown value flag: " + flag);
        };
    }
}
