package com.multiplayer.server.lobby;

import io.netty.channel.ChannelHandlerContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Central registry for all active {@link Lobby} instances.
 * <p>
 * Thread-safe: backed by a {@link ConcurrentHashMap} so lobbies can be
 * created, looked up, and removed from any Netty event-loop thread
 * without external synchronisation.
 */
public final class LobbyManager {

    private static final Logger logger = LoggerFactory.getLogger(LobbyManager.class);

    private static final int DEFAULT_MAX_PLAYERS = 8;
    private static final int DEFAULT_MIN_PLAYERS_START = 2;
    private static final String DEMO_LOBBY_NAME = "demo";
    private static final String DEMO_LOBBY_ID = "demo0001";

    /** lobbyId → Lobby */
    private final ConcurrentMap<String, Lobby> lobbies = new ConcurrentHashMap<>();
    /** lobbyName(lowercase) -> lobbyId */
    private final ConcurrentMap<String, String> lobbyNameToId = new ConcurrentHashMap<>();
    /** lobbyId -> lobbyName(lowercase) */
    private final ConcurrentMap<String, String> lobbyIdToName = new ConcurrentHashMap<>();

    public record JoinLobbyResult(boolean success, String message, int playerCount) {
    }

    public LobbyManager() {
        // Keep a stable lobby available for demos so frontend users can always join.
        createLobby(DEMO_LOBBY_NAME, DEFAULT_MAX_PLAYERS);
    }

    // ── Lobby lifecycle ─────────────────────────────────────────────────

    /**
     * Creates a new lobby and registers it.
     *
     * @param lobbyName  a human-readable name (used for logging; the key is a UUID)
     * @param maxPlayers maximum number of players allowed
     * @return the newly created {@link Lobby}
     */
    public Lobby createLobby(String lobbyName, int maxPlayers) {
        String normalizedName = normalizeLobbyName(lobbyName);
        int max = maxPlayers > 0 ? maxPlayers : DEFAULT_MAX_PLAYERS;

        if (DEMO_LOBBY_NAME.equals(normalizedName)) {
            Lobby existingDemo = lobbies.get(DEMO_LOBBY_ID);
            if (existingDemo != null) {
                logger.info("Returning existing demo lobby: id={}", DEMO_LOBBY_ID);
                return existingDemo;
            }

            var demoLobby = new Lobby(DEMO_LOBBY_ID, max, DEFAULT_MIN_PLAYERS_START);
            Lobby previous = lobbies.putIfAbsent(DEMO_LOBBY_ID, demoLobby);
            Lobby selected = previous != null ? previous : demoLobby;
            lobbyNameToId.put(DEMO_LOBBY_NAME, DEMO_LOBBY_ID);
            lobbyIdToName.put(DEMO_LOBBY_ID, DEMO_LOBBY_NAME);

            logger.info("Demo lobby ready: id={}, maxPlayers={}", DEMO_LOBBY_ID, max);
            return selected;
        }

        String lobbyId = generateLobbyId();
        var lobby = new Lobby(lobbyId, max, DEFAULT_MIN_PLAYERS_START);
        lobbies.put(lobbyId, lobby);
        lobbyNameToId.put(normalizedName, lobbyId);
        lobbyIdToName.put(lobbyId, normalizedName);

        logger.info("Lobby created: id={}, name='{}', maxPlayers={}", lobbyId, lobbyName, max);
        return lobby;
    }

    /**
     * Overload that uses default capacity.
     */
    public Lobby createLobby(String lobbyName) {
        return createLobby(lobbyName, DEFAULT_MAX_PLAYERS);
    }

    /**
     * Looks up a lobby by its identifier.
     */
    public Optional<Lobby> getLobby(String lobbyId) {
        return Optional.ofNullable(lobbies.get(lobbyId));
    }

    /**
     * Removes and returns the lobby if it exists.
     */
    public Optional<Lobby> removeLobby(String lobbyId) {
        if (DEMO_LOBBY_ID.equals(lobbyId)) {
            logger.info("Ignoring remove for persistent demo lobby: id={}", DEMO_LOBBY_ID);
            return Optional.ofNullable(lobbies.get(DEMO_LOBBY_ID));
        }

        Lobby removed = lobbies.remove(lobbyId);
        if (removed != null) {
            String name = lobbyIdToName.remove(lobbyId);
            if (name != null) {
                lobbyNameToId.remove(name, lobbyId);
            }
            logger.info("Lobby removed: id={}", lobbyId);
        }
        return Optional.ofNullable(removed);
    }

    /**
     * Returns an unmodifiable view of all active lobbies.
     */
    public Collection<Lobby> getAllLobbies() {
        return Collections.unmodifiableCollection(lobbies.values());
    }

    public int getLobbyCount() {
        return lobbies.size();
    }

    // ── Player operations (delegated to Lobby) ──────────────────────────

    /**
     * Adds a player to the specified lobby.
     *
     * @return {@code true} if the player was added successfully
     */
    public JoinLobbyResult joinLobby(String lobbyId, ChannelHandlerContext ctx) {
        Lobby lobby = lobbies.get(lobbyId);
        if (lobby == null) {
            logger.warn("Join failed — lobby {} does not exist", lobbyId);
            return new JoinLobbyResult(false, "Lobby does not exist", 0);
        }

        if (lobby.addPlayer(ctx)) {
            return new JoinLobbyResult(true, "Joined lobby", lobby.getPlayerCount());
        }

        if (lobby.getPlayers().contains(ctx)) {
            return new JoinLobbyResult(true, "Already in lobby", lobby.getPlayerCount());
        }

        if (lobby.getPlayerCount() >= lobby.getMaxPlayers()) {
            return new JoinLobbyResult(false, "Lobby is full", lobby.getPlayerCount());
        }

        return switch (lobby.getState()) {
            case ENDED -> new JoinLobbyResult(false, "Lobby has ended", lobby.getPlayerCount());
            case WAITING, COUNTDOWN, PLAYING ->
                new JoinLobbyResult(false, "Join failed due to concurrent state change, retry", lobby.getPlayerCount());
        };
    }

    /**
     * Removes a player from the specified lobby.
     * If the lobby becomes empty, it is automatically cleaned up.
     *
     * @return {@code true} if the player was removed
     */
    public boolean leaveLobby(String lobbyId, ChannelHandlerContext ctx) {
        return getLobby(lobbyId)
                .map(lobby -> {
                    boolean removed = lobby.removePlayer(ctx);
                    if (removed && lobby.getPlayerCount() == 0 && !DEMO_LOBBY_ID.equals(lobbyId)) {
                        removeLobby(lobbyId);
                        logger.info("Lobby {} auto-removed (empty)", lobbyId);
                    }
                    return removed;
                })
                .orElseGet(() -> {
                    logger.warn("Leave failed — lobby {} does not exist", lobbyId);
                    return false;
                });
    }

    /**
     * Removes a player from <b>all</b> lobbies they belong to.
     * Useful on disconnect to clean up stale references.
     */
    public void removePlayerFromAll(ChannelHandlerContext ctx) {
        lobbies.forEach((id, lobby) -> {
            if (lobby.removePlayer(ctx) && lobby.getPlayerCount() == 0 && !DEMO_LOBBY_ID.equals(id)) {
                removeLobby(id);
            }
        });
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private String generateLobbyId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    private String normalizeLobbyName(String lobbyName) {
        if (lobbyName == null) {
            return "lobby";
        }
        String trimmed = lobbyName.trim().toLowerCase();
        return trimmed.isEmpty() ? "lobby" : trimmed;
    }
}
