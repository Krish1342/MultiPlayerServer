package com.multiplayer.server.db;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HexFormat;

/**
 * Data-access object for the {@code users} table.
 * <p>
 * All methods perform <b>blocking JDBC calls</b> and must therefore be
 * invoked from a Virtual Thread (or other off-event-loop context) — never
 * directly from a Netty {@code EventLoop}.
 *
 * <h2>Password Storage</h2>
 * Uses SHA-256 hashing for simplicity in development. In production this
 * should be replaced with a slow, salted hash (bcrypt / Argon2).
 */
public final class UserRepository {

    private static final Logger logger = LoggerFactory.getLogger(UserRepository.class);

    private final DatabaseManager databaseManager;

    public UserRepository(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Authenticates a user by comparing the SHA-256 hash of the supplied
     * password against the stored {@code password_hash}.
     *
     * @return {@code true} if the username exists and the password matches
     */
    public boolean authenticate(String username, String password) {
        String sql = "SELECT password_hash FROM users WHERE username = ?";

        try (Connection conn = databaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, username);

            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String storedHash = rs.getString("password_hash");
                    String inputHash = hashPassword(password);
                    boolean match = storedHash.equals(inputHash);

                    if (match) {
                        logger.info("Authentication succeeded for user '{}'", username);
                    } else {
                        logger.warn("Authentication failed for user '{}' — bad password", username);
                    }
                    return match;
                }
            }

            logger.warn("Authentication failed — user '{}' not found", username);
            return false;

        } catch (SQLException e) {
            logger.error("Database error during authentication for '{}': {}", username, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Registers a new user. Returns {@code true} on success, {@code false}
     * if the username already exists or a DB error occurs.
     */
    public boolean register(String username, String password) {
        String sql = "INSERT INTO users (username, password_hash) VALUES (?, ?)";

        try (Connection conn = databaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, username);
            ps.setString(2, hashPassword(password));

            int rows = ps.executeUpdate();
            if (rows == 1) {
                logger.info("User '{}' registered successfully", username);
                return true;
            }
            return false;

        } catch (SQLException e) {
            // Unique-constraint violation → duplicate username
            logger.warn("Registration failed for '{}': {}", username, e.getMessage());
            return false;
        }
    }

    /**
     * Checks whether a username is already taken.
     */
    public boolean userExists(String username) {
        String sql = "SELECT 1 FROM users WHERE username = ?";

        try (Connection conn = databaseManager.getConnection();
                PreparedStatement ps = conn.prepareStatement(sql)) {

            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }

        } catch (SQLException e) {
            logger.error("Database error checking existence of '{}': {}", username, e.getMessage(), e);
            return false;
        }
    }

    // ── Internals ───────────────────────────────────────────────────────

    /**
     * Hashes a password with SHA-256 and returns the hex-encoded digest.
     * <p>
     * <b>Dev-only</b> — replace with bcrypt/Argon2 for production.
     */
    private static String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }
}
