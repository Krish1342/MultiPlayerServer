package com.multiplayer.server.demo;

import com.google.protobuf.ByteString;
import com.multiplayer.server.proto.GameMessages.CreateLobbyRequest;
import com.multiplayer.server.proto.GameMessages.CreateLobbyResponse;
import com.multiplayer.server.proto.GameMessages.InputPacket;
import com.multiplayer.server.proto.GameMessages.JoinLobbyRequest;
import com.multiplayer.server.proto.GameMessages.JoinLobbyResponse;
import java.io.ByteArrayOutputStream;
import com.multiplayer.server.proto.GameMessages.Packet;
import com.multiplayer.server.proto.GameMessages.WorldStateSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Lightweight websocket bot for demoing the authoritative server loop.
 */
public final class DemoBotClient implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(DemoBotClient.class);

    private final String botName;
    private final URI serverUri;
    private final HttpClient httpClient;
    private final AtomicLong sequence = new AtomicLong(0);

    private volatile WebSocket webSocket;
    private volatile CompletableFuture<String> pendingCreateLobby;
    private volatile CompletableFuture<Boolean> pendingJoinLobby;
    private volatile ScheduledExecutorService inputExecutor;

    public DemoBotClient(String botName, URI serverUri) {
        this.botName = Objects.requireNonNull(botName, "botName");
        this.serverUri = Objects.requireNonNull(serverUri, "serverUri");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public CompletableFuture<Void> connect() {
        var listener = new BotListener();
        return httpClient.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .buildAsync(serverUri, listener)
                .thenAccept(ws -> {
                    this.webSocket = ws;
                    logger.info("{} connected to {}", botName, serverUri);
                });
    }

    public CompletableFuture<String> createLobby(String lobbyName, int maxPlayers) {
        var request = CreateLobbyRequest.newBuilder()
                .setLobbyName(lobbyName)
                .setMaxPlayers(maxPlayers)
                .build();

        CompletableFuture<String> future = new CompletableFuture<>();
        this.pendingCreateLobby = future;

        sendPacket(Packet.Type.CREATE_LOBBY, request.toByteArray());
        return future.orTimeout(10, TimeUnit.SECONDS);
    }

    public CompletableFuture<Boolean> joinLobby(String lobbyId) {
        var request = JoinLobbyRequest.newBuilder()
                .setLobbyId(lobbyId)
                .build();

        CompletableFuture<Boolean> future = new CompletableFuture<>();
        this.pendingJoinLobby = future;

        sendPacket(Packet.Type.JOIN_LOBBY, request.toByteArray());
        return future.orTimeout(10, TimeUnit.SECONDS);
    }

    public void startSendingInput() {
        if (inputExecutor != null && !inputExecutor.isShutdown()) {
            return;
        }

        inputExecutor = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual()
                .name("bot-input-" + botName, 0)
                .factory());

        inputExecutor.scheduleAtFixedRate(() -> {
            try {
                float angle = (float) (System.nanoTime() / 1_000_000_000.0 + botName.hashCode());
                float jitterX = ThreadLocalRandom.current().nextFloat(-0.25f, 0.25f);
                float jitterY = ThreadLocalRandom.current().nextFloat(-0.25f, 0.25f);
                float dx = (float) Math.max(-1.0, Math.min(1.0, Math.cos(angle) + jitterX));
                float dy = (float) Math.max(-1.0, Math.min(1.0, Math.sin(angle) + jitterY));

                InputPacket input = InputPacket.newBuilder()
                        .setPlayerId(botName)
                        .setDx(dx)
                        .setDy(dy)
                        .setSequence(sequence.incrementAndGet())
                        .build();

                sendPacket(Packet.Type.PLAYER_INPUT, input.toByteArray());
            } catch (Exception e) {
                logger.warn("{} failed to send input: {}", botName, e.getMessage());
            }
        }, 100, 100, TimeUnit.MILLISECONDS);
    }

    public void stopSendingInput() {
        ScheduledExecutorService executor = this.inputExecutor;
        if (executor != null) {
            executor.shutdownNow();
            this.inputExecutor = null;
        }
    }

    private void sendPacket(Packet.Type type, byte[] payload) {
        WebSocket ws = this.webSocket;
        if (ws == null) {
            throw new IllegalStateException("WebSocket is not connected");
        }

        Packet packet = Packet.newBuilder()
                .setType(type)
                .setPayload(ByteString.copyFrom(payload))
                .setTimestamp(System.currentTimeMillis())
                .build();

        ws.sendBinary(ByteBuffer.wrap(packet.toByteArray()), true);
    }

    private void handleBinary(ByteBuffer data) {
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);

        try {
            Packet packet = Packet.parseFrom(bytes);
            switch (packet.getType()) {
                case CREATE_LOBBY -> {
                    CreateLobbyResponse response = CreateLobbyResponse.parseFrom(packet.getPayload());
                    CompletableFuture<String> future = pendingCreateLobby;
                    if (future != null) {
                        if (response.getSuccess()) {
                            future.complete(response.getLobbyId());
                        } else {
                            future.completeExceptionally(
                                    new IllegalStateException("Create lobby failed: " + response.getMessage()));
                        }
                    }
                    logger.info("{} create-lobby response: success={}, lobbyId={}, msg={}",
                            botName, response.getSuccess(), response.getLobbyId(), response.getMessage());
                }
                case JOIN_LOBBY -> {
                    JoinLobbyResponse response = JoinLobbyResponse.parseFrom(packet.getPayload());
                    CompletableFuture<Boolean> future = pendingJoinLobby;
                    if (future != null) {
                        future.complete(response.getSuccess());
                    }
                    logger.info("{} join-lobby response: success={}, count={}, msg={}",
                            botName, response.getSuccess(), response.getPlayerCount(), response.getMessage());
                }
                case STATE_UPDATE -> {
                    WorldStateSnapshot snapshot = WorldStateSnapshot.parseFrom(packet.getPayload());
                    if (snapshot.getTick() % 60 == 0) {
                        logger.info("{} tick={} players={}",
                                botName, snapshot.getTick(), snapshot.getPlayersCount());
                    }
                }
                default -> logger.debug("{} received packet type {}", botName, packet.getType());
            }
        } catch (Exception e) {
            logger.warn("{} failed to decode packet: {}", botName, e.getMessage());
        }
    }

    @Override
    public void close() {
        stopSendingInput();
        WebSocket ws = this.webSocket;
        if (ws != null) {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "demo-complete");
            this.webSocket = null;
        }
    }

    private final class BotListener implements WebSocket.Listener {

        private final ByteArrayOutputStream fragmentBuffer = new ByteArrayOutputStream();

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
            WebSocket.Listener.super.onOpen(webSocket);
        }

        @Override
        public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
            byte[] chunk = new byte[data.remaining()];
            data.get(chunk);
            fragmentBuffer.writeBytes(chunk);

            if (last) {
                handleBinary(ByteBuffer.wrap(fragmentBuffer.toByteArray()));
                fragmentBuffer.reset();
            }
            webSocket.request(1);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            logger.info("{} disconnected: status={}, reason={}", botName, statusCode, reason);
            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            logger.error("{} websocket error: {}", botName, error.getMessage(), error);
        }
    }
}
