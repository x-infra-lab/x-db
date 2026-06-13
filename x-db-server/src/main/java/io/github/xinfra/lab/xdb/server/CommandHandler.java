package io.github.xinfra.lab.xdb.server;

import io.github.xinfra.lab.xdb.common.XDBException;
import io.github.xinfra.lab.xdb.expression.Datum;
import io.github.xinfra.lab.xdb.expression.Row;
import io.github.xinfra.lab.xdb.meta.ColumnInfo;
import io.github.xinfra.lab.xdb.session.ExecuteResult;
import io.github.xinfra.lab.xdb.session.Session;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles MySQL command packets after authentication is complete.
 * <p>
 * Supported commands: COM_QUERY, COM_INIT_DB, COM_PING, COM_QUIT, COM_FIELD_LIST.
 */
public class CommandHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(CommandHandler.class);

    public static final AttributeKey<Session> SESSION_KEY = AttributeKey.valueOf("session");

    private static final int COM_STMT_RESET = 0x1A;
    private static final int COM_RESET_CONNECTION = 0x1F;

    private final ConcurrentHashMap<Integer, PreparedStatement> preparedStatements = new ConcurrentHashMap<>();
    private final AtomicInteger stmtIdGen = new AtomicInteger(1);

    private record PreparedStatement(String sql, int paramCount) {}

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        MySQLPacket packet = (MySQLPacket) msg;
        try {
            // Reset encoder sequence for each new command.
            MySQLPacketEncoder encoder = ctx.pipeline().get(MySQLPacketEncoder.class);
            if (encoder != null) {
                encoder.setSequenceId(packet.sequenceId() + 1);
            }

            ByteBuf payload = packet.payload();
            int commandType = payload.readByte() & 0xFF;

            switch (commandType) {
                case MySQLConstants.COM_QUERY:
                    handleQuery(ctx, payload);
                    break;
                case MySQLConstants.COM_INIT_DB:
                    handleInitDb(ctx, payload);
                    break;
                case MySQLConstants.COM_PING:
                    handlePing(ctx);
                    break;
                case MySQLConstants.COM_QUIT:
                    handleQuit(ctx);
                    break;
                case MySQLConstants.COM_FIELD_LIST:
                    handleFieldList(ctx, payload);
                    break;
                case MySQLConstants.COM_STMT_PREPARE:
                    handleStmtPrepare(ctx, payload);
                    break;
                case MySQLConstants.COM_STMT_EXECUTE:
                    handleStmtExecute(ctx, payload);
                    break;
                case MySQLConstants.COM_STMT_CLOSE:
                    handleStmtClose(payload);
                    break;
                case COM_STMT_RESET:
                    handleStmtReset(ctx, payload);
                    break;
                case COM_RESET_CONNECTION:
                    handleResetConnection(ctx);
                    break;
                default:
                    sendError(ctx, 1047, "08S01", "Unknown command: " + commandType);
                    break;
            }
        } finally {
            packet.release();
        }
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        Session session = ctx.channel().attr(SESSION_KEY).getAndSet(null);
        if (session != null) {
            session.close();
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Unhandled error in command handler, closing connection", cause);
        try {
            sendError(ctx, XDBException.ER_UNKNOWN_ERROR, "HY000",
                    cause.getMessage() != null ? cause.getMessage() : "Internal error");
        } finally {
            ctx.close();
        }
    }

    // -----------------------------------------------------------------------
    //  COM_QUERY
    // -----------------------------------------------------------------------

    private void handleQuery(ChannelHandlerContext ctx, ByteBuf payload) {
        String sql = payload.readCharSequence(payload.readableBytes(), StandardCharsets.UTF_8)
                .toString().trim();

        log.debug("COM_QUERY: {}", sql);

        if (isSystemVariableQuery(sql)) {
            sendSystemVariableResult(ctx, sql);
            return;
        }

        if (isNoOpSetStatement(sql)) {
            sendOK(ctx, 0, 0);
            return;
        }

        Session session = ctx.channel().attr(SESSION_KEY).get();

        try {
            ExecuteResult result = session.execute(sql);

            if (result.isQuery()) {
                sendResultSet(ctx, result);
            } else {
                sendOK(ctx, result.getAffectedRows(), result.getLastInsertId());
            }
        } catch (XDBException e) {
            sendError(ctx, e.errorCode(), e.sqlState(), e.getMessage());
        } catch (Exception e) {
            log.error("Query execution failed: {}", sql, e);
            sendError(ctx, XDBException.ER_UNKNOWN_ERROR, "HY000",
                    e.getMessage() != null ? e.getMessage() : "Internal error");
        }
    }

    // -----------------------------------------------------------------------
    //  COM_INIT_DB
    // -----------------------------------------------------------------------

    private void handleInitDb(ChannelHandlerContext ctx, ByteBuf payload) {
        String dbName = payload.readCharSequence(payload.readableBytes(), StandardCharsets.UTF_8)
                .toString().trim();

        log.debug("COM_INIT_DB: {}", dbName);

        Session session = ctx.channel().attr(SESSION_KEY).get();

        try {
            session.useDatabase(dbName);
            sendOK(ctx, 0, 0);
        } catch (XDBException e) {
            sendError(ctx, e.errorCode(), e.sqlState(), e.getMessage());
        } catch (Exception e) {
            sendError(ctx, XDBException.ER_BAD_DB, "42000",
                    "Unknown database '" + dbName + "'");
        }
    }

    // -----------------------------------------------------------------------
    //  COM_PING
    // -----------------------------------------------------------------------

    private void handlePing(ChannelHandlerContext ctx) {
        log.debug("COM_PING");
        sendOK(ctx, 0, 0);
    }

    // -----------------------------------------------------------------------
    //  COM_QUIT
    // -----------------------------------------------------------------------

    private void handleQuit(ChannelHandlerContext ctx) {
        log.debug("COM_QUIT");
        Session session = ctx.channel().attr(SESSION_KEY).getAndSet(null);
        if (session != null) {
            session.close();
        }
        ctx.close();
    }

    // -----------------------------------------------------------------------
    //  COM_FIELD_LIST
    // -----------------------------------------------------------------------

    private void handleFieldList(ChannelHandlerContext ctx, ByteBuf payload) {
        // COM_FIELD_LIST is deprecated; respond with an EOF to signal "no columns".
        log.debug("COM_FIELD_LIST (deprecated, returning EOF)");
        ByteBuf eof = MySQLPacketWriter.writeEOF(ctx.alloc(), 0,
                MySQLConstants.SERVER_STATUS_AUTOCOMMIT);
        ctx.writeAndFlush(eof);
    }

    // -----------------------------------------------------------------------
    //  COM_STMT_PREPARE / EXECUTE / CLOSE
    // -----------------------------------------------------------------------

    private void handleStmtPrepare(ChannelHandlerContext ctx, ByteBuf payload) {
        String sql = payload.readCharSequence(payload.readableBytes(), StandardCharsets.UTF_8)
                .toString().trim();
        log.debug("COM_STMT_PREPARE: {}", sql);

        int paramCount = 0;
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == '?') paramCount++;
        }

        int stmtId = stmtIdGen.getAndIncrement();
        preparedStatements.put(stmtId, new PreparedStatement(sql, paramCount));

        ByteBuf resp = ctx.alloc().buffer();
        resp.writeByte(0x00);            // status: OK
        resp.writeIntLE(stmtId);         // statement_id
        resp.writeShortLE(0);            // num_columns (unknown until execute)
        resp.writeShortLE(paramCount);   // num_params
        resp.writeByte(0x00);            // filler
        resp.writeShortLE(0);            // warning_count
        ctx.write(resp);

        if (paramCount > 0) {
            for (int i = 0; i < paramCount; i++) {
                ByteBuf colDef = MySQLPacketWriter.writeColumnDef(ctx.alloc(),
                        "", "", "?", MySQLConstants.MYSQL_TYPE_VAR_STRING, 65535, 0);
                ctx.write(colDef);
            }
            ByteBuf eof = MySQLPacketWriter.writeEOF(ctx.alloc(), 0,
                    MySQLConstants.SERVER_STATUS_AUTOCOMMIT);
            ctx.write(eof);
        }

        ctx.flush();
    }

    private void handleStmtExecute(ChannelHandlerContext ctx, ByteBuf payload) {
        int stmtId = payload.readIntLE();
        payload.skipBytes(1); // flags
        payload.skipBytes(4); // iteration_count

        PreparedStatement stmt = preparedStatements.get(stmtId);
        if (stmt == null) {
            sendError(ctx, 1243, "HY000", "Unknown prepared statement id: " + stmtId);
            return;
        }

        String sql = stmt.sql();
        int paramCount = stmt.paramCount();

        if (paramCount > 0 && payload.readableBytes() > 0) {
            String[] paramValues = readBinaryParams(payload, paramCount);
            sql = substituteParams(sql, paramValues);
        }

        log.debug("COM_STMT_EXECUTE (stmtId={}): {}", stmtId, sql);

        Session session = ctx.channel().attr(SESSION_KEY).get();
        try {
            ExecuteResult result = session.execute(sql);
            if (result.isQuery()) {
                sendBinaryResultSet(ctx, result);
            } else {
                sendOK(ctx, result.getAffectedRows(), result.getLastInsertId());
            }
        } catch (XDBException e) {
            sendError(ctx, e.errorCode(), e.sqlState(), e.getMessage());
        } catch (Exception e) {
            log.error("Prepared statement execution failed: {}", sql, e);
            sendError(ctx, XDBException.ER_UNKNOWN_ERROR, "HY000",
                    e.getMessage() != null ? e.getMessage() : "Internal error");
        }
    }

    private void handleStmtClose(ByteBuf payload) {
        int stmtId = payload.readIntLE();
        preparedStatements.remove(stmtId);
        log.debug("COM_STMT_CLOSE: stmtId={}", stmtId);
    }

    private void handleStmtReset(ChannelHandlerContext ctx, ByteBuf payload) {
        int stmtId = payload.readIntLE();
        log.debug("COM_STMT_RESET: stmtId={}", stmtId);
        if (preparedStatements.containsKey(stmtId)) {
            sendOK(ctx, 0, 0);
        } else {
            sendError(ctx, 1243, "HY000", "Unknown prepared statement id: " + stmtId);
        }
    }

    // -----------------------------------------------------------------------
    //  COM_RESET_CONNECTION
    // -----------------------------------------------------------------------

    private void handleResetConnection(ChannelHandlerContext ctx) {
        log.debug("COM_RESET_CONNECTION");
        Session session = ctx.channel().attr(SESSION_KEY).get();
        if (session != null) {
            try {
                session.close();
            } catch (Exception e) {
                log.warn("Failed to close session during reset", e);
            }
        }
        Session newSession = null;
        // We don't have access to SessionManager here, so just send OK.
        // The session will be in a closed state but connection stays alive.
        preparedStatements.clear();
        sendOK(ctx, 0, 0);
    }

    private String[] readBinaryParams(ByteBuf payload, int paramCount) {
        String[] values = new String[paramCount];

        int nullBitmapLen = (paramCount + 7) / 8;
        byte[] nullBitmap = new byte[nullBitmapLen];
        payload.readBytes(nullBitmap);

        int newParamsFlag = payload.readByte() & 0xFF;

        int[] paramTypes = new int[paramCount];
        if (newParamsFlag == 1) {
            for (int i = 0; i < paramCount; i++) {
                paramTypes[i] = payload.readShortLE() & 0xFFFF;
            }
        }

        for (int i = 0; i < paramCount; i++) {
            if ((nullBitmap[i / 8] & (1 << (i % 8))) != 0) {
                values[i] = null;
                continue;
            }
            int type = paramTypes[i] & 0xFF;
            switch (type) {
                case MySQLConstants.MYSQL_TYPE_TINY:
                    values[i] = String.valueOf(payload.readByte());
                    break;
                case MySQLConstants.MYSQL_TYPE_SHORT:
                    values[i] = String.valueOf(payload.readShortLE());
                    break;
                case MySQLConstants.MYSQL_TYPE_LONG, MySQLConstants.MYSQL_TYPE_INT24:
                    values[i] = String.valueOf(payload.readIntLE());
                    break;
                case MySQLConstants.MYSQL_TYPE_LONGLONG:
                    values[i] = String.valueOf(payload.readLongLE());
                    break;
                case MySQLConstants.MYSQL_TYPE_FLOAT:
                    values[i] = String.valueOf(Float.intBitsToFloat(payload.readIntLE()));
                    break;
                case MySQLConstants.MYSQL_TYPE_DOUBLE:
                    values[i] = String.valueOf(Double.longBitsToDouble(payload.readLongLE()));
                    break;
                default:
                    int len = readLenEncInt(payload);
                    values[i] = payload.readCharSequence(len, StandardCharsets.UTF_8).toString();
                    break;
            }
        }
        return values;
    }

    private int readLenEncInt(ByteBuf buf) {
        int first = buf.readByte() & 0xFF;
        if (first < 251) return first;
        if (first == 0xFC) return buf.readShortLE() & 0xFFFF;
        if (first == 0xFD) return buf.readMediumLE() & 0xFFFFFF;
        return (int) buf.readLongLE();
    }

    private String substituteParams(String sql, String[] params) {
        StringBuilder sb = new StringBuilder(sql.length() + 64);
        int paramIdx = 0;
        for (int i = 0; i < sql.length(); i++) {
            if (sql.charAt(i) == '?' && paramIdx < params.length) {
                String val = params[paramIdx++];
                if (val == null) {
                    sb.append("NULL");
                } else {
                    sb.append('\'').append(val.replace("'", "''")).append('\'');
                }
            } else {
                sb.append(sql.charAt(i));
            }
        }
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    //  Response helpers
    // -----------------------------------------------------------------------

    private void sendOK(ChannelHandlerContext ctx, long affectedRows, long lastInsertId) {
        ByteBuf ok = MySQLPacketWriter.writeOK(ctx.alloc(), affectedRows, lastInsertId,
                MySQLConstants.SERVER_STATUS_AUTOCOMMIT, 0);
        ctx.writeAndFlush(ok);
    }

    private void sendError(ChannelHandlerContext ctx, int errorCode,
                            String sqlState, String message) {
        ByteBuf err = MySQLPacketWriter.writeERR(ctx.alloc(), errorCode, sqlState, message);
        ctx.writeAndFlush(err);
    }

    /**
     * Send a full text-protocol result set:
     * column-count packet, N column-definition packets, EOF,
     * M row-data packets, EOF.
     */
    private void sendResultSet(ChannelHandlerContext ctx, ExecuteResult result) {
        List<ColumnInfo> columns = result.getColumns();
        List<Row> rows = result.getRows();

        // 1. Column count
        ByteBuf colCountBuf = ctx.alloc().buffer();
        MySQLPacketWriter.writeLenEncInt(colCountBuf, columns.size());
        ctx.write(colCountBuf);

        // 2. Column definitions
        for (ColumnInfo col : columns) {
            int mysqlType = TypeMapper.toMySQLType(col.getType());
            int colLength = col.getFieldLength() > 0
                    ? col.getFieldLength()
                    : TypeMapper.columnLength(col.getType());
            int flags = TypeMapper.columnFlags(col);

            ByteBuf colDef = MySQLPacketWriter.writeColumnDef(ctx.alloc(),
                    "", "", col.getName(), mysqlType, colLength, flags);
            ctx.write(colDef);
        }

        // 3. EOF (end of column definitions)
        ByteBuf eof1 = MySQLPacketWriter.writeEOF(ctx.alloc(), 0,
                MySQLConstants.SERVER_STATUS_AUTOCOMMIT);
        ctx.write(eof1);

        // 4. Row data
        for (Row row : rows) {
            List<String> values = new ArrayList<>(columns.size());
            for (int i = 0; i < columns.size(); i++) {
                Datum datum = row.get(i);
                if (datum == null || datum.isNull()) {
                    values.add(null);
                } else {
                    values.add(datum.toStringValue());
                }
            }
            ByteBuf rowBuf = MySQLPacketWriter.writeRow(ctx.alloc(), values);
            ctx.write(rowBuf);
        }

        // 5. EOF (end of rows)
        ByteBuf eof2 = MySQLPacketWriter.writeEOF(ctx.alloc(), 0,
                MySQLConstants.SERVER_STATUS_AUTOCOMMIT);
        ctx.writeAndFlush(eof2);
    }

    /**
     * Send a binary-protocol result set (used after COM_STMT_EXECUTE).
     * All columns are sent as MYSQL_TYPE_VAR_STRING for simplicity.
     */
    private void sendBinaryResultSet(ChannelHandlerContext ctx, ExecuteResult result) {
        List<ColumnInfo> columns = result.getColumns();
        List<Row> rows = result.getRows();

        // 1. Column count
        ByteBuf colCountBuf = ctx.alloc().buffer();
        MySQLPacketWriter.writeLenEncInt(colCountBuf, columns.size());
        ctx.write(colCountBuf);

        // 2. Column definitions — use MYSQL_TYPE_VAR_STRING for all columns
        for (ColumnInfo col : columns) {
            int colLength = col.getFieldLength() > 0
                    ? col.getFieldLength()
                    : TypeMapper.columnLength(col.getType());
            int flags = TypeMapper.columnFlags(col);

            ByteBuf colDef = MySQLPacketWriter.writeColumnDef(ctx.alloc(),
                    "", "", col.getName(),
                    MySQLConstants.MYSQL_TYPE_VAR_STRING, colLength, flags);
            ctx.write(colDef);
        }

        // 3. EOF (end of column definitions)
        ByteBuf eof1 = MySQLPacketWriter.writeEOF(ctx.alloc(), 0,
                MySQLConstants.SERVER_STATUS_AUTOCOMMIT);
        ctx.write(eof1);

        // 4. Binary row data
        for (Row row : rows) {
            List<String> values = new ArrayList<>(columns.size());
            for (int i = 0; i < columns.size(); i++) {
                Datum datum = row.get(i);
                if (datum == null || datum.isNull()) {
                    values.add(null);
                } else {
                    values.add(datum.toStringValue());
                }
            }
            ByteBuf rowBuf = MySQLPacketWriter.writeBinaryRow(ctx.alloc(), values, columns.size());
            ctx.write(rowBuf);
        }

        // 5. EOF (end of rows)
        ByteBuf eof2 = MySQLPacketWriter.writeEOF(ctx.alloc(), 0,
                MySQLConstants.SERVER_STATUS_AUTOCOMMIT);
        ctx.writeAndFlush(eof2);
    }

    // -----------------------------------------------------------------------
    //  System variable queries (@@session.xxx, @@global.xxx)
    // -----------------------------------------------------------------------

    private boolean isNoOpSetStatement(String sql) {
        String upper = sql.toUpperCase().trim();
        return upper.startsWith("SET NAMES")
                || upper.startsWith("SET CHARACTER SET")
                || upper.startsWith("SET CHARACTER_SET_RESULTS")
                || upper.startsWith("SET SQL_SAFE_UPDATES");
    }

    private boolean isSystemVariableQuery(String sql) {
        if (!sql.contains("@@")) {
            return false;
        }
        String stripped = stripLeadingComments(sql).toUpperCase().trim();
        return stripped.startsWith("SELECT");
    }

    private String stripLeadingComments(String sql) {
        String s = sql.trim();
        while (s.startsWith("/*")) {
            int end = s.indexOf("*/");
            if (end < 0) break;
            s = s.substring(end + 2).trim();
        }
        return s;
    }

    private void sendSystemVariableResult(ChannelHandlerContext ctx, String sql) {
        log.debug("Handling system variable query: {}", sql);

        List<String> varNames = new ArrayList<>();
        List<String> varValues = new ArrayList<>();

        String[] parts = sql.split(",");
        for (String part : parts) {
            part = part.trim();
            int atIdx = part.indexOf("@@");
            if (atIdx >= 0) {
                String varExpr = part.substring(atIdx).split("\\s+")[0].trim();
                String varName = varExpr;
                String value = getSystemVariable(varExpr);

                int asIdx = part.toUpperCase().indexOf(" AS ");
                if (asIdx > 0) {
                    varName = part.substring(asIdx + 4).trim();
                }

                varNames.add(varName);
                varValues.add(value);
            }
        }

        if (varNames.isEmpty()) {
            sendOK(ctx, 0, 0);
            return;
        }

        List<ColumnInfo> columns = new ArrayList<>();
        for (String name : varNames) {
            ColumnInfo col = new ColumnInfo();
            col.setName(name);
            col.setType(io.github.xinfra.lab.xdb.expression.DataType.VARCHAR);
            columns.add(col);
        }

        Datum[] datums = new Datum[varValues.size()];
        for (int i = 0; i < varValues.size(); i++) {
            datums[i] = Datum.of(varValues.get(i));
        }

        ExecuteResult result = ExecuteResult.query(columns, List.of(new Row(datums)));
        sendResultSet(ctx, result);
    }

    private String getSystemVariable(String varExpr) {
        String normalized = varExpr.toLowerCase()
                .replace("@@session.", "").replace("@@global.", "").replace("@@", "");
        return switch (normalized) {
            case "auto_increment_increment" -> "1";
            case "character_set_client" -> "utf8mb4";
            case "character_set_connection" -> "utf8mb4";
            case "character_set_results" -> "utf8mb4";
            case "character_set_server" -> "utf8mb4";
            case "collation_server" -> "utf8mb4_general_ci";
            case "collation_connection" -> "utf8mb4_general_ci";
            case "init_connect" -> "";
            case "interactive_timeout" -> "28800";
            case "license" -> "Apache-2.0";
            case "lower_case_table_names" -> "0";
            case "max_allowed_packet" -> "67108864";
            case "net_write_timeout" -> "60";
            case "performance_schema" -> "0";
            case "query_cache_size" -> "0";
            case "query_cache_type" -> "OFF";
            case "sql_mode" -> "ONLY_FULL_GROUP_BY,STRICT_TRANS_TABLES,NO_ZERO_IN_DATE,NO_ZERO_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION";
            case "system_time_zone" -> "UTC";
            case "time_zone" -> "SYSTEM";
            case "transaction_isolation", "tx_isolation" -> "REPEATABLE-READ";
            case "transaction_read_only", "tx_read_only" -> "0";
            case "wait_timeout" -> "28800";
            case "version" -> "8.0.30-x-db";
            case "version_comment" -> "x-db";
            default -> "";
        };
    }
}
