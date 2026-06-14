package io.github.xinfra.lab.xdb.common;

import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;

public final class Codec {
    public static final byte NULL_FLAG = 0x00;
    public static final byte BYTES_FLAG = 0x01;
    public static final byte COMPACT_BYTES_FLAG = 0x02;
    public static final byte INT_FLAG = 0x03;
    public static final byte UINT_FLAG = 0x04;
    public static final byte FLOAT_FLAG = 0x05;
    public static final byte DECIMAL_FLAG = 0x06;
    public static final byte DURATION_FLAG = 0x07;
    public static final byte VARINT_FLAG = 0x08;
    public static final byte UVARINT_FLAG = 0x09;
    public static final byte BYTES_DATUM_FLAG = 0x0A;
    public static final byte MAX_FLAG = (byte) 0xFE;

    private Codec() {}

    public static byte[] encodeInt64(long v) {
        byte[] b = new byte[8];
        long u = v ^ Long.MIN_VALUE;
        for (int i = 7; i >= 0; i--) {
            b[i] = (byte) (u & 0xFF);
            u >>>= 8;
        }
        return b;
    }

    public static long decodeInt64(byte[] b, int offset) {
        long u = 0;
        for (int i = 0; i < 8; i++) {
            u = (u << 8) | (b[offset + i] & 0xFF);
        }
        return u ^ Long.MIN_VALUE;
    }

    public static byte[] encodeUint64(long v) {
        byte[] b = new byte[8];
        for (int i = 7; i >= 0; i--) {
            b[i] = (byte) (v & 0xFF);
            v >>>= 8;
        }
        return b;
    }

    public static long decodeUint64(byte[] b, int offset) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[offset + i] & 0xFF);
        }
        return v;
    }

    public static byte[] encodeFloat64(double v) {
        long bits = Double.doubleToLongBits(v);
        if (bits < 0) {
            bits = ~bits;
        } else {
            bits ^= Long.MIN_VALUE;
        }
        return encodeUint64(bits);
    }

    public static double decodeFloat64(byte[] b, int offset) {
        long bits = decodeUint64(b, offset);
        if (bits < 0) {
            bits ^= Long.MIN_VALUE;
        } else {
            bits = ~bits;
        }
        return Double.longBitsToDouble(bits);
    }

    public static byte[] encodeBytes(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(data.length + 9);
        int idx = 0;
        while (idx <= data.length) {
            int remaining = data.length - idx;
            int groupLen = Math.min(8, remaining);
            byte[] group = new byte[8];
            if (groupLen > 0) {
                System.arraycopy(data, idx, group, 0, groupLen);
            }
            out.write(group, 0, 8);
            int padCount = 8 - groupLen;
            out.write(0xFF - padCount);
            idx += 8;
            if (padCount > 0) break;
        }
        return out.toByteArray();
    }

    public static byte[] decodeBytes(byte[] data, int offset, int[] bytesRead) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int idx = offset;
        while (idx < data.length) {
            if (idx + 9 > data.length) {
                throw new IllegalArgumentException("insufficient bytes at offset " + idx);
            }
            int marker = data[idx + 8] & 0xFF;
            int padCount = 0xFF - marker;
            int dataLen = 8 - padCount;
            out.write(data, idx, dataLen);
            idx += 9;
            if (padCount > 0) break;
        }
        bytesRead[0] = idx - offset;
        return out.toByteArray();
    }

    public static byte[] encodeCompactBytes(byte[] data) {
        byte[] lenBytes = encodeVarint(data.length);
        byte[] result = new byte[lenBytes.length + data.length];
        System.arraycopy(lenBytes, 0, result, 0, lenBytes.length);
        System.arraycopy(data, 0, result, lenBytes.length, data.length);
        return result;
    }

    public static byte[] encodeVarint(long v) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(10);
        long uv = (v << 1) ^ (v >> 63);
        while ((uv & ~0x7FL) != 0) {
            out.write((int) ((uv & 0x7F) | 0x80));
            uv >>>= 7;
        }
        out.write((int) (uv & 0x7F));
        return out.toByteArray();
    }

    public static long decodeVarint(byte[] data, int offset, int[] bytesRead) {
        long uv = 0;
        int shift = 0;
        int idx = offset;
        while (idx < data.length) {
            byte b = data[idx++];
            uv |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        bytesRead[0] = idx - offset;
        return (uv >>> 1) ^ -(uv & 1);
    }

    public static byte[] encodeUvarint(long v) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(10);
        while ((v & ~0x7FL) != 0) {
            out.write((int) ((v & 0x7F) | 0x80));
            v >>>= 7;
        }
        out.write((int) (v & 0x7F));
        return out.toByteArray();
    }

    public static long decodeUvarint(byte[] data, int offset, int[] bytesRead) {
        long v = 0;
        int shift = 0;
        int idx = offset;
        while (idx < data.length) {
            byte b = data[idx++];
            v |= (long) (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        bytesRead[0] = idx - offset;
        return v;
    }

    public static byte[] encodeDatetime(LocalDateTime dt) {
        long packed = (long) dt.getYear() * 10_000_000_000L
                + (long) dt.getMonthValue() * 100_000_000L
                + (long) dt.getDayOfMonth() * 1_000_000L
                + (long) dt.getHour() * 10_000L
                + (long) dt.getMinute() * 100L
                + (long) dt.getSecond();
        return encodeUint64(packed);
    }

    public static LocalDateTime decodeDatetime(byte[] data, int offset) {
        long packed = decodeUint64(data, offset);
        int second = (int) (packed % 100); packed /= 100;
        int minute = (int) (packed % 100); packed /= 100;
        int hour   = (int) (packed % 100); packed /= 100;
        int day    = (int) (packed % 100); packed /= 100;
        int month  = (int) (packed % 100); packed /= 100;
        int year   = (int) packed;
        return LocalDateTime.of(year, month, day, hour, minute, second);
    }

    public static int compareBytes(byte[] a, byte[] b) {
        int minLen = Math.min(a.length, b.length);
        for (int i = 0; i < minLen; i++) {
            int cmp = Integer.compare(a[i] & 0xFF, b[i] & 0xFF);
            if (cmp != 0) return cmp;
        }
        return Integer.compare(a.length, b.length);
    }

    public static byte[] concat(byte[]... arrays) {
        int totalLen = 0;
        for (byte[] a : arrays) totalLen += a.length;
        byte[] result = new byte[totalLen];
        int pos = 0;
        for (byte[] a : arrays) {
            System.arraycopy(a, 0, result, pos, a.length);
            pos += a.length;
        }
        return result;
    }
}
