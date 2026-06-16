package io.github.xinfra.lab.xdb.table;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public final class KvPairDecoder {
    private KvPairDecoder() {}

    public record KvPair(byte[] key, byte[] value) {}

    public static List<KvPair> decode(byte[] data) {
        if (data == null || data.length < 4) return List.of();
        ByteBuffer bb = ByteBuffer.wrap(data);
        int count = bb.getInt();
        List<KvPair> pairs = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int kLen = bb.getInt();
            byte[] k = new byte[kLen];
            bb.get(k);
            int vLen = bb.getInt();
            byte[] v = new byte[vLen];
            bb.get(v);
            pairs.add(new KvPair(k, v));
        }
        return pairs;
    }

    public static byte[] encode(List<KvPair> pairs) {
        int totalBytes = 4;
        for (KvPair p : pairs) {
            totalBytes += 4 + p.key().length + 4 + p.value().length;
        }
        byte[] out = new byte[totalBytes];
        ByteBuffer bb = ByteBuffer.wrap(out);
        bb.putInt(pairs.size());
        for (KvPair p : pairs) {
            bb.putInt(p.key().length);
            bb.put(p.key());
            bb.putInt(p.value().length);
            bb.put(p.value());
        }
        return out;
    }
}
