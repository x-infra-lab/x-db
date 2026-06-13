package io.github.xinfra.lab.xdb.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class KeyBuilderTest {

    @Test
    void emptyBuilder() {
        byte[] key = KeyBuilder.builder().build();
        assertThat(key).isEmpty();
    }

    @Test
    void appendSingleByte() {
        byte[] key = KeyBuilder.builder()
                .appendByte(0x74)
                .build();
        assertThat(key).hasSize(1);
        assertThat(key[0]).isEqualTo((byte) 0x74);
    }

    @Test
    void appendBytes() {
        byte[] data = {1, 2, 3, 4};
        byte[] key = KeyBuilder.builder()
                .appendBytes(data)
                .build();
        assertThat(key).isEqualTo(data);
    }

    @Test
    void appendInt64() {
        long value = 42L;
        byte[] key = KeyBuilder.builder()
                .appendInt64(value)
                .build();
        assertThat(key).hasSize(8);
        assertThat(Codec.decodeInt64(key, 0)).isEqualTo(value);
    }

    @Test
    void appendUint64() {
        long value = 100L;
        byte[] key = KeyBuilder.builder()
                .appendUint64(value)
                .build();
        assertThat(key).hasSize(8);
        assertThat(Codec.decodeUint64(key, 0)).isEqualTo(value);
    }

    @Test
    void appendEncodedBytes() {
        byte[] raw = "hello".getBytes();
        byte[] key = KeyBuilder.builder()
                .appendEncodedBytes(raw)
                .build();
        int[] bytesRead = {0};
        byte[] decoded = Codec.decodeBytes(key, 0, bytesRead);
        assertThat(decoded).isEqualTo(raw);
    }

    @Test
    void fluent_chain_builds_composite_key() {
        byte[] key = KeyBuilder.builder()
                .appendByte(0x74)        // 't' prefix
                .appendInt64(1L)         // table ID
                .appendBytes(new byte[]{0x5F, 0x72}) // '_r'
                .appendInt64(100L)       // row handle
                .build();

        assertThat(key).hasSize(1 + 8 + 2 + 8); // 19 bytes
        assertThat(key[0]).isEqualTo((byte) 0x74);
        assertThat(Codec.decodeInt64(key, 1)).isEqualTo(1L);
        assertThat(key[9]).isEqualTo((byte) 0x5F);
        assertThat(key[10]).isEqualTo((byte) 0x72);
        assertThat(Codec.decodeInt64(key, 11)).isEqualTo(100L);
    }

    @Test
    void length_tracks_accumulated_size() {
        KeyBuilder builder = KeyBuilder.builder();
        assertThat(builder.length()).isEqualTo(0);

        builder.appendByte(0x01);
        assertThat(builder.length()).isEqualTo(1);

        builder.appendInt64(0L);
        assertThat(builder.length()).isEqualTo(9);

        builder.appendBytes(new byte[]{1, 2, 3});
        assertThat(builder.length()).isEqualTo(12);
    }

    @Test
    void multipleBuildsReturnSameResult() {
        KeyBuilder builder = KeyBuilder.builder()
                .appendByte(0x01)
                .appendInt64(42L);
        byte[] first = builder.build();
        byte[] second = builder.build();
        assertThat(first).isEqualTo(second);
    }
}
