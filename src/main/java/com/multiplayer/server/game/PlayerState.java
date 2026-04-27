package com.multiplayer.server.game;

/**
 * Server-authoritative player state.
 * <p>
 * Mutable by design — updated every tick on the game-loop thread,
 * so no synchronisation is required (single-writer model per the
 * architecture rules).
 */
public final class PlayerState {

    private final String playerId;
    private float x;
    private float y;

    /** Movement speed in units per tick. */
    private static final float SPEED = 3.0f;

    /** Player size (half-width/half-height for box collider). */
    public static final float RADIUS = 24.0f;

    /** World boundaries. */
    private static final float MIN_BOUND = -500.0f;
    private static final float MAX_BOUND = 500.0f;

    public PlayerState(String playerId, float x, float y) {
        this.playerId = playerId;
        this.x = x;
        this.y = y;
    }

    public PlayerState(String playerId) {
        this(playerId, 0.0f, 0.0f);
    }

    /**
     * Applies directional input to the player's position.
     * Input values are clamped to [-1, 1] and scaled by {@link #SPEED}.
     *
     * @param dx horizontal input (-1.0 left, +1.0 right)
     * @param dy vertical input (-1.0 down, +1.0 up)
     */
    public void applyInput(float dx, float dy) {
        dx = clamp(dx, -1.0f, 1.0f);
        dy = clamp(dy, -1.0f, 1.0f);

        // Clamp center position so the box edges stay within world boundaries
        this.x = clamp(this.x + dx * SPEED, MIN_BOUND + RADIUS, MAX_BOUND - RADIUS);
        this.y = clamp(this.y + dy * SPEED, MIN_BOUND + RADIUS, MAX_BOUND - RADIUS);
    }

    public void setPosition(float x, float y) {
        this.x = x;
        this.y = y;
    }

    // ── Accessors ───────────────────────────────────────────────────────

    public String getPlayerId() {
        return playerId;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    // ── Helpers ─────────────────────────────────────────────────────────

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public String toString() {
        return "PlayerState{id='%s', x=%.2f, y=%.2f}".formatted(playerId, x, y);
    }
}
