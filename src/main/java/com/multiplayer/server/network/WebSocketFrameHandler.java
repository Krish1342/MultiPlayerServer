package com.multiplayer.server.network;

import com.google.protobuf.InvalidProtocolBufferException;
import com.multiplayer.server.proto.GameMessages.Packet;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles inbound {@link WebSocketFrame}s, specifically
 * {@link BinaryWebSocketFrame}.
 * <p>
 * Extends {@link SimpleChannelInboundHandler} which <b>automatically
 * releases</b>
 * the incoming frame (and its underlying {@link ByteBuf}) after
 * {@link #channelRead0} returns, preventing memory leaks.
 * <p>
 * The binary payload is expected to be a Protobuf-encoded {@link Packet}.
 * After parsing, the packet is forwarded to a {@link MessageRouter} for
 * dispatch.
 */
public final class WebSocketFrameHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

    private static final Logger logger = LoggerFactory.getLogger(WebSocketFrameHandler.class);

    private final MessageRouter messageRouter;

    public WebSocketFrameHandler(MessageRouter messageRouter) {
        // Pass true (default) to SimpleChannelInboundHandler so it auto-releases frames
        super(true);
        this.messageRouter = messageRouter;
    }

    /**
     * Called for every inbound {@link WebSocketFrame}.
     * Only {@link BinaryWebSocketFrame} is supported — anything else is rejected.
     * <p>
     * The frame's {@link ByteBuf} is <b>not</b> manually released here because
     * {@link SimpleChannelInboundHandler} handles that automatically after this
     * method completes (even on exceptions), ensuring leak-free operation.
     */
    @Override
    protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (!(frame instanceof BinaryWebSocketFrame binaryFrame)) {
            logger.warn("Unsupported frame type from {}: {} — closing channel",
                    ctx.channel().remoteAddress(), frame.getClass().getSimpleName());
            ctx.close();
            return;
        }

        ByteBuf content = binaryFrame.content();

        try {
            // Read bytes from the ByteBuf without mutating the reader index more than
            // needed
            byte[] bytes = new byte[content.readableBytes()];
            content.readBytes(bytes);

            Packet packet = Packet.parseFrom(bytes);

            logger.debug("Decoded Packet [type={}, payloadSize={}, timestamp={}] from {}",
                    packet.getType(), packet.getPayload().size(), packet.getTimestamp(),
                    ctx.channel().remoteAddress());

            messageRouter.route(ctx, packet);

        } catch (InvalidProtocolBufferException e) {
            logger.error("Failed to parse Protobuf Packet from {}: {}",
                    ctx.channel().remoteAddress(), e.getMessage());
            ctx.close();
        }
        // ByteBuf release is handled by SimpleChannelInboundHandler — no finally block
        // needed
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        logger.error("Unhandled exception on channel {}: {}",
                ctx.channel().remoteAddress(), cause.getMessage(), cause);
        ctx.close();
    }
}
