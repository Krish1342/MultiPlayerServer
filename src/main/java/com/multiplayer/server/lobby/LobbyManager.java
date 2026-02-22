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

    /** lobbyId → Lobby */
    private final ConcurrentMap<String, Lobby> lobbies = new ConcurrentHashMap<>();

    // ── Lobby lifecycle ─────────────────────────────────────────────────

    /**
     * Creates a new lobby and registers it.
     *
     * @param lobbyName  a human-readable name (used for logging; the key is a UUID)
     * @param maxPlayers maximum number of players allowed
     * @return the newly created {@link Lobby}
     */
    public Lobby createLobby(String lobbyName, int maxPlayers) {
        String lobbyId = generateLobbyId();
        int max = maxPlayers > 0 ? maxPlayers : DEFAULT_MAX_PLAYERS;

        var lobby = new Lobby(lobbyId, max, DEFAULT_MIN_PLAYERS_START);
        lobbies.put(lobbyId, lobby);

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
        Lobby removed = lobbies.remove(lobbyId);
        if (removed != null) {
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
    public boolean joinLobby(String lobbyId, ChannelHandlerContext ctx) {
        return getLobby(lobbyId)
                .map(lobby -> lobby.addPlayer(ctx))
                .orElseGet(() -> {
                    logger.warn("Join failed — lobby {} does not exist", lobbyId);
                    return false;
                });
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
                    if (removed && lobby.getPlayerCount() == 0) {
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
            if (lobby.removePlayer(ctx) && lobby.getPlayerCount() == 0) {
                removeLobby(id);
            }
        });
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private String generateLobbyId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
