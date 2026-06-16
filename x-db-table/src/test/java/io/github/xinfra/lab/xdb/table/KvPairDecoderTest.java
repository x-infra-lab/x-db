package io.github.xinfra.lab.xdb.table;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class KvPairDecoderTest {

    @Test
    void decodeEmpty() {
        assertThat(KvPairDecoder.decode(null)).isEmpty();
        assertThat(KvPairDecoder.decode(new byte[0])).isEmpty();
        assertThat(KvPairDecoder.decode(new byte[3])).isEmpty();
    }

    @Test
    void decodeZeroPairs() {
        byte[] data = ByteBuffer.allocate(4).putInt(0).array();
        assertThat(KvPairDecoder.decode(data)).isEmpty();
    }

    @Test
    void encodeDecodeRoundTrip() {
        var pairs = List.of(
                new KvPairDecoder.KvPair(new byte[]{1, 2, 3}, new byte[]{4, 5}),
                new KvPairDecoder.KvPair(new byte[]{10}, new byte[]{20, 30, 40, 50}),
                new KvPairDecoder.KvPair(new byte[]{}, new byte[]{})
        );
        byte[] encoded = KvPairDecoder.encode(pairs);
        List<KvPairDecoder.KvPair> decoded = KvPairDecoder.decode(encoded);

        assertThat(decoded).hasSize(3);
        assertThat(decoded.get(0).key()).isEqualTo(new byte[]{1, 2, 3});
        assertThat(decoded.get(0).value()).isEqualTo(new byte[]{4, 5});
        assertThat(decoded.get(1).key()).isEqualTo(new byte[]{10});
        assertThat(decoded.get(1).value()).isEqualTo(new byte[]{20, 30, 40, 50});
        assertThat(decoded.get(2).key()).isEqualTo(new byte[]{});
        assertThat(decoded.get(2).value()).isEqualTo(new byte[]{});
    }

    @Test
    void decodeSinglePair() {
        byte[] key = "hello".getBytes();
        byte[] val = "world".getBytes();
        ByteBuffer bb = ByteBuffer.allocate(4 + 4 + key.length + 4 + val.length);
        bb.putInt(1);
        bb.putInt(key.length);
        bb.put(key);
        bb.putInt(val.length);
        bb.put(val);

        var decoded = KvPairDecoder.decode(bb.array());
        assertThat(decoded).hasSize(1);
        assertThat(new String(decoded.get(0).key())).isEqualTo("hello");
        assertThat(new String(decoded.get(0).value())).isEqualTo("world");
    }

    @Test
    void encodeDecodeMultiplePairs() {
        var pairs = new java.util.ArrayList<KvPairDecoder.KvPair>();
        for (int i = 0; i < 100; i++) {
            pairs.add(new KvPairDecoder.KvPair(
                    ("k" + i).getBytes(),
                    ("v" + i).getBytes()));
        }
        byte[] encoded = KvPairDecoder.encode(pairs);
        var decoded = KvPairDecoder.decode(encoded);
        assertThat(decoded).hasSize(100);
        for (int i = 0; i < 100; i++) {
            assertThat(new String(decoded.get(i).key())).isEqualTo("k" + i);
            assertThat(new String(decoded.get(i).value())).isEqualTo("v" + i);
        }
    }
}
