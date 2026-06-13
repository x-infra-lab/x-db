package io.github.xinfra.lab.xdb.table;

import io.github.xinfra.lab.xdb.common.Codec;
import io.github.xinfra.lab.xdb.common.KeyBuilder;
import io.github.xinfra.lab.xdb.expression.Datum;

import java.util.List;

public final class TableCodec {
    private TableCodec() {}

    // ===== Row Key Encoding =====
    // Format: t{tableID}_r{rowHandle}
    // tableID and rowHandle are int64 memcomparable encoded

    public static byte[] encodeRowKey(long tableId, long handle) {
        return KeyBuilder.builder()
                .appendByte(KeyPrefix.TABLE_PREFIX)
                .appendInt64(tableId)
                .appendBytes(KeyPrefix.RECORD_PREFIX_SEP)
                .appendInt64(handle)
                .build();
    }

    public static long decodeRowHandle(byte[] rowKey) {
        // t(1) + tableID(8) + _r(2) = offset 11
        return Codec.decodeInt64(rowKey, 11);
    }

    public static long decodeTableId(byte[] key) {
        // t(1) + tableID(8) starts at offset 1
        return Codec.decodeInt64(key, 1);
    }

    // Row key range for a table: [t{tableID}_r{MIN}, t{tableID}_r{MAX})
    public static byte[] encodeRowKeyMin(long tableId) {
        return encodeRowKey(tableId, Long.MIN_VALUE);
    }

    public static byte[] encodeRowKeyMax(long tableId) {
        // Use next prefix after _r
        return KeyBuilder.builder()
                .appendByte(KeyPrefix.TABLE_PREFIX)
                .appendInt64(tableId)
                .appendBytes(KeyPrefix.RECORD_PREFIX_SEP)
                .appendInt64(Long.MAX_VALUE)
                .build();
    }

    // ===== Index Key Encoding =====
    // Non-unique: t{tableID}_i{indexID}_{col1}_{col2}_{handle}
    // Unique:     t{tableID}_i{indexID}_{col1}_{col2}

    public static byte[] encodeIndexKey(long tableId, long indexId,
                                         List<Datum> indexValues, Long handle) {
        KeyBuilder kb = KeyBuilder.builder()
                .appendByte(KeyPrefix.TABLE_PREFIX)
                .appendInt64(tableId)
                .appendBytes(KeyPrefix.INDEX_PREFIX_SEP)
                .appendInt64(indexId);

        for (Datum v : indexValues) {
            kb.appendBytes(DatumCodec.encode(v));
        }

        if (handle != null) {
            kb.appendBytes(DatumCodec.encode(Datum.of(handle)));
        }

        return kb.build();
    }

    // Index scan range: [t{tableID}_i{indexID}_{MIN}, t{tableID}_i{indexID}_{MAX})
    public static byte[] encodeIndexKeyPrefix(long tableId, long indexId) {
        return KeyBuilder.builder()
                .appendByte(KeyPrefix.TABLE_PREFIX)
                .appendInt64(tableId)
                .appendBytes(KeyPrefix.INDEX_PREFIX_SEP)
                .appendInt64(indexId)
                .build();
    }

    // Encode index scan start for a given set of prefix values
    public static byte[] encodeIndexScanStart(long tableId, long indexId, List<Datum> prefixValues) {
        KeyBuilder kb = KeyBuilder.builder()
                .appendByte(KeyPrefix.TABLE_PREFIX)
                .appendInt64(tableId)
                .appendBytes(KeyPrefix.INDEX_PREFIX_SEP)
                .appendInt64(indexId);
        for (Datum v : prefixValues) {
            kb.appendBytes(DatumCodec.encode(v));
        }
        return kb.build();
    }

    // Decode index values from an index key
    // Returns the handle from a non-unique index key (last encoded int)
    public static long decodeIndexHandle(byte[] indexKey, int numIndexColumns) {
        // Skip: t(1) + tableID(8) + _i(2) + indexID(8) = 19
        int offset = 19;
        int[] bytesRead = new int[1];
        for (int i = 0; i < numIndexColumns; i++) {
            DatumCodec.decode(indexKey, offset, bytesRead);
            offset += bytesRead[0];
        }
        // The handle is the next encoded datum (IntDatum)
        Datum handleDatum = DatumCodec.decode(indexKey, offset, bytesRead);
        return handleDatum.toLong();
    }

    // Check if a key is a row key for a given table
    public static boolean isRowKey(byte[] key, long tableId) {
        if (key.length < 11) return false;
        if (key[0] != KeyPrefix.TABLE_PREFIX) return false;
        long tid = Codec.decodeInt64(key, 1);
        if (tid != tableId) return false;
        return key[9] == KeyPrefix.RECORD_PREFIX_SEP[0]
            && key[10] == KeyPrefix.RECORD_PREFIX_SEP[1];
    }

    // Table prefix for range operations
    public static byte[] tablePrefix(long tableId) {
        return KeyBuilder.builder()
                .appendByte(KeyPrefix.TABLE_PREFIX)
                .appendInt64(tableId)
                .build();
    }

    public static byte[] tableRecordPrefix(long tableId) {
        return KeyBuilder.builder()
                .appendByte(KeyPrefix.TABLE_PREFIX)
                .appendInt64(tableId)
                .appendBytes(KeyPrefix.RECORD_PREFIX_SEP)
                .build();
    }

    public static byte[] tableIndexPrefix(long tableId) {
        return KeyBuilder.builder()
                .appendByte(KeyPrefix.TABLE_PREFIX)
                .appendInt64(tableId)
                .appendBytes(KeyPrefix.INDEX_PREFIX_SEP)
                .build();
    }
}
