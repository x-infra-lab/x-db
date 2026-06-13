package io.github.xinfra.lab.xdb.server;

import io.netty.buffer.ByteBuf;

/**
 * A decoded MySQL protocol packet (header stripped, payload retained).
 */
public class MySQLPacket {

    private final int sequenceId;
    private final ByteBuf payload;

    public MySQLPacket(int sequenceId, ByteBuf payload) {
        this.sequenceId = sequenceId;
        this.payload = payload;
    }

    public int sequenceId() {
        return sequenceId;
    }

    public ByteBuf payload() {
        return payload;
    }

    /**
     * Release the underlying payload buffer.
     * Must be called once the packet has been fully consumed.
     */
    public void release() {
        if (payload != null && payload.refCnt() > 0) {
            payload.release();
        }
    }
}
