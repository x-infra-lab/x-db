package io.github.xinfra.lab.xdb.table;

import io.github.xinfra.lab.xdb.common.BytesUtil;
import io.github.xinfra.lab.xdb.common.Codec;
import io.github.xinfra.lab.xdb.expression.Datum;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TableCodecTest {

    // ==================== Row Key ====================

    @Nested
    class RowKey {

        @Test
        void encodeDecodeRoundTrip() {
            long tableId = 42;
            long handle = 100;
            byte[] rowKey = TableCodec.encodeRowKey(tableId, handle);

            assertThat(TableCodec.decodeTableId(rowKey)).isEqualTo(tableId);
            assertThat(TableCodec.decodeRowHandle(rowKey)).isEqualTo(handle);
        }

        @Test
        void roundTripWithNegativeHandle() {
            long tableId = 1;
            long handle = -5;
            byte[] rowKey = TableCodec.encodeRowKey(tableId, handle);

            assertThat(TableCodec.decodeTableId(rowKey)).isEqualTo(tableId);
            assertThat(TableCodec.decodeRowHandle(rowKey)).isEqualTo(handle);
        }

        @Test
        void roundTripWithMaxValues() {
            long tableId = Long.MAX_VALUE;
            long handle = Long.MAX_VALUE;
            byte[] rowKey = TableCodec.encodeRowKey(tableId, handle);

            assertThat(TableCodec.decodeTableId(rowKey)).isEqualTo(tableId);
            assertThat(TableCodec.decodeRowHandle(rowKey)).isEqualTo(handle);
        }

        @Test
        void roundTripWithMinValues() {
            long tableId = Long.MIN_VALUE;
            long handle = Long.MIN_VALUE;
            byte[] rowKey = TableCodec.encodeRowKey(tableId, handle);

            assertThat(TableCodec.decodeTableId(rowKey)).isEqualTo(tableId);
            assertThat(TableCodec.decodeRowHandle(rowKey)).isEqualTo(handle);
        }

        @Test
        void keyFormat() {
            byte[] rowKey = TableCodec.encodeRowKey(1, 1);
            // t(1) + tableID(8) + _r(2) + handle(8) = 19
            assertThat(rowKey).hasSize(19);
            assertThat(rowKey[0]).isEqualTo(KeyPrefix.TABLE_PREFIX);
            assertThat(rowKey[9]).isEqualTo(KeyPrefix.RECORD_PREFIX_SEP[0]);
            assertThat(rowKey[10]).isEqualTo(KeyPrefix.RECORD_PREFIX_SEP[1]);
        }

        @Test
        void rowKeyOrderPreserved() {
            byte[] key1 = TableCodec.encodeRowKey(1, 1);
            byte[] key2 = TableCodec.encodeRowKey(1, 2);
            byte[] key3 = TableCodec.encodeRowKey(1, 100);
            byte[] key4 = TableCodec.encodeRowKey(2, 1);

            assertThat(BytesUtil.compare(key1, key2)).isLessThan(0);
            assertThat(BytesUtil.compare(key2, key3)).isLessThan(0);
            assertThat(BytesUtil.compare(key3, key4)).isLessThan(0);
        }
    }

    // ==================== Row Key Range ====================

    @Nested
    class RowKeyRange {

        @Test
        void minRowKey() {
            byte[] minKey = TableCodec.encodeRowKeyMin(1);
            byte[] regularKey = TableCodec.encodeRowKey(1, 0);
            byte[] maxKey = TableCodec.encodeRowKeyMax(1);

            assertThat(BytesUtil.compare(minKey, regularKey)).isLessThan(0);
            assertThat(BytesUtil.compare(regularKey, maxKey)).isLessThan(0);
        }

        @Test
        void minKeyDecodesToMinHandle() {
            byte[] minKey = TableCodec.encodeRowKeyMin(5);
            assertThat(TableCodec.decodeRowHandle(minKey)).isEqualTo(Long.MIN_VALUE);
        }

        @Test
        void maxKeyDecodesToMaxHandle() {
            byte[] maxKey = TableCodec.encodeRowKeyMax(5);
            assertThat(TableCodec.decodeRowHandle(maxKey)).isEqualTo(Long.MAX_VALUE);
        }
    }

    // ==================== isRowKey ====================

    @Nested
    class IsRowKey {

        @Test
        void validRowKey() {
            byte[] rowKey = TableCodec.encodeRowKey(1, 100);
            assertThat(TableCodec.isRowKey(rowKey, 1)).isTrue();
        }

        @Test
        void wrongTableId() {
            byte[] rowKey = TableCodec.encodeRowKey(1, 100);
            assertThat(TableCodec.isRowKey(rowKey, 2)).isFalse();
        }

        @Test
        void indexKeyIsNotRowKey() {
            byte[] indexKey = TableCodec.encodeIndexKey(1, 1, List.of(Datum.of(1L)), null);
            assertThat(TableCodec.isRowKey(indexKey, 1)).isFalse();
        }

        @Test
        void tooShortKey() {
            byte[] shortKey = new byte[5];
            assertThat(TableCodec.isRowKey(shortKey, 1)).isFalse();
        }

        @Test
        void wrongPrefix() {
            byte[] key = new byte[19];
            key[0] = 0x00; // wrong prefix
            assertThat(TableCodec.isRowKey(key, 1)).isFalse();
        }
    }

    // ==================== Index Key ====================

    @Nested
    class IndexKey {

        @Test
        void encodeNonUniqueIndex() {
            long tableId = 1;
            long indexId = 2;
            List<Datum> values = List.of(Datum.of(10L), Datum.of("hello"));
            long handle = 100;

            byte[] indexKey = TableCodec.encodeIndexKey(tableId, indexId, values, handle);
            assertThat(indexKey).isNotEmpty();

            // Verify table ID is recoverable
            assertThat(TableCodec.decodeTableId(indexKey)).isEqualTo(tableId);
        }

        @Test
        void encodeUniqueIndex() {
            long tableId = 1;
            long indexId = 2;
            List<Datum> values = List.of(Datum.of(42L));

            byte[] indexKey = TableCodec.encodeIndexKey(tableId, indexId, values, null);
            assertThat(indexKey).isNotEmpty();
        }

        @Test
        void decodeIndexHandle() {
            long tableId = 1;
            long indexId = 2;
            List<Datum> values = List.of(Datum.of(10L));
            long handle = 99;

            byte[] indexKey = TableCodec.encodeIndexKey(tableId, indexId, values, handle);
            long decodedHandle = TableCodec.decodeIndexHandle(indexKey, 1);
            assertThat(decodedHandle).isEqualTo(handle);
        }

        @Test
        void decodeIndexHandleWithMultipleColumns() {
            long tableId = 1;
            long indexId = 3;
            List<Datum> values = List.of(Datum.of(10L), Datum.of("abc"));
            long handle = 42;

            byte[] indexKey = TableCodec.encodeIndexKey(tableId, indexId, values, handle);
            long decodedHandle = TableCodec.decodeIndexHandle(indexKey, 2);
            assertThat(decodedHandle).isEqualTo(handle);
        }

        @Test
        void indexKeyOrderPreserved() {
            // Same index, different values
            byte[] key1 = TableCodec.encodeIndexKey(1, 1, List.of(Datum.of(1L)), 1L);
            byte[] key2 = TableCodec.encodeIndexKey(1, 1, List.of(Datum.of(2L)), 1L);

            assertThat(BytesUtil.compare(key1, key2)).isLessThan(0);
        }

        @Test
        void indexKeyPrefix() {
            byte[] prefix = TableCodec.encodeIndexKeyPrefix(1, 2);
            byte[] fullKey = TableCodec.encodeIndexKey(1, 2, List.of(Datum.of(10L)), 1L);

            assertThat(BytesUtil.hasPrefix(fullKey, prefix)).isTrue();
        }

        @Test
        void indexScanStartHasCorrectPrefix() {
            byte[] scanStart = TableCodec.encodeIndexScanStart(1, 2, List.of(Datum.of(5L)));
            byte[] prefix = TableCodec.encodeIndexKeyPrefix(1, 2);

            assertThat(BytesUtil.hasPrefix(scanStart, prefix)).isTrue();
        }
    }

    // ==================== Table prefixes ====================

    @Nested
    class Prefixes {

        @Test
        void tablePrefix() {
            byte[] prefix = TableCodec.tablePrefix(1);
            byte[] rowKey = TableCodec.encodeRowKey(1, 100);
            assertThat(BytesUtil.hasPrefix(rowKey, prefix)).isTrue();
        }

        @Test
        void tableRecordPrefix() {
            byte[] prefix = TableCodec.tableRecordPrefix(1);
            byte[] rowKey = TableCodec.encodeRowKey(1, 100);
            assertThat(BytesUtil.hasPrefix(rowKey, prefix)).isTrue();
        }

        @Test
        void tableIndexPrefix() {
            byte[] prefix = TableCodec.tableIndexPrefix(1);
            byte[] indexKey = TableCodec.encodeIndexKey(1, 2, List.of(Datum.of(1L)), 1L);
            assertThat(BytesUtil.hasPrefix(indexKey, prefix)).isTrue();
        }

        @Test
        void recordPrefixDoesNotMatchIndex() {
            byte[] recordPrefix = TableCodec.tableRecordPrefix(1);
            byte[] indexKey = TableCodec.encodeIndexKey(1, 2, List.of(Datum.of(1L)), 1L);
            assertThat(BytesUtil.hasPrefix(indexKey, recordPrefix)).isFalse();
        }

        @Test
        void indexPrefixDoesNotMatchRecord() {
            byte[] indexPrefix = TableCodec.tableIndexPrefix(1);
            byte[] rowKey = TableCodec.encodeRowKey(1, 100);
            assertThat(BytesUtil.hasPrefix(rowKey, indexPrefix)).isFalse();
        }
    }
}
