package io.github.xinfra.lab.xdb.server;

import io.github.xinfra.lab.xdb.session.Session;
import io.github.xinfra.lab.xdb.session.SessionManager;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.netty.util.concurrent.EventExecutorGroup;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Handles the MySQL handshake and authentication sequence.
 * <p>
 * On channel activation this handler sends the server greeting (handshake v10).
 * When the client responds with its handshake response, we accept the connection
 * (no password check for now), create a {@link Session}, attach it to the channel,
 * and replace this handler with {@link CommandHandler}.
 */
public class AuthenticationHandler extends ChannelInboundHandlerAdapter {

    private static final Logger log = LoggerFactory.getLogger(AuthenticationHandler.class);

    private static final String SERVER_VERSION = "8.0.30-x-db";
    private static final String AUTH_PLUGIN = "mysql_native_password";
    private static final AtomicInteger connectionIdGenerator = new AtomicInteger(0);

    private final SessionManager sessionManager;
    private final int maxConnections;
    private final EventExecutorGroup queryExecutorGroup;
    private boolean authenticated = false;

    public AuthenticationHandler(SessionManager sessionManager) {
        this(sessionManager, 0, null);
    }

    public AuthenticationHandler(SessionManager sessionManager, int maxConnections,
                                 EventExecutorGroup queryExecutorGroup) {
        this.sessionManager = sessionManager;
        this.maxConnections = maxConnections;
        this.queryExecutorGroup = queryExecutorGroup;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        sendHandshake(ctx);
        super.channelActive(ctx);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        MySQLPacket packet = (MySQLPacket) msg;
        try {
            if (!authenticated) {
                handleHandshakeResponse(ctx, packet);
            } else {
                // Should not happen -- once authenticated we remove this handler.
                ctx.fireChannelRead(msg);
            }
        } finally {
            packet.release();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Error during authentication", cause);
        ctx.close();
    }

    // -----------------------------------------------------------------------
    //  Handshake (server greeting)
    // -----------------------------------------------------------------------

    private void sendHandshake(ChannelHandlerContext ctx) {
        int connectionId = connectionIdGenerator.incrementAndGet();

        // Generate 20 bytes of random auth-plugin-data.
        byte[] authData = new byte[20];
        ThreadLocalRandom.current().nextBytes(authData);

        int serverCapabilities = MySQLConstants.CLIENT_LONG_PASSWORD
                | MySQLConstants.CLIENT_PROTOCOL_41
                | MySQLConstants.CLIENT_SECURE_CONNECTION
                | MySQLConstants.CLIENT_PLUGIN_AUTH
                | MySQLConstants.CLIENT_CONNECT_WITH_DB;

        ByteBuf payload = ctx.alloc().buffer();

        // Protocol version
        payload.writeByte(10);

        // Server version (null-terminated)
        MySQLPacketWriter.writeNullTermString(payload, SERVER_VERSION);

        // Connection id (4 bytes LE)
        payload.writeIntLE(connectionId);

        // Auth-plugin-data part 1 (8 bytes)
        payload.writeBytes(authData, 0, 8);

        // Filler
        payload.writeByte(0x00);

        // Capability flags (lower 2 bytes)
        payload.writeShortLE(serverCapabilities & 0xFFFF);

        // Character set (utf8mb4)
        payload.writeByte(MySQLConstants.CHARSET_UTF8MB4);

        // Status flags
        payload.writeShortLE(MySQLConstants.SERVER_STATUS_AUTOCOMMIT);

        // Capability flags (upper 2 bytes)
        payload.writeShortLE((serverCapabilities >> 16) & 0xFFFF);

        // Auth-plugin-data length (1 byte): total length of auth data
        payload.writeByte(authData.length + 1); // include trailing NUL

        // Reserved (10 bytes of 0x00)
        payload.writeZero(10);

        // Auth-plugin-data part 2 (remaining 12 bytes + NUL)
        payload.writeBytes(authData, 8, 12);
        payload.writeByte(0x00);

        // Auth-plugin name (null-terminated)
        MySQLPacketWriter.writeNullTermString(payload, AUTH_PLUGIN);

        ctx.writeAndFlush(payload);

        log.debug("Sent handshake to client (connectionId={})", connectionId);
    }

    // -----------------------------------------------------------------------
    //  Client handshake response
    // -----------------------------------------------------------------------

    private void handleHandshakeResponse(ChannelHandlerContext ctx, MySQLPacket packet) {
        ByteBuf payload = packet.payload();

        // Parse client capabilities (4 bytes)
        int clientCapabilities = payload.readIntLE();

        // Max packet size (4 bytes)
        payload.readIntLE();

        // Character set (1 byte)
        payload.readByte();

        // Reserved (23 bytes)
        payload.skipBytes(23);

        // Username (null-terminated)
        String username = readNullTermString(payload);

        // Auth response
        if ((clientCapabilities & MySQLConstants.CLIENT_SECURE_CONNECTION) != 0) {
            int authLen = payload.readByte() & 0xFF;
            if (authLen > 0) {
                payload.skipBytes(authLen);
            }
        }

        // Database (null-terminated, optional)
        String database = null;
        if ((clientCapabilities & MySQLConstants.CLIENT_CONNECT_WITH_DB) != 0
                && payload.readableBytes() > 0) {
            database = readNullTermString(payload);
        }

        log.info("Client connected: user={}, database={}", username, database);

        // Accept all connections for now (no password verification).
        authenticated = true;

        // Enforce max connections
        if (maxConnections > 0 && sessionManager instanceof io.github.xinfra.lab.xdb.session.SessionManagerImpl impl
                && impl.activeCount() >= maxConnections) {
            log.warn("Connection rejected: max connections ({}) reached", maxConnections);
            ByteBuf err = MySQLPacketWriter.writeERR(ctx.alloc(), 1040, "08004",
                    "Too many connections");
            ctx.writeAndFlush(err).addListener(f -> ctx.close());
            return;
        }

        // Create a session and attach it to the channel.
        Session session = sessionManager.createSession();
        if (database != null && !database.isEmpty()) {
            session.useDatabase(database);
        }
        ctx.channel().attr(CommandHandler.SESSION_KEY).set(session);

        // Send OK to complete authentication.
        MySQLPacketEncoder encoder = ctx.pipeline().get(MySQLPacketEncoder.class);
        if (encoder != null) {
            encoder.setSequenceId(2); // handshake was seq 0, client response was seq 1
        }

        ByteBuf ok = MySQLPacketWriter.writeOK(ctx.alloc(), 0, 0,
                MySQLConstants.SERVER_STATUS_AUTOCOMMIT, 0);
        ctx.writeAndFlush(ok);

        // Swap this handler for the command handler (on a separate thread pool to avoid blocking EventLoop).
        if (queryExecutorGroup != null) {
            ctx.pipeline().addLast(queryExecutorGroup, "commandHandler", new CommandHandler());
        } else {
            ctx.pipeline().addLast("commandHandler", new CommandHandler());
        }
        ctx.pipeline().remove(this);
    }

    // -----------------------------------------------------------------------
    //  Helpers
    // -----------------------------------------------------------------------

    private static String readNullTermString(ByteBuf buf) {
        int start = buf.readerIndex();
        while (buf.readableBytes() > 0 && buf.readByte() != 0x00) {
            // advance
        }
        int end = buf.readerIndex() - 1; // exclude the NUL byte
        int length = end - start;
        if (length <= 0) {
            return "";
        }
        byte[] bytes = new byte[length];
        buf.getBytes(start, bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
