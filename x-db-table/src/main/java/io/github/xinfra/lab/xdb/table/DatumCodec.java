package io.github.xinfra.lab.xdb.table;

import io.github.xinfra.lab.xdb.common.Codec;
import io.github.xinfra.lab.xdb.expression.Datum;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

public final class DatumCodec {
    private DatumCodec() {}

    public static byte[] encode(Datum datum) {
        return switch (datum) {
            case Datum.NullDatum n -> new byte[]{Codec.NULL_FLAG};
            case Datum.IntDatum d -> {
                byte[] enc = Codec.encodeInt64(d.value());
                byte[] result = new byte[9];
                result[0] = Codec.INT_FLAG;
                System.arraycopy(enc, 0, result, 1, 8);
                yield result;
            }
            case Datum.DoubleDatum d -> {
                byte[] enc = Codec.encodeFloat64(d.value());
                byte[] result = new byte[9];
                result[0] = Codec.FLOAT_FLAG;
                System.arraycopy(enc, 0, result, 1, 8);
                yield result;
            }
            case Datum.StringDatum d -> {
                byte[] raw = d.value().getBytes(StandardCharsets.UTF_8);
                byte[] enc = Codec.encodeBytes(raw);
                byte[] result = new byte[1 + enc.length];
                result[0] = Codec.BYTES_FLAG;
                System.arraycopy(enc, 0, result, 1, enc.length);
                yield result;
            }
            case Datum.BytesDatum d -> {
                byte[] enc = Codec.encodeBytes(d.value());
                byte[] result = new byte[1 + enc.length];
                result[0] = Codec.BYTES_FLAG;
                System.arraycopy(enc, 0, result, 1, enc.length);
                yield result;
            }
            case Datum.DecimalDatum d -> {
                // Encode decimal as string for simplicity (not optimal but correct ordering
                // within same precision)
                String s = d.value().toPlainString();
                byte[] raw = s.getBytes(StandardCharsets.UTF_8);
                byte[] enc = Codec.encodeBytes(raw);
                byte[] result = new byte[1 + enc.length];
                result[0] = Codec.DECIMAL_FLAG;
                System.arraycopy(enc, 0, result, 1, enc.length);
                yield result;
            }
            case Datum.DateTimeDatum d -> {
                byte[] enc = Codec.encodeDatetime(d.value());
                byte[] result = new byte[9];
                result[0] = Codec.DURATION_FLAG;
                System.arraycopy(enc, 0, result, 1, 8);
                yield result;
            }
        };
    }

    public static Datum decode(byte[] data, int offset, int[] bytesRead) {
        byte flag = data[offset];
        return switch (flag) {
            case Codec.NULL_FLAG -> { bytesRead[0] = 1; yield Datum.nil(); }
            case Codec.INT_FLAG -> {
                long v = Codec.decodeInt64(data, offset + 1);
                bytesRead[0] = 9;
                yield Datum.of(v);
            }
            case Codec.FLOAT_FLAG -> {
                double v = Codec.decodeFloat64(data, offset + 1);
                bytesRead[0] = 9;
                yield Datum.of(v);
            }
            case Codec.BYTES_FLAG -> {
                int[] innerRead = new int[1];
                byte[] raw = Codec.decodeBytes(data, offset + 1, innerRead);
                bytesRead[0] = 1 + innerRead[0];
                yield Datum.of(new String(raw, StandardCharsets.UTF_8));
            }
            case Codec.DECIMAL_FLAG -> {
                int[] innerRead = new int[1];
                byte[] raw = Codec.decodeBytes(data, offset + 1, innerRead);
                bytesRead[0] = 1 + innerRead[0];
                yield Datum.of(new BigDecimal(new String(raw, StandardCharsets.UTF_8)));
            }
            case Codec.DURATION_FLAG -> {
                LocalDateTime dt = Codec.decodeDatetime(data, offset + 1);
                bytesRead[0] = 9;
                yield Datum.of(dt);
            }
            default -> throw new IllegalArgumentException("Unknown datum flag: " + flag);
        };
    }
}
