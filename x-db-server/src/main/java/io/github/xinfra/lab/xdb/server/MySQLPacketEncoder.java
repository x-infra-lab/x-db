package io.github.xinfra.lab.xdb.server;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;

/**
 * Encodes outgoing {@link ByteBuf} messages with the MySQL 4-byte packet header.
 * <p>
 * Each message written through this encoder is prefixed with:
 * 3 bytes payload length (little-endian) | 1 byte sequence id.
 * <p>
 * Payloads exceeding 0xFFFFFF (16 MB - 1) bytes are split into multiple
 * packets per the MySQL protocol. If the payload is an exact multiple of
 * 0xFFFFFF, a final zero-length packet is appended to signal completion.
 * <p>
 * The caller is responsible for managing sequence-id semantics by calling
 * {@link #resetSequence()} at the start of each command/response cycle.
 */
public class MySQLPacketEncoder extends MessageToByteEncoder<ByteBuf> {

    static final int MAX_PACKET_PAYLOAD = 0xFFFFFF;

    private int sequenceId = 0;

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {
        int remaining = msg.readableBytes();

        while (remaining >= MAX_PACKET_PAYLOAD) {
            out.writeMediumLE(MAX_PACKET_PAYLOAD);
            out.writeByte(sequenceId++);
            out.writeBytes(msg, MAX_PACKET_PAYLOAD);
            remaining -= MAX_PACKET_PAYLOAD;
        }

        // Final packet (or only packet if payload < MAX_PACKET_PAYLOAD).
        // Also handles the exact-multiple case: remaining == 0 sends
        // a zero-length terminator as required by the protocol.
        out.writeMediumLE(remaining);
        out.writeByte(sequenceId++);
        if (remaining > 0) {
            out.writeBytes(msg, remaining);
        }
    }

    /**
     * Reset the sequence id to zero (typically at the start of a new command).
     */
    public void resetSequence() {
        this.sequenceId = 0;
    }

    /**
     * Set the sequence id to a specific value (e.g. to continue from a client request).
     */
    public void setSequenceId(int sequenceId) {
        this.sequenceId = sequenceId;
    }
}
