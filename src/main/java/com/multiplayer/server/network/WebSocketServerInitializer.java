package com.multiplayer.server.network;

import com.multiplayer.server.db.UserRepository;
import com.multiplayer.server.lobby.LobbyManager;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.codec.http.websocketx.extensions.compression.WebSocketServerCompressionHandler;
import io.netty.handler.stream.ChunkedWriteHandler;

/**
 * Sets up the Netty channel pipeline for WebSocket connections.
 * <p>
 * Pipeline order:
 * 1. HTTP codec – encodes/decodes HTTP for the initial upgrade handshake.
 * 2. HTTP aggregator – aggregates HTTP messages into full requests.
 * 3. Chunked writer – supports streaming large payloads.
 * 4. WebSocket compression – per-message deflate extension.
 * 5. WebSocket protocol handler – manages the WS handshake and control frames.
 * 6. GameFrameHandler – application-level processing of WebSocket frames.
 */
public final class WebSocketServerInitializer extends ChannelInitializer<SocketChannel> {

    private static final String WEBSOCKET_PATH = "/game";
    private static final int MAX_CONTENT_LENGTH = 65_536;

    private final MessageRouter messageRouter;

    public WebSocketServerInitializer(LobbyManager lobbyManager, UserRepository userRepository) {
        this.messageRouter = new MessageRouter(lobbyManager, userRepository);
    }

    @Override
    protected void initChannel(SocketChannel ch) {
        ChannelPipeline pipeline = ch.pipeline();

        // HTTP codec for the WebSocket upgrade handshake
        pipeline.addLast(new HttpServerCodec());
        pipeline.addLast(new HttpObjectAggregator(MAX_CONTENT_LENGTH));
        pipeline.addLast(new ChunkedWriteHandler());

        // WebSocket compression & protocol handling
        pipeline.addLast(new WebSocketServerCompressionHandler());
        pipeline.addLast(new WebSocketServerProtocolHandler(WEBSOCKET_PATH, null, true));

        // Application-level frame handler for protobuf packets
        pipeline.addLast(new WebSocketFrameHandler(messageRouter));
    }
}
