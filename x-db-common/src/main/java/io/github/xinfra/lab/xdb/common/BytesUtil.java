package io.github.xinfra.lab.xdb.common;

import java.util.Arrays;

public final class BytesUtil {
    private BytesUtil() {}

    public static final byte[] EMPTY = new byte[0];

    public static boolean isEmpty(byte[] b) {
        return b == null || b.length == 0;
    }

    public static byte[] nextKey(byte[] key) {
        return Arrays.copyOf(key, key.length + 1);
    }

    public static byte[] prefixEndKey(byte[] prefix) {
        byte[] end = Arrays.copyOf(prefix, prefix.length);
        for (int i = end.length - 1; i >= 0; i--) {
            end[i]++;
            if (end[i] != 0) return end;
        }
        return null;
    }

    public static boolean hasPrefix(byte[] key, byte[] prefix) {
        if (key.length < prefix.length) return false;
        for (int i = 0; i < prefix.length; i++) {
            if (key[i] != prefix[i]) return false;
        }
        return true;
    }

    public static int compare(byte[] a, byte[] b) {
        return Codec.compareBytes(a, b);
    }

    public static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xFF));
        }
        return sb.toString();
    }
}
