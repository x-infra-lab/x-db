package io.github.xinfra.lab.xdb.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MySQLPacketEncoderTest {

    private static final int MAX = MySQLPacketEncoder.MAX_PACKET_PAYLOAD;

    @Test
    @DisplayName("small payload: single packet with correct header")
    void smallPayload() {
        EmbeddedChannel ch = new EmbeddedChannel(new MySQLPacketEncoder());

        byte[] data = new byte[100];
        for (int i = 0; i < data.length; i++) data[i] = (byte) i;
        ch.writeOutbound(Unpooled.wrappedBuffer(data));

        ByteBuf out = ch.readOutbound();
        assertThat(out.readUnsignedMediumLE()).isEqualTo(100);
        assertThat(out.readByte() & 0xFF).isEqualTo(0);

        byte[] payload = new byte[100];
        out.readBytes(payload);
        assertThat(payload).isEqualTo(data);
        assertThat(out.readableBytes()).isZero();

        out.release();
        ch.finish();
    }

    @Test
    @DisplayName("payload exactly MAX_PACKET_PAYLOAD: one full chunk + zero-length terminator")
    void exactMaxPayload() {
        EmbeddedChannel ch = new EmbeddedChannel(new MySQLPacketEncoder());

        ByteBuf msg = Unpooled.buffer(MAX);
        msg.writeBytes(new byte[MAX]);
        ch.writeOutbound(msg);

        ByteBuf out = ch.readOutbound();

        // First chunk: full 0xFFFFFF bytes
        assertThat(out.readUnsignedMediumLE()).isEqualTo(MAX);
        assertThat(out.readByte() & 0xFF).isEqualTo(0); // seq 0
        out.skipBytes(MAX);

        // Terminator: 0-length packet
        assertThat(out.readUnsignedMediumLE()).isZero();
        assertThat(out.readByte() & 0xFF).isEqualTo(1); // seq 1

        assertThat(out.readableBytes()).isZero();
        out.release();
        ch.finish();
    }

    @Test
    @DisplayName("payload larger than MAX: split into chunks with correct sequence ids")
    void largePayload() {
        EmbeddedChannel ch = new EmbeddedChannel(new MySQLPacketEncoder());

        int totalSize = MAX + 500;
        ByteBuf msg = Unpooled.buffer(totalSize);
        byte[] data = new byte[totalSize];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i & 0xFF);
        msg.writeBytes(data);
        ch.writeOutbound(msg);

        ByteBuf out = ch.readOutbound();

        // First chunk: 0xFFFFFF bytes, seq 0
        assertThat(out.readUnsignedMediumLE()).isEqualTo(MAX);
        assertThat(out.readByte() & 0xFF).isEqualTo(0);
        byte[] chunk1 = new byte[MAX];
        out.readBytes(chunk1);

        // Second chunk: 500 bytes, seq 1
        assertThat(out.readUnsignedMediumLE()).isEqualTo(500);
        assertThat(out.readByte() & 0xFF).isEqualTo(1);
        byte[] chunk2 = new byte[500];
        out.readBytes(chunk2);

        // Verify data integrity
        byte[] reassembled = new byte[totalSize];
        System.arraycopy(chunk1, 0, reassembled, 0, MAX);
        System.arraycopy(chunk2, 0, reassembled, MAX, 500);
        assertThat(reassembled).isEqualTo(data);

        assertThat(out.readableBytes()).isZero();
        out.release();
        ch.finish();
    }

    @Test
    @DisplayName("payload exactly 2*MAX: two full chunks + zero-length terminator")
    void doubleMaxPayload() {
        EmbeddedChannel ch = new EmbeddedChannel(new MySQLPacketEncoder());

        int totalSize = MAX * 2;
        ByteBuf msg = Unpooled.buffer(totalSize);
        msg.writeBytes(new byte[totalSize]);
        ch.writeOutbound(msg);

        ByteBuf out = ch.readOutbound();

        // Chunk 1: MAX, seq 0
        assertThat(out.readUnsignedMediumLE()).isEqualTo(MAX);
        assertThat(out.readByte() & 0xFF).isEqualTo(0);
        out.skipBytes(MAX);

        // Chunk 2: MAX, seq 1
        assertThat(out.readUnsignedMediumLE()).isEqualTo(MAX);
        assertThat(out.readByte() & 0xFF).isEqualTo(1);
        out.skipBytes(MAX);

        // Terminator: 0-length, seq 2
        assertThat(out.readUnsignedMediumLE()).isZero();
        assertThat(out.readByte() & 0xFF).isEqualTo(2);

        assertThat(out.readableBytes()).isZero();
        out.release();
        ch.finish();
    }

    @Test
    @DisplayName("encoder + decoder round-trip preserves large payload")
    void roundTrip() {
        EmbeddedChannel ch = new EmbeddedChannel(
                new MySQLPacketEncoder(), new MySQLPacketDecoder());

        int totalSize = MAX + 1024;
        byte[] data = new byte[totalSize];
        for (int i = 0; i < data.length; i++) data[i] = (byte) (i % 251);
        ch.writeOutbound(Unpooled.wrappedBuffer(data));

        // Read encoded bytes and feed to inbound (decoder)
        ByteBuf encoded = ch.readOutbound();
        ch.writeInbound(encoded);

        MySQLPacket packet = ch.readInbound();
        assertThat(packet).isNotNull();
        ByteBuf payload = packet.payload();
        assertThat(payload.readableBytes()).isEqualTo(totalSize);

        byte[] decoded = new byte[totalSize];
        payload.readBytes(decoded);
        assertThat(decoded).isEqualTo(data);

        packet.release();
        ch.finish();
    }

    @Test
    @DisplayName("sequence id increments across chunks and resets correctly")
    void sequenceIdManagement() {
        MySQLPacketEncoder encoder = new MySQLPacketEncoder();
        EmbeddedChannel ch = new EmbeddedChannel(encoder);

        // First message: small
        ch.writeOutbound(Unpooled.wrappedBuffer(new byte[10]));
        ByteBuf out1 = ch.readOutbound();
        out1.readUnsignedMediumLE();
        assertThat(out1.readByte() & 0xFF).isEqualTo(0);
        out1.release();

        // Second message: seq continues at 1
        ch.writeOutbound(Unpooled.wrappedBuffer(new byte[20]));
        ByteBuf out2 = ch.readOutbound();
        out2.readUnsignedMediumLE();
        assertThat(out2.readByte() & 0xFF).isEqualTo(1);
        out2.release();

        // Reset and verify
        encoder.resetSequence();
        ch.writeOutbound(Unpooled.wrappedBuffer(new byte[30]));
        ByteBuf out3 = ch.readOutbound();
        out3.readUnsignedMediumLE();
        assertThat(out3.readByte() & 0xFF).isEqualTo(0);
        out3.release();

        ch.finish();
    }
}
