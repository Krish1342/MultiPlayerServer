package com.multiplayer.server.network;

import com.google.protobuf.InvalidProtocolBufferException;
import com.multiplayer.server.db.UserRepository;
import com.multiplayer.server.lobby.Lobby;
import com.multiplayer.server.lobby.LobbyManager;
import com.multiplayer.server.lobby.Lobby.LobbyState;
import com.multiplayer.server.proto.GameMessages.CreateLobbyRequest;
import com.multiplayer.server.proto.GameMessages.CreateLobbyResponse;
import com.multiplayer.server.proto.GameMessages.InputPacket;
import com.multiplayer.server.proto.GameMessages.JoinLobbyRequest;
import com.multiplayer.server.proto.GameMessages.JoinLobbyResponse;
import com.multiplayer.server.proto.GameMessages.LeaveLobbyRequest;
import com.multiplayer.server.proto.GameMessages.LoginRequest;
import com.multiplayer.server.proto.GameMessages.LoginResponse;
import com.multiplayer.server.proto.GameMessages.Packet;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes decoded {@link Packet} messages to the appropriate application-layer
 * handler based on the packet's {@code Type} discriminator.
 * <p>
 * Lobby-related packets ({@code CREATE_LOBBY}, {@code JOIN_LOBBY},
 * {@code LEAVE_LOBBY}) are forwarded to the {@link LobbyManager}.
 * <p>
 * <b>Blocking operations</b> (e.g. {@code LOGIN} → DB query) are offloaded
 * to Java 21 Virtual Threads so the Netty event-loop is never blocked.
 */
public final class MessageRouter {

    private static final Logger logger = LoggerFactory.getLogger(MessageRouter.class);

    private final LobbyManager lobbyManager;
    private final UserRepository userRepository;
    /** channelId -> lobbyId mapping used for fast input routing. */
    private final java.util.concurrent.ConcurrentHashMap<String, String> channelLobbyMap = new java.util.concurrent.ConcurrentHashMap<>();

    public MessageRouter(LobbyManager lobbyManager, UserRepository userRepository) {
        this.lobbyManager = lobbyManager;
        this.userRepository = userRepository;
    }

    /**
     * Routes an incoming packet from the given channel context.
     *
     * @param ctx    the Netty channel context (used for responses and player
     *               tracking)
     * @param packet the decoded Protobuf packet
     */
    public void route(ChannelHandlerContext ctx, Packet packet) {
        String channelId = ctx.channel().id().asShortText();

        switch (packet.getType()) {
            case LOGIN -> handleLogin(ctx, channelId, packet);
            case CREATE_LOBBY -> handleCreateLobby(ctx, channelId, packet);
            case JOIN_LOBBY -> handleJoinLobby(ctx, channelId, packet);
            case LEAVE_LOBBY -> handleLeaveLobby(ctx, channelId, packet);
            case JOIN -> logger.info("[{}] JOIN received", channelId);
            case LEAVE -> logger.info("[{}] LEAVE received", channelId);
            case PLAYER_INPUT -> handlePlayerInput(ctx, channelId, packet);
            case STATE_UPDATE -> logger.debug("[{}] STATE_UPDATE received", channelId);
            case CHAT -> logger.info("[{}] CHAT received", channelId);
            default -> logger.warn("[{}] Unknown packet type: {}", channelId, packet.getType());
        }
    }

    // ── Auth handlers ───────────────────────────────────────────────────

    /**
     * Handles {@code LOGIN} packets by offloading the blocking
     * {@link UserRepository#authenticate} call to a <b>Java 21 Virtual Thread</b>.
     * <p>
     * Once the DB query completes, the response is written back through
     * the Netty channel (thread-safe — Netty serialises writes on the
     * channel's event-loop automatically).
     */
    private void handleLogin(ChannelHandlerContext ctx, String channelId, Packet packet) {
        try {
            LoginRequest request = LoginRequest.parseFrom(packet.getPayload());

            logger.info("[{}] LOGIN request for user '{}'", channelId, request.getUsername());

            // Offload blocking DB call to a virtual thread
            Thread.startVirtualThread(() -> {
                try {
                    boolean authenticated = userRepository.authenticate(
                            request.getUsername(), request.getPassword());

                    LoginResponse response = LoginResponse.newBuilder()
                            .setSuccess(authenticated)
                            .setMessage(authenticated ? "Login successful" : "Invalid credentials")
                            .build();

                    sendResponse(ctx, Packet.Type.LOGIN, response.toByteArray());

                    logger.info("[{}] LOGIN result for '{}': {}",
                            channelId, request.getUsername(), authenticated ? "OK" : "FAILED");
                } catch (Exception e) {
                    logger.error("[{}] Error during login for '{}': {}",
                            channelId, request.getUsername(), e.getMessage(), e);
                    sendResponse(ctx, Packet.Type.LOGIN, LoginResponse.newBuilder()
                            .setSuccess(false)
                            .setMessage("Internal server error")
                            .build()
                            .toByteArray());
                }
            });

        } catch (InvalidProtocolBufferException e) {
            logger.error("[{}] Malformed LOGIN payload: {}", channelId, e.getMessage());
            sendResponse(ctx, Packet.Type.LOGIN, LoginResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Invalid request payload")
                    .build()
                    .toByteArray());
        }
    }

    // ── Lobby handlers ──────────────────────────────────────────────────

    private void handleCreateLobby(ChannelHandlerContext ctx, String channelId, Packet packet) {
        try {
            CreateLobbyRequest request = CreateLobbyRequest.parseFrom(packet.getPayload());

            logger.info("[{}] CREATE_LOBBY request: name='{}', maxPlayers={}",
                    channelId, request.getLobbyName(), request.getMaxPlayers());

            Lobby lobby = lobbyManager.createLobby(request.getLobbyName(), request.getMaxPlayers());

            // Auto-join the creator
            lobby.addPlayer(ctx);
            channelLobbyMap.put(channelId, lobby.getLobbyId());

            sendResponse(ctx, Packet.Type.CREATE_LOBBY, CreateLobbyResponse.newBuilder()
                    .setSuccess(true)
                    .setLobbyId(lobby.getLobbyId())
                    .setMessage("Lobby created")
                    .build()
                    .toByteArray());

        } catch (InvalidProtocolBufferException e) {
            logger.error("[{}] Malformed CREATE_LOBBY payload: {}", channelId, e.getMessage());
            sendResponse(ctx, Packet.Type.CREATE_LOBBY, CreateLobbyResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Invalid request payload")
                    .build()
                    .toByteArray());
        }
    }

    private void handleJoinLobby(ChannelHandlerContext ctx, String channelId, Packet packet) {
        try {
            JoinLobbyRequest request = JoinLobbyRequest.parseFrom(packet.getPayload());
            String lobbyId = request.getLobbyId();

            logger.info("[{}] JOIN_LOBBY request: lobbyId='{}'", channelId, lobbyId);

            boolean joined = lobbyManager.joinLobby(lobbyId, ctx);

            var responseBuilder = JoinLobbyResponse.newBuilder()
                    .setSuccess(joined)
                    .setLobbyId(lobbyId);

            if (joined) {
                channelLobbyMap.put(channelId, lobbyId);
                int count = lobbyManager.getLobby(lobbyId)
                        .map(Lobby::getPlayerCount)
                        .orElse(0);
                responseBuilder.setPlayerCount(count).setMessage("Joined lobby");
            } else {
                responseBuilder.setMessage("Failed to join lobby — full or does not exist");
            }

            sendResponse(ctx, Packet.Type.JOIN_LOBBY, responseBuilder.build().toByteArray());

        } catch (InvalidProtocolBufferException e) {
            logger.error("[{}] Malformed JOIN_LOBBY payload: {}", channelId, e.getMessage());
            sendResponse(ctx, Packet.Type.JOIN_LOBBY, JoinLobbyResponse.newBuilder()
                    .setSuccess(false)
                    .setMessage("Invalid request payload")
                    .build()
                    .toByteArray());
        }
    }

    private void handleLeaveLobby(ChannelHandlerContext ctx, String channelId, Packet packet) {
        try {
            LeaveLobbyRequest request = LeaveLobbyRequest.parseFrom(packet.getPayload());
            String lobbyId = request.getLobbyId();

            logger.info("[{}] LEAVE_LOBBY request: lobbyId='{}'", channelId, lobbyId);

            boolean left = lobbyManager.leaveLobby(lobbyId, ctx);
            if (left) {
                channelLobbyMap.remove(channelId, lobbyId);
            }

            if (!left) {
                logger.warn("[{}] Leave failed — not in lobby {}", channelId, lobbyId);
            }
        } catch (InvalidProtocolBufferException e) {
            logger.error("[{}] Malformed LEAVE_LOBBY payload: {}", channelId, e.getMessage());
        }
    }

    private void handlePlayerInput(ChannelHandlerContext ctx, String channelId, Packet packet) {
        try {
            InputPacket input = InputPacket.parseFrom(packet.getPayload());
            String lobbyId = channelLobbyMap.get(channelId);
            if (lobbyId == null) {
                logger.warn("[{}] PLAYER_INPUT dropped — client is not in a lobby", channelId);
                return;
            }

            lobbyManager.getLobby(lobbyId).ifPresentOrElse(lobby -> {
                if (lobby.getState() == LobbyState.COUNTDOWN) {
                    // Start simulation as soon as gameplay input arrives.
                    lobby.startGame();
                }
                lobby.enqueueInput(channelId, input);
            }, () -> {
                channelLobbyMap.remove(channelId, lobbyId);
                logger.warn("[{}] PLAYER_INPUT dropped — lobby {} no longer exists", channelId, lobbyId);
            });

        } catch (InvalidProtocolBufferException e) {
            logger.error("[{}] Malformed PLAYER_INPUT payload: {}", channelId, e.getMessage());
        }
    }

    /**
     * Clears channel-specific membership state after disconnect.
     */
    public void onDisconnect(ChannelHandlerContext ctx) {
        String channelId = ctx.channel().id().asShortText();
        String lobbyId = channelLobbyMap.remove(channelId);
        if (lobbyId != null) {
            lobbyManager.leaveLobby(lobbyId, ctx);
        } else {
            lobbyManager.removePlayerFromAll(ctx);
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    /**
     * Wraps a response payload in a {@link Packet} and writes it back to the client
     * as a {@link BinaryWebSocketFrame}.
     */
    private void sendResponse(ChannelHandlerContext ctx, Packet.Type type, byte[] payload) {
        Packet response = Packet.newBuilder()
                .setType(type)
                .setPayload(com.google.protobuf.ByteString.copyFrom(payload))
                .setTimestamp(System.currentTimeMillis())
                .build();

        byte[] responseBytes = response.toByteArray();
        ctx.writeAndFlush(new BinaryWebSocketFrame(
                Unpooled.wrappedBuffer(responseBytes)));
    }
}
