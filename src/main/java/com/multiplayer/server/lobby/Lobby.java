package com.multiplayer.server.lobby;

import com.multiplayer.server.game.GameLoop;
import com.multiplayer.server.proto.GameMessages.InputPacket;
import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manages a single game lobby with a thread-safe player list and a
 * Finite State Machine governing the lobby lifecycle.
 *
 * <h2>State Machine</h2>
 * 
 * <pre>
 *   WAITING ──▶ COUNTDOWN ──▶ PLAYING ──▶ ENDED
 *      ▲                                    │
 *      └────────────────────────────────────┘  (reset)
 * </pre>
 *
 * <h2>Thread Safety</h2>
 * <ul>
 * <li>Player list: {@link CopyOnWriteArrayList} — safe for concurrent
 * reads with infrequent writes (join/leave).</li>
 * <li>State: {@link AtomicReference} with CAS transitions — lock-free
 * and safe for cross-thread calls from Netty event-loops.</li>
 * </ul>
 */
public final class Lobby {

    private static final Logger logger = LoggerFactory.getLogger(Lobby.class);

    // ── FSM States ──────────────────────────────────────────────────────

    /**
     * Represents the possible states of a lobby.
     * Valid transitions are encoded in {@link #canTransitionTo(LobbyState)}.
     */
    public enum LobbyState {

        /** Waiting for players to join before starting the countdown. */
        WAITING {
            @Override
            public boolean canTransitionTo(LobbyState target) {
                return target == COUNTDOWN;
            }
        },

        /** Countdown has begun — game will start shortly. */
        COUNTDOWN {
            @Override
            public boolean canTransitionTo(LobbyState target) {
                return target == PLAYING || target == WAITING; // cancel back to WAITING if players leave
            }
        },

        /** Game is actively in progress. */
        PLAYING {
            @Override
            public boolean canTransitionTo(LobbyState target) {
                return target == ENDED;
            }
        },

        /** Game has ended — lobby can be reset back to WAITING. */
        ENDED {
            @Override
            public boolean canTransitionTo(LobbyState target) {
                return target == WAITING;
            }
        };

        /**
         * Returns {@code true} if transitioning from {@code this} to
         * {@code target} is a valid FSM move.
         */
        public abstract boolean canTransitionTo(LobbyState target);
    }

    // ── Result record for state transitions ─────────────────────────────

    /**
     * Describes the outcome of a state-transition attempt.
     *
     * @param success  whether the transition was applied
     * @param previous the state before the attempt
     * @param current  the state after the attempt (same as previous on failure)
     * @param message  human-readable detail
     */
    public record TransitionResult(boolean success, LobbyState previous, LobbyState current, String message) {
    }

    // ── Fields ──────────────────────────────────────────────────────────

    private final String lobbyId;
    private final int maxPlayers;
    private final int minPlayersToStart;

    /**
     * Thread-safe player list — optimised for frequent iteration, infrequent
     * mutation.
     */
    private final CopyOnWriteArrayList<ChannelHandlerContext> players = new CopyOnWriteArrayList<>();

    /** Lock-free FSM state. */
    private final AtomicReference<LobbyState> state = new AtomicReference<>(LobbyState.WAITING);

    /**
     * Dedicated single-thread executor for the game loop (one thread per lobby).
     */
    private volatile ExecutorService gameLoopExecutor;

    /** The game loop instance — {@code null} until the lobby enters PLAYING. */
    private volatile GameLoop gameLoop;

    // ── Constructor ─────────────────────────────────────────────────────

    public Lobby(String lobbyId, int maxPlayers, int minPlayersToStart) {
        this.lobbyId = lobbyId;
        this.maxPlayers = maxPlayers;
        this.minPlayersToStart = minPlayersToStart;
        logger.info("Lobby [{}] created (max={}, minToStart={})", lobbyId, maxPlayers, minPlayersToStart);
    }

    public Lobby(String lobbyId, int maxPlayers) {
        this(lobbyId, maxPlayers, 2);
    }

    // ── Player management ───────────────────────────────────────────────

    /**
     * Adds a player to the lobby if the current state allows it and the
     * lobby is not full.
     *
     * @return {@code true} if the player was added successfully
     */
    public boolean addPlayer(ChannelHandlerContext ctx) {
        LobbyState current = state.get();

        boolean canJoin = switch (current) {
            case WAITING, COUNTDOWN -> true;
            case PLAYING, ENDED -> false;
        };

        if (!canJoin) {
            logger.warn("Lobby [{}] rejected player {} — state is {}",
                    lobbyId, remoteAddress(ctx), current);
            return false;
        }

        if (players.size() >= maxPlayers) {
            logger.warn("Lobby [{}] is full ({}/{})", lobbyId, players.size(), maxPlayers);
            return false;
        }

        if (players.addIfAbsent(ctx)) {
            logger.info("Lobby [{}] player joined: {} ({}/{})",
                    lobbyId, remoteAddress(ctx), players.size(), maxPlayers);
            onPlayerCountChanged();
            return true;
        }
        return false; // already present
    }

    /**
     * Removes a player from the lobby.
     *
     * @return {@code true} if the player was present and removed
     */
    public boolean removePlayer(ChannelHandlerContext ctx) {
        if (players.remove(ctx)) {
            logger.info("Lobby [{}] player left: {} ({}/{})",
                    lobbyId, remoteAddress(ctx), players.size(), maxPlayers);
            onPlayerCountChanged();
            return true;
        }
        return false;
    }

    /**
     * Returns an unmodifiable snapshot of the current player list.
     */
    public List<ChannelHandlerContext> getPlayers() {
        return Collections.unmodifiableList(players);
    }

    public int getPlayerCount() {
        return players.size();
    }

    // ── FSM transitions ─────────────────────────────────────────────────

    /**
     * Attempts a CAS state transition from {@code expected} to {@code target}.
     *
     * @return a {@link TransitionResult} describing the outcome
     */
    public TransitionResult transition(LobbyState expected, LobbyState target) {
        if (!expected.canTransitionTo(target)) {
            return new TransitionResult(false, expected, expected,
                    "Invalid transition: %s → %s".formatted(expected, target));
        }

        if (state.compareAndSet(expected, target)) {
            logger.info("Lobby [{}] state transition: {} → {}", lobbyId, expected, target);
            onStateChanged(target);
            return new TransitionResult(true, expected, target,
                    "Transitioned: %s → %s".formatted(expected, target));
        }

        LobbyState actual = state.get();
        return new TransitionResult(false, actual, actual,
                "CAS failed — expected %s but was %s".formatted(expected, actual));
    }

    /**
     * Force-transitions the lobby to the given state <b>if</b> the FSM
     * rules allow it from the current state. Retries once on CAS failure.
     *
     * @return the transition result
     */
    public TransitionResult transitionTo(LobbyState target) {
        LobbyState current = state.get();
        TransitionResult result = transition(current, target);

        // One CAS retry in case of a race
        if (!result.success() && current != state.get()) {
            current = state.get();
            result = transition(current, target);
        }
        return result;
    }

    /**
     * Convenience: start the countdown (WAITING → COUNTDOWN).
     */
    public TransitionResult startCountdown() {
        return transition(LobbyState.WAITING, LobbyState.COUNTDOWN);
    }

    /**
     * Convenience: begin the game (COUNTDOWN → PLAYING).
     */
    public TransitionResult startGame() {
        return transition(LobbyState.COUNTDOWN, LobbyState.PLAYING);
    }

    /**
     * Convenience: end the game (PLAYING → ENDED).
     */
    public TransitionResult endGame() {
        return transition(LobbyState.PLAYING, LobbyState.ENDED);
    }

    /**
     * Convenience: reset to WAITING (ENDED → WAITING).
     */
    public TransitionResult reset() {
        return transition(LobbyState.ENDED, LobbyState.WAITING);
    }

    // ── Accessors ───────────────────────────────────────────────────────

    public String getLobbyId() {
        return lobbyId;
    }

    public LobbyState getState() {
        return state.get();
    }

    /**
     * Returns the active {@link GameLoop}, or empty if the lobby is not
     * in the {@code PLAYING} state.
     */
    public Optional<GameLoop> getGameLoop() {
        return Optional.ofNullable(gameLoop);
    }

    // ── Input bridge (called from Netty threads) ────────────────────────

    /**
     * Enqueues a player input for processing on the next game-loop tick.
     * <p>
     * Safe to call from any Netty event-loop thread — the input is placed
     * into the {@link GameLoop}'s lock-free {@code ConcurrentLinkedQueue}.
     *
     * @param playerId the channel short-ID of the sending player
     * @param input    the decoded {@link InputPacket}
     */
    public void enqueueInput(String playerId, InputPacket input) {
        GameLoop loop = this.gameLoop;
        if (loop != null && loop.isRunning()) {
            loop.enqueueInput(playerId, input);
        } else {
            logger.warn("Lobby [{}] input dropped — game loop not active", lobbyId);
        }
    }

    // ── Internal hooks ──────────────────────────────────────────────────

    /**
     * Called after a player joins or leaves.
     * Automatically starts the countdown when the minimum player count is
     * reached, or cancels it if players drop below the threshold.
     */
    private void onPlayerCountChanged() {
        LobbyState current = state.get();
        int count = players.size();

        switch (current) {
            case WAITING -> {
                if (count >= minPlayersToStart) {
                    startCountdown();
                }
            }
            case COUNTDOWN -> {
                if (count < minPlayersToStart) {
                    transition(LobbyState.COUNTDOWN, LobbyState.WAITING);
                    logger.info("Lobby [{}] countdown cancelled — not enough players", lobbyId);
                }
            }
            default -> {
                /* no automatic transitions during PLAYING / ENDED */ }
        }
    }

    /**
     * Hook invoked immediately after a successful state change.
     * Starts or stops the game loop as appropriate.
     */
    private void onStateChanged(LobbyState newState) {
        switch (newState) {
            case WAITING -> logger.debug("Lobby [{}] is now waiting for players", lobbyId);
            case COUNTDOWN -> logger.debug("Lobby [{}] countdown started", lobbyId);
            case PLAYING -> startGameLoop();
            case ENDED -> stopGameLoop();
        }
    }

    /**
     * Instantiates a {@link GameLoop}, registers all current players,
     * and submits it to a dedicated single-thread executor.
     * <p>
     * The executor is a single-thread pool so the entire game simulation
     * runs on one thread per lobby — no locking needed inside the loop
     * (per the architecture rules).
     */
    private void startGameLoop() {
        gameLoop = new GameLoop(this);

        // Register every player already in the lobby
        for (ChannelHandlerContext ctx : players) {
            gameLoop.addPlayer(ctx.channel().id().asShortText());
        }

        gameLoopExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "game-loop-" + lobbyId);
            t.setDaemon(true);
            return t;
        });
        gameLoopExecutor.submit(gameLoop);

        logger.info("Lobby [{}] game loop started on dedicated thread", lobbyId);
    }

    /**
     * Signals the game loop to stop and shuts down the executor.
     */
    private void stopGameLoop() {
        GameLoop loop = this.gameLoop;
        if (loop != null) {
            loop.stop();
            this.gameLoop = null;
        }

        ExecutorService exec = this.gameLoopExecutor;
        if (exec != null && !exec.isShutdown()) {
            exec.shutdown();
            this.gameLoopExecutor = null;
        }

        logger.info("Lobby [{}] game loop stopped", lobbyId);
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static String remoteAddress(ChannelHandlerContext ctx) {
        return Optional.ofNullable(ctx.channel().remoteAddress())
                .map(Object::toString)
                .orElse("unknown");
    }

    @Override
    public String toString() {
        return "Lobby{id='%s', state=%s, players=%d/%d}"
                .formatted(lobbyId, state.get(), players.size(), maxPlayers);
    }
}
