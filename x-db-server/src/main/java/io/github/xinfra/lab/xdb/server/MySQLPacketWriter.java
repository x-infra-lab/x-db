package io.github.xinfra.lab.xdb.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;

import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Utility methods for building MySQL protocol response packets.
 * <p>
 * All methods produce raw payload buffers (without the 4-byte header);
 * the header is prepended by {@link MySQLPacketEncoder}.
 */
public final class MySQLPacketWriter {

    private MySQLPacketWriter() {}

    // -----------------------------------------------------------------------
    //  OK packet
    // -----------------------------------------------------------------------

    /**
     * Write an OK packet.
     *
     * @param alloc        buffer allocator
     * @param affectedRows number of affected rows
     * @param lastInsertId last auto-generated id
     * @param statusFlags  server status flags
     * @param warnings     warning count
     * @return the payload buffer (caller must release after write)
     */
    public static ByteBuf writeOK(ByteBufAllocator alloc, long affectedRows,
                                   long lastInsertId, int statusFlags, int warnings) {
        ByteBuf buf = alloc.buffer();
        buf.writeByte(MySQLConstants.OK_PACKET); // 0x00 header
        writeLenEncInt(buf, affectedRows);
        writeLenEncInt(buf, lastInsertId);
        buf.writeShortLE(statusFlags);           // status_flags (2 bytes)
        buf.writeShortLE(warnings);              // warnings     (2 bytes)
        return buf;
    }

    // -----------------------------------------------------------------------
    //  ERR packet
    // -----------------------------------------------------------------------

    /**
     * Write an ERR packet.
     *
     * @param alloc     buffer allocator
     * @param errorCode MySQL error code
     * @param sqlState  5-char SQLSTATE
     * @param message   human-readable error message
     * @return the payload buffer
     */
    public static ByteBuf writeERR(ByteBufAllocator alloc, int errorCode,
                                    String sqlState, String message) {
        ByteBuf buf = alloc.buffer();
        buf.writeByte(MySQLConstants.ERR_PACKET); // 0xFF header
        buf.writeShortLE(errorCode);
        buf.writeByte('#');                       // sql_state_marker
        buf.writeBytes(sqlState.getBytes(StandardCharsets.UTF_8)); // 5 chars
        buf.writeBytes(message.getBytes(StandardCharsets.UTF_8));
        return buf;
    }

    // -----------------------------------------------------------------------
    //  EOF packet
    // -----------------------------------------------------------------------

    /**
     * Write an EOF packet (used in the text result-set protocol).
     *
     * @param alloc       buffer allocator
     * @param warnings    warning count
     * @param statusFlags server status flags
     * @return the payload buffer
     */
    public static ByteBuf writeEOF(ByteBufAllocator alloc, int warnings, int statusFlags) {
        ByteBuf buf = alloc.buffer(5);
        buf.writeByte(MySQLConstants.EOF_PACKET); // 0xFE header
        buf.writeShortLE(warnings);
        buf.writeShortLE(statusFlags);
        return buf;
    }

    // -----------------------------------------------------------------------
    //  Column Definition (COM_QUERY response)
    // -----------------------------------------------------------------------

    /**
     * Write a column definition packet (Protocol::ColumnDefinition41).
     *
     * @param alloc        buffer allocator
     * @param schema       schema (database) name
     * @param table        table name
     * @param name         column name
     * @param mysqlType    MySQL column type constant
     * @param columnLength max display length
     * @param flags        column flags (NOT_NULL, PRI_KEY, etc.)
     * @return the payload buffer
     */
    public static ByteBuf writeColumnDef(ByteBufAllocator alloc, String schema,
                                          String table, String name,
                                          int mysqlType, int columnLength, int flags) {
        ByteBuf buf = alloc.buffer();
        writeLenEncString(buf, "def");            // catalog (always "def")
        writeLenEncString(buf, schema != null ? schema : "");  // schema
        writeLenEncString(buf, table != null ? table : "");    // table (virtual)
        writeLenEncString(buf, table != null ? table : "");    // org_table
        writeLenEncString(buf, name);             // name
        writeLenEncString(buf, name);             // org_name
        buf.writeByte(0x0C);                      // length of fixed-length fields
        buf.writeShortLE(MySQLConstants.CHARSET_UTF8MB4); // character_set
        buf.writeIntLE(columnLength);             // column_length
        buf.writeByte(mysqlType);                 // column_type
        buf.writeShortLE(flags);                  // flags
        buf.writeByte(0);                         // decimals
        buf.writeShortLE(0);                      // filler
        return buf;
    }

    // -----------------------------------------------------------------------
    //  Result Row (text protocol)
    // -----------------------------------------------------------------------

    /**
     * Write a text-protocol result row.
     * Each value is encoded as a length-encoded string; null values use 0xFB.
     *
     * @param alloc  buffer allocator
     * @param values column values (null entries produce the NULL marker)
     * @return the payload buffer
     */
    public static ByteBuf writeRow(ByteBufAllocator alloc, List<String> values) {
        ByteBuf buf = alloc.buffer();
        for (String value : values) {
            if (value == null) {
                buf.writeByte(0xFB); // NULL marker
            } else {
                writeLenEncString(buf, value);
            }
        }
        return buf;
    }

    // -----------------------------------------------------------------------
    //  Binary Result Row (COM_STMT_EXECUTE response)
    // -----------------------------------------------------------------------

    /**
     * Write a binary-protocol result row.
     * Format: 0x00 header, NULL bitmap (with 2-bit offset), then binary-encoded values.
     *
     * @param alloc      buffer allocator
     * @param values     column values (null entries are marked in the bitmap)
     * @param columnCount number of columns
     * @return the payload buffer
     */
    public static ByteBuf writeBinaryRow(ByteBufAllocator alloc, List<String> values, int columnCount) {
        ByteBuf buf = alloc.buffer();
        buf.writeByte(0x00); // packet header

        int nullBitmapLen = (columnCount + 7 + 2) / 8;
        byte[] nullBitmap = new byte[nullBitmapLen];
        for (int i = 0; i < columnCount; i++) {
            if (i >= values.size() || values.get(i) == null) {
                int bytePos = (i + 2) / 8;
                int bitPos = (i + 2) % 8;
                nullBitmap[bytePos] |= (byte) (1 << bitPos);
            }
        }
        buf.writeBytes(nullBitmap);

        for (int i = 0; i < columnCount; i++) {
            if (i >= values.size() || values.get(i) == null) {
                continue;
            }
            writeLenEncString(buf, values.get(i));
        }
        return buf;
    }

    // -----------------------------------------------------------------------
    //  Length-encoded integer
    // -----------------------------------------------------------------------

    /**
     * Write a length-encoded integer to the buffer.
     */
    public static void writeLenEncInt(ByteBuf buf, long value) {
        if (value < 251) {
            buf.writeByte((int) value);
        } else if (value < 65536) {
            buf.writeByte(0xFC);
            buf.writeShortLE((int) value);
        } else if (value < 16777216) {
            buf.writeByte(0xFD);
            buf.writeMediumLE((int) value);
        } else {
            buf.writeByte(0xFE);
            buf.writeLongLE(value);
        }
    }

    // -----------------------------------------------------------------------
    //  Length-encoded string
    // -----------------------------------------------------------------------

    /**
     * Write a length-encoded string (UTF-8) to the buffer.
     */
    public static void writeLenEncString(ByteBuf buf, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeLenEncInt(buf, bytes.length);
        buf.writeBytes(bytes);
    }

    // -----------------------------------------------------------------------
    //  Null-terminated string
    // -----------------------------------------------------------------------

    /**
     * Write a null-terminated string (UTF-8) to the buffer.
     */
    public static void writeNullTermString(ByteBuf buf, String value) {
        buf.writeBytes(value.getBytes(StandardCharsets.UTF_8));
        buf.writeByte(0x00);
    }
}
