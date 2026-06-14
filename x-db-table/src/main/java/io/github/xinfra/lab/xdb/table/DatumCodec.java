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
                result[0] = Codec.BYTES_DATUM_FLAG;
                System.arraycopy(enc, 0, result, 1, enc.length);
                yield result;
            }
            case Datum.DecimalDatum d -> {
                byte[] sortable = encodeDecimalSortable(d.value());
                byte[] enc = Codec.encodeBytes(sortable);
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

    private static final int DECIMAL_INT_WIDTH = 40;
    private static final int DECIMAL_FRAC_WIDTH = 20;

    static byte[] encodeDecimalSortable(BigDecimal val) {
        int sign = val.signum();
        if (sign == 0) {
            byte[] result = new byte[1 + DECIMAL_INT_WIDTH + DECIMAL_FRAC_WIDTH];
            result[0] = 0x01;
            for (int i = 1; i < result.length; i++) result[i] = '0';
            return result;
        }
        BigDecimal abs = val.abs();
        String plain = abs.toPlainString();
        int dotPos = plain.indexOf('.');
        String intPart = dotPos >= 0 ? plain.substring(0, dotPos) : plain;
        String fracPart = dotPos >= 0 ? plain.substring(dotPos + 1) : "";
        while (intPart.length() < DECIMAL_INT_WIDTH) intPart = "0" + intPart;
        while (fracPart.length() < DECIMAL_FRAC_WIDTH) fracPart = fracPart + "0";
        if (intPart.length() > DECIMAL_INT_WIDTH) intPart = intPart.substring(intPart.length() - DECIMAL_INT_WIDTH);
        if (fracPart.length() > DECIMAL_FRAC_WIDTH) fracPart = fracPart.substring(0, DECIMAL_FRAC_WIDTH);
        String digits = intPart + fracPart;
        byte[] result = new byte[1 + digits.length()];
        if (sign > 0) {
            result[0] = 0x02;
            for (int i = 0; i < digits.length(); i++) result[i + 1] = (byte) digits.charAt(i);
        } else {
            result[0] = 0x00;
            for (int i = 0; i < digits.length(); i++) {
                result[i + 1] = (byte) ('0' + (9 - (digits.charAt(i) - '0')));
            }
        }
        return result;
    }

    static BigDecimal decodeDecimalSortable(byte[] data) {
        if (data.length == 0) return BigDecimal.ZERO;
        byte signByte = data[0];
        if (signByte == 0x01) return BigDecimal.ZERO;
        boolean negative = signByte == 0x00;
        char[] digits = new char[data.length - 1];
        for (int i = 0; i < digits.length; i++) {
            if (negative) {
                digits[i] = (char) ('0' + (9 - (data[i + 1] - '0')));
            } else {
                digits[i] = (char) data[i + 1];
            }
        }
        String intPart = new String(digits, 0, DECIMAL_INT_WIDTH).replaceFirst("^0+", "");
        String fracPart = new String(digits, DECIMAL_INT_WIDTH, digits.length - DECIMAL_INT_WIDTH).replaceFirst("0+$", "");
        if (intPart.isEmpty()) intPart = "0";
        String numStr = fracPart.isEmpty() ? intPart : intPart + "." + fracPart;
        if (negative) numStr = "-" + numStr;
        return new BigDecimal(numStr);
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
            case Codec.BYTES_DATUM_FLAG -> {
                int[] innerRead = new int[1];
                byte[] raw = Codec.decodeBytes(data, offset + 1, innerRead);
                bytesRead[0] = 1 + innerRead[0];
                yield Datum.of(raw);
            }
            case Codec.DECIMAL_FLAG -> {
                int[] innerRead = new int[1];
                byte[] raw = Codec.decodeBytes(data, offset + 1, innerRead);
                bytesRead[0] = 1 + innerRead[0];
                yield Datum.of(decodeDecimalSortable(raw));
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
