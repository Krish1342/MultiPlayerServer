package com.multiplayer.server.network;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles inbound WebSocket frames after the handshake is complete.
 * <p>
 * Extends {@link SimpleChannelInboundHandler} so frames are automatically
 * released after processing, preventing {@code ByteBuf} leaks.
 */
public final class GameFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final Logger logger = LoggerFactory.getLogger(GameFrameHandler.class);

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete handshake) {
            logger.info("WebSocket handshake complete – remote={}, uri={}",
                    ctx.channel().remoteAddress(), handshake.requestUri());
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        switch (frame) {
            case BinaryWebSocketFrame binary -> handleBinaryFrame(ctx, binary);
            case TextWebSocketFrame text -> handleTextFrame(ctx, text);
            default -> {
                logger.warn("Unsupported frame type: {}", frame.getClass().getSimpleName());
                ctx.close();
            }
        }
    }

    private void handleBinaryFrame(ChannelHandlerContext ctx, BinaryWebSocketFrame frame) {
        // TODO: Deserialize Protobuf messages and dispatch to game logic
        logger.debug("Received binary frame ({} bytes) from {}",
                frame.content().readableBytes(), ctx.channel().remoteAddress());
    }

    private void handleTextFrame(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        // Text frames may be used for lightweight control messages (e.g., ping/debug)
        logger.debug("Received text frame from {}: {}", ctx.channel().remoteAddress(), frame.text());
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        logger.info("Client connected: {}", ctx.channel().remoteAddress());
        super.channelActive(ctx);
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        logger.info("Client disconnected: {}", ctx.channel().remoteAddress());
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Exception in channel {}: {}", ctx.channel().remoteAddress(), cause.getMessage(), cause);
        ctx.close();
    }
}
