package com.multiplayer.server.network;

import com.multiplayer.server.db.UserRepository;
import com.multiplayer.server.lobby.LobbyManager;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;

/**
 * Initializes the channel pipeline for incoming WebSocket connections
 * on the {@code /game} endpoint.
 */
public final class WebSocketChannelInitializer extends ChannelInitializer<SocketChannel> {

    private static final String WEBSOCKET_PATH = "/game";
    private static final int MAX_CONTENT_LENGTH = 65_536;

    private final LobbyManager lobbyManager;
    private final UserRepository userRepository;

    public WebSocketChannelInitializer(LobbyManager lobbyManager, UserRepository userRepository) {
        this.lobbyManager = lobbyManager;
        this.userRepository = userRepository;
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();

        // HTTP codec for the initial WebSocket upgrade handshake
        pipeline.addLast("httpCodec", new HttpServerCodec());

        // Aggregate HTTP messages into FullHttpRequests / FullHttpResponses
        pipeline.addLast("httpAggregator", new HttpObjectAggregator(MAX_CONTENT_LENGTH));

        // WebSocket protocol handler – manages handshake & control frames on /game
        pipeline.addLast("wsProtocol", new WebSocketServerProtocolHandler(WEBSOCKET_PATH, null, true));

        // Custom binary frame handler – parses Protobuf Packets and routes them
        pipeline.addLast("gameFrameHandler",
                new WebSocketFrameHandler(new MessageRouter(lobbyManager, userRepository)));
    }
}
