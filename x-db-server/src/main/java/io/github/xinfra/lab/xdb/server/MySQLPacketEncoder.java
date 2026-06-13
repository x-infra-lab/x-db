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
 * The caller is responsible for managing sequence-id semantics by calling
 * {@link #resetSequence()} at the start of each command/response cycle.
 */
public class MySQLPacketEncoder extends MessageToByteEncoder<ByteBuf> {

    private int sequenceId = 0;

    @Override
    protected void encode(ChannelHandlerContext ctx, ByteBuf msg, ByteBuf out) {
        int length = msg.readableBytes();
        out.writeMediumLE(length);
        out.writeByte(sequenceId++);
        out.writeBytes(msg);
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
