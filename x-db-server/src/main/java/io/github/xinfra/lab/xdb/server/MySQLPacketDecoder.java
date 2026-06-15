package io.github.xinfra.lab.xdb.server;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.util.List;

/**
 * Decodes the MySQL packet framing layer.
 * <p>
 * Wire format: 3 bytes payload length (little-endian) | 1 byte sequence id | payload.
 * Packets whose payload exceeds 16 MB are split into 0xFFFFFF-byte chunks by the
 * sender; this decoder reassembles them into a single {@link MySQLPacket}.
 */
public class MySQLPacketDecoder extends ByteToMessageDecoder {

    private static final int HEADER_SIZE = 4;
    private static final int MAX_PACKET_PAYLOAD = 0xFFFFFF; // 16 MB - 1

    /** Accumulator for multi-packet payloads (lazy-init). */
    private ByteBuf accumulated;

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        while (in.readableBytes() >= HEADER_SIZE) {
            in.markReaderIndex();

            int payloadLength = in.readUnsignedMediumLE();
            int sequenceId = in.readByte() & 0xFF;

            if (in.readableBytes() < payloadLength) {
                in.resetReaderIndex();
                return; // wait for more data
            }

            ByteBuf payload = in.readBytes(payloadLength);

            if (payloadLength == MAX_PACKET_PAYLOAD) {
                // This is a chunk of a multi-packet payload; accumulate.
                if (accumulated == null) {
                    accumulated = ctx.alloc().compositeBuffer()
                            .addComponent(true, payload);
                } else {
                    accumulated = ctx.alloc().compositeBuffer()
                            .addComponents(true, accumulated, payload);
                }
                // Continue reading the next chunk.
            } else {
                // Final (or only) packet.
                if (accumulated != null) {
                    accumulated = ctx.alloc().compositeBuffer()
                            .addComponents(true, accumulated, payload);
                    out.add(new MySQLPacket(sequenceId, accumulated));
                    accumulated = null;
                } else {
                    out.add(new MySQLPacket(sequenceId, payload));
                }
            }
        }
    }

    @Override
    protected void handlerRemoved0(ChannelHandlerContext ctx) {
        if (accumulated != null) {
            accumulated.release();
            accumulated = null;
        }
    }
}
