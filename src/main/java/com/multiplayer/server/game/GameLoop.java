package com.multiplayer.server.game;

import com.google.protobuf.ByteString;
import com.multiplayer.server.lobby.Lobby;
import com.multiplayer.server.proto.GameMessages.InputPacket;
import com.multiplayer.server.proto.GameMessages.Packet;
import com.multiplayer.server.proto.GameMessages.PlayerSnapshot;
import com.multiplayer.server.proto.GameMessages.WorldStateSnapshot;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Authoritative server game loop running at a fixed <b>60 Hz</b> tick rate.
 * <p>
 * Designed to run on a <b>single dedicated thread per lobby</b>
 * (as mandated by the architecture rules), so the internal
 * {@link #playerStates} map uses {@link ConcurrentHashMap} only because
 * players are registered/removed from Netty event-loop threads, while
 * reads happen on this loop's thread.
 *
 * <h2>Tick Phases</h2>
 * <ol>
 * <li><b>Process inputs</b> — drain the lock-free input queue.</li>
 * <li><b>Update world</b> — apply inputs to authoritative player
 * positions.</li>
 * <li><b>Broadcast state</b> — build a {@link WorldStateSnapshot} and send it
 * to every player in the lobby.</li>
 * </ol>
 *
 * <h2>Timing</h2>
 * Uses {@code System.nanoTime()} with a fixed-step loop and adaptive sleep
 * to maintain a stable 60 ticks/second regardless of processing time.
 */
public final class GameLoop implements Runnable {

    private static final Logger logger = LoggerFactory.getLogger(GameLoop.class);

    /** Target tick rate (Hz). */
    private static final int TICK_RATE = 60;
    /** Ideal duration per tick in nanoseconds (≈ 16.666 ms). */
    private static final long TICK_NS = 1_000_000_000L / TICK_RATE;
    /** One millisecond in nanoseconds — used for sleep conversion. */
    private static final long MS_IN_NS = 1_000_000L;

    private final Lobby lobby;

    /** Authoritative positions for every player in this lobby. */
    private final Map<String, PlayerState> playerStates = new ConcurrentHashMap<>();

    /**
     * Lock-free MPSC queue — Netty threads produce, game-loop thread consumes.
     * The record wraps the player ID with the raw input so the game loop
     * can look up the correct {@link PlayerState}.
     */
    private final ConcurrentLinkedQueue<TimestampedInput> inputQueue = new ConcurrentLinkedQueue<>();

    private volatile boolean running;
    private long tickCount;

    // ── Input wrapper ───────────────────────────────────────────────────

    /**
     * Pairs a player's channel ID with the decoded {@link InputPacket}.
     */
    public record TimestampedInput(String playerId, InputPacket input) {
    }

    // ── Constructor ─────────────────────────────────────────────────────

    public GameLoop(Lobby lobby) {
        this.lobby = lobby;
    }

    // ── Public API (called from Netty threads) ──────────────────────────

    /**
     * Enqueues an input packet for processing on the next tick.
     * Thread-safe — may be called from any Netty event-loop thread.
     */
    public void enqueueInput(String playerId, InputPacket input) {
        inputQueue.add(new TimestampedInput(playerId, input));
    }

    /**
     * Registers a player so the game loop tracks their state.
     */
    public void addPlayer(String playerId) {
        playerStates.putIfAbsent(playerId, new PlayerState(playerId));
        logger.info("GameLoop [{}] player registered: {}", lobby.getLobbyId(), playerId);
    }

    /**
     * Removes a player's state from the simulation.
     */
    public void removePlayer(String playerId) {
        playerStates.remove(playerId);
        logger.info("GameLoop [{}] player removed: {}", lobby.getLobbyId(), playerId);
    }

    /**
     * Signals the loop to stop after the current tick completes.
     */
    public void stop() {
        running = false;
    }

    public boolean isRunning() {
        return running;
    }

    // ── Main loop ───────────────────────────────────────────────────────

    @Override
    public void run() {
        running = true;
        tickCount = 0;
        logger.info("GameLoop [{}] started at {} Hz", lobby.getLobbyId(), TICK_RATE);

        long previousTime = System.nanoTime();

        while (running) {
            long now = System.nanoTime();
            long elapsed = now - previousTime;

            if (elapsed >= TICK_NS) {
                previousTime += TICK_NS; // fixed step — avoids drift
                tick();
            } else {
                // Sleep for the remaining time minus a small margin for scheduling jitter
                long sleepNanos = TICK_NS - elapsed;
                long sleepMs = sleepNanos / MS_IN_NS;
                if (sleepMs > 1) {
                    try {
                        Thread.sleep(sleepMs - 1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        running = false;
                    }
                } else {
                    Thread.onSpinWait(); // brief busy-wait for sub-ms precision
                }
            }
        }

        logger.info("GameLoop [{}] stopped after {} ticks", lobby.getLobbyId(), tickCount);
    }

    // ── Tick phases ─────────────────────────────────────────────────────

    private void tick() {
        tickCount++;

        // Phase 1 — Process all queued inputs
        processInputs();

        // Phase 2 — Update world (positions already applied in processInputs)
        // Additional game logic (collision, physics) would go here.

        // Phase 3 — Build snapshot and broadcast
        broadcastWorldState();
    }

    /**
     * <b>Phase 1:</b> Drains every pending {@link InputPacket} from the queue
     * and applies it to the corresponding player's authoritative position.
     */
    private void processInputs() {
        TimestampedInput polled;
        while ((polled = inputQueue.poll()) != null) {
            PlayerState ps = playerStates.get(polled.playerId());
            if (ps != null) {
                InputPacket input = polled.input();
                ps.applyInput(input.getDx(), input.getDy());
            }
        }
    }

    /**
     * <b>Phase 3:</b> Builds a {@link WorldStateSnapshot} containing every
     * player's authoritative X/Y and broadcasts it as a
     * {@link BinaryWebSocketFrame} to all connected players in the lobby.
     */
    private void broadcastWorldState() {
        // Build snapshot
        var snapshotBuilder = WorldStateSnapshot.newBuilder()
                .setTick(tickCount)
                .setTimestamp(System.currentTimeMillis());

        for (PlayerState ps : playerStates.values()) {
            snapshotBuilder.addPlayers(PlayerSnapshot.newBuilder()
                    .setPlayerId(ps.getPlayerId())
                    .setX(ps.getX())
                    .setY(ps.getY())
                    .build());
        }

        WorldStateSnapshot snapshot = snapshotBuilder.build();

        // Wrap in a Packet envelope
        byte[] packetBytes = Packet.newBuilder()
                .setType(Packet.Type.STATE_UPDATE)
                .setPayload(ByteString.copyFrom(snapshot.toByteArray()))
                .setTimestamp(snapshot.getTimestamp())
                .build()
                .toByteArray();

        // Broadcast to every player channel in the lobby
        List<ChannelHandlerContext> players = lobby.getPlayers();
        for (ChannelHandlerContext ctx : players) {
            if (ctx.channel().isActive()) {
                ctx.writeAndFlush(new BinaryWebSocketFrame(
                        Unpooled.copiedBuffer(packetBytes)));
            }
        }
    }
}
