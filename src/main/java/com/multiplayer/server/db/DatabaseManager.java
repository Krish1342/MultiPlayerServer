package com.multiplayer.server.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Manages the HikariCP connection pool and database schema initialisation.
 * <p>
 * In development mode the pool connects to an <b>in-memory H2</b> database.
 * For production, swap the JDBC URL and driver to MySQL 8 via configuration.
 *
 * <h2>Thread Safety</h2>
 * {@link HikariDataSource} is fully thread-safe. Connections obtained via
 * {@link #getConnection()} can be used from any thread (Netty event-loop,
 * virtual threads, etc.) and <b>must</b> be closed after use
 * (try-with-resources).
 */
public final class DatabaseManager {

    private static final Logger logger = LoggerFactory.getLogger(DatabaseManager.class);

    private final HikariDataSource dataSource;

    // ── Construction ────────────────────────────────────────────────────

    /**
     * Creates the connection pool and runs the DDL migration.
     */
    public DatabaseManager() {
        this.dataSource = createDataSource();
        initializeSchema();
        logger.info("DatabaseManager initialised (H2 in-memory)");
    }

    /**
     * Overload that accepts an explicit JDBC URL (useful for testing or
     * switching to MySQL in production).
     */
    public DatabaseManager(String jdbcUrl, String username, String password) {
        this.dataSource = createDataSource(jdbcUrl, username, password);
        initializeSchema();
        logger.info("DatabaseManager initialised ({})", jdbcUrl);
    }

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Returns a pooled {@link Connection}.
     * <p>
     * <b>Callers must close the connection</b> (preferably via
     * try-with-resources) so it is returned to the pool.
     */
    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    /**
     * Shuts down the connection pool. Call once during server shutdown.
     */
    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            logger.info("Database connection pool closed");
        }
    }

    // ── Internals ───────────────────────────────────────────────────────

    /**
     * Builds a default HikariCP pool pointing at an in-memory H2 instance.
     */
    private static HikariDataSource createDataSource() {
        return createDataSource(
                "jdbc:h2:mem:multiplayer;DB_CLOSE_DELAY=-1",
                "sa",
                "");
    }

    private static HikariDataSource createDataSource(String jdbcUrl, String user, String pass) {
        var config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(user);
        config.setPassword(pass);

        // Pool sizing — keep small for dev; tune for prod
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(2);
        config.setIdleTimeout(30_000);
        config.setConnectionTimeout(5_000);
        config.setMaxLifetime(600_000);

        config.setPoolName("mp-hikari-pool");
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "256");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");

        return new HikariDataSource(config);
    }

    /**
     * Creates the initial schema if it does not already exist.
     */
    private void initializeSchema() {
        String createUsersTable = """
                CREATE TABLE IF NOT EXISTS users (
                    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
                    username      VARCHAR(64)  NOT NULL UNIQUE,
                    password_hash VARCHAR(256) NOT NULL
                )
                """;

        try (Connection conn = dataSource.getConnection();
                Statement stmt = conn.createStatement()) {

            stmt.execute(createUsersTable);
            logger.info("Schema initialised — 'users' table ready");

        } catch (SQLException e) {
            logger.error("Failed to initialise database schema", e);
            throw new RuntimeException("Database schema initialisation failed", e);
        }
    }
}
