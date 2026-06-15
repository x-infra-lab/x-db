package io.github.xinfra.lab.xdb.server;

import io.github.xinfra.lab.xdb.session.SessionManager;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.util.concurrent.DefaultEventExecutorGroup;
import io.netty.util.concurrent.EventExecutorGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import javax.net.ssl.SSLException;

/**
 * MySQL-protocol-compatible server for x-db, built on Netty.
 * <p>
 * The pipeline per connection is:
 * <pre>
 *   MySQLPacketDecoder  (bytes  -> MySQLPacket)
 *   MySQLPacketEncoder  (ByteBuf -> bytes with 4-byte MySQL header)
 *   AuthenticationHandler  (handshake, then replaced by CommandHandler)
 * </pre>
 */
public class XDBServer {

    private static final Logger log = LoggerFactory.getLogger(XDBServer.class);

    private final XDBConfig config;
    private final SessionManager sessionManager;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private EventExecutorGroup queryExecutorGroup;
    private Channel serverChannel;
    private SslContext sslContext;

    public XDBServer(XDBConfig config, SessionManager sessionManager) {
        this.config = config;
        this.sessionManager = sessionManager;
    }

    /**
     * Start the server and bind to the configured port.
     * This method blocks until the server socket is bound.
     */
    public void start() throws Exception {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup(config.workerThreads());
        queryExecutorGroup = new DefaultEventExecutorGroup(config.workerThreads(),
                r -> { Thread t = new Thread(r); t.setName("query-exec-" + t.getId()); return t; });

        if (config.tlsEnabled()) {
            sslContext = SslContextBuilder.forServer(
                    new File(config.tlsCertFile()),
                    new File(config.tlsKeyFile())
            ).build();
            log.info("TLS enabled: cert={}, key={}", config.tlsCertFile(), config.tlsKeyFile());
        }

        ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline()
                                .addLast("decoder", new MySQLPacketDecoder())
                                .addLast("encoder", new MySQLPacketEncoder())
                                .addLast("auth", new AuthenticationHandler(
                                        sessionManager, config.maxConnections(),
                                        queryExecutorGroup, config.rootPassword(),
                                        sslContext));
                    }
                })
                .option(ChannelOption.SO_BACKLOG, 128)
                .childOption(ChannelOption.SO_KEEPALIVE, true)
                .childOption(ChannelOption.TCP_NODELAY, true);

        serverChannel = bootstrap.bind(config.port()).sync().channel();

        log.info("x-db server started on port {}", config.port());
    }

    /**
     * Gracefully shut down the server.
     */
    public void shutdown() {
        log.info("Shutting down x-db server...");
        if (serverChannel != null) {
            serverChannel.close();
        }
        if (queryExecutorGroup != null) {
            queryExecutorGroup.shutdownGracefully().syncUninterruptibly();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().syncUninterruptibly();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().syncUninterruptibly();
        }
        if (sessionManager != null) {
            sessionManager.close();
        }
        log.info("x-db server stopped.");
    }

    /**
     * Return the port the server is listening on.
     */
    public int port() {
        return config.port();
    }

    public Channel serverChannel() {
        return serverChannel;
    }

    /**
     * Standalone entry point.
     * <p>
     * Connects to x-kv, starts DDL worker, and serves MySQL-compatible queries.
     * <p>
     * Usage: {@code java --enable-preview -jar x-db-server.jar [port]}
     */
    public static void main(String[] args) {
        XDBConfig config = XDBConfig.load(args);
        try {
            XDBBootstrap.run(config);
        } catch (Exception e) {
            log.error("Failed to start x-db server", e);
            System.exit(1);
        }
    }
}
