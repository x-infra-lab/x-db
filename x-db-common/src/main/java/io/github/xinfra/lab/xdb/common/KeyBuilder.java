package io.github.xinfra.lab.xdb.common;

import java.io.ByteArrayOutputStream;

public final class KeyBuilder {
    private final ByteArrayOutputStream buf;

    private KeyBuilder() {
        this.buf = new ByteArrayOutputStream(64);
    }

    public static KeyBuilder builder() {
        return new KeyBuilder();
    }

    public KeyBuilder appendByte(int b) {
        buf.write(b);
        return this;
    }

    public KeyBuilder appendBytes(byte[] data) {
        buf.write(data, 0, data.length);
        return this;
    }

    public KeyBuilder appendInt64(long v) {
        return appendBytes(Codec.encodeInt64(v));
    }

    public KeyBuilder appendUint64(long v) {
        return appendBytes(Codec.encodeUint64(v));
    }

    public KeyBuilder appendEncodedBytes(byte[] data) {
        return appendBytes(Codec.encodeBytes(data));
    }

    public byte[] build() {
        return buf.toByteArray();
    }

    public int length() {
        return buf.size();
    }
}
