package com.multiplayer.server.network;

import com.multiplayer.server.db.DatabaseManager;
import com.multiplayer.server.db.UserRepository;
import com.multiplayer.server.lobby.LobbyManager;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Main entry point for the multiplayer game server.
 * Bootstraps a Netty WebSocket server on a configurable port (default 8080)
 * and registers a JVM shutdown hook for graceful shutdown.
 */
public final class GameServer {

    private static final Logger logger = LoggerFactory.getLogger(GameServer.class);
    private static final int DEFAULT_PORT = 8080;

    private final int port;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final DatabaseManager databaseManager;
    private final LobbyManager lobbyManager;
    private final UserRepository userRepository;

    public GameServer(int port) {
        this.port = port;
        this.bossGroup = new NioEventLoopGroup(1); // 1 thread – accepts connections
        this.workerGroup = new NioEventLoopGroup(); // default threads – handles I/O
        this.databaseManager = new DatabaseManager();
        this.lobbyManager = new LobbyManager();
        this.userRepository = new UserRepository(databaseManager);
    }

    /**
     * Starts the server, binds to the configured port, and blocks until the
     * server channel is closed.
     */
    public void start() throws InterruptedException {
        try {
            var bootstrap = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new WebSocketServerInitializer(lobbyManager, userRepository))
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childOption(ChannelOption.TCP_NODELAY, true);

            Channel serverChannel = bootstrap.bind(port).sync().channel();
            logger.info("Game server started on port {}", port);

            // Register JVM shutdown hook for graceful shutdown
            Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown, "shutdown-hook"));

            // Block until the server channel closes
            serverChannel.closeFuture().sync();
        } finally {
            shutdown();
        }
    }

    /**
     * Gracefully shuts down boss and worker event-loop groups.
     */
    public void shutdown() {
        logger.info("Shutting down game server...");
        bossGroup.shutdownGracefully();
        workerGroup.shutdownGracefully();
        databaseManager.close();
        logger.info("Game server shut down.");
    }

    public static void main(String[] args) throws InterruptedException {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : DEFAULT_PORT;
        new GameServer(port).start();
    }
}
