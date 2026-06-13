package io.github.xinfra.lab.xdb.test;

import io.github.xinfra.lab.xdb.executor.TransactionContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentSkipListMap;

public class InMemoryKVStore {

    private static final Comparator<byte[]> BYTE_ARRAY_COMPARATOR = (a, b) -> {
        int minLen = Math.min(a.length, b.length);
        for (int i = 0; i < minLen; i++) {
            int cmp = Integer.compare(a[i] & 0xFF, b[i] & 0xFF);
            if (cmp != 0) return cmp;
        }
        return Integer.compare(a.length, b.length);
    };

    private final ConcurrentSkipListMap<byte[], byte[]> data =
            new ConcurrentSkipListMap<>(BYTE_ARRAY_COMPARATOR);

    public byte[] get(byte[] key) {
        byte[] value = data.get(key);
        return value != null ? Arrays.copyOf(value, value.length) : null;
    }

    public void put(byte[] key, byte[] value) {
        data.put(Arrays.copyOf(key, key.length), Arrays.copyOf(value, value.length));
    }

    public void delete(byte[] key) {
        data.remove(key);
    }

    public List<TransactionContext.KVPair> scan(byte[] startKey, byte[] endKey, int limit) {
        var subMap = endKey != null
                ? data.subMap(startKey, true, endKey, false)
                : data.tailMap(startKey, true);
        List<TransactionContext.KVPair> result = new ArrayList<>();
        int count = 0;
        for (var entry : subMap.entrySet()) {
            if (limit > 0 && count >= limit) break;
            result.add(new TransactionContext.KVPair(
                    Arrays.copyOf(entry.getKey(), entry.getKey().length),
                    Arrays.copyOf(entry.getValue(), entry.getValue().length)));
            count++;
        }
        return result;
    }

    public boolean cas(byte[] key, byte[] expectedOldValue, byte[] newValue) {
        synchronized (this) {
            byte[] current = data.get(key);
            if (expectedOldValue == null) {
                if (current != null) return false;
            } else {
                if (current == null || !Arrays.equals(current, expectedOldValue)) return false;
            }
            data.put(Arrays.copyOf(key, key.length), Arrays.copyOf(newValue, newValue.length));
            return true;
        }
    }

    public void clear() {
        data.clear();
    }
}
