package com.professoros.database;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Objects;
import java.util.logging.Logger;

/** Opens short-lived connections. Callers own and close each returned connection. */
public final class Database {
    private static final Logger LOGGER = Logger.getLogger(Database.class.getName());
    private final Path file;
    private final String jdbcUrl;
    private final boolean readOnly;

    public Database(Path file) {
        this.file = Objects.requireNonNull(file, "Database path is required")
                .toAbsolutePath().normalize();
        this.jdbcUrl = "jdbc:sqlite:" + this.file;
        this.readOnly = false;
    }

    /** A separate, volatile working copy. Its owner keeps one connection open for its lifetime. */
    static Database transientWorkspace() {
        return new Database("jdbc:sqlite:file:professor-os-" + java.util.UUID.randomUUID() + "?mode=memory&cache=shared", false);
    }

    Database readOnlySessionView() {
        if (file != null) throw new IllegalStateException("A session workspace is required");
        return new Database(jdbcUrl, true);
    }

    private Database(String jdbcUrl, boolean readOnly) {
        this.file = null;
        this.jdbcUrl = jdbcUrl;
        this.readOnly = readOnly;
    }

    public void initialize() throws SQLException {
        try (Connection connection = openConnection()) {
            if (!connection.isValid(2)) {
                throw new SQLException("Database connection validation failed: " + file);
            }
            new MigrationRunner().migrate(connection);
        } catch (SQLException failure) {
            throw new SQLException("Database initialization failed at " + file + ": " + failure.getMessage(), failure);
        }
        LOGGER.info(() -> file == null ? "Session workspace initialized in memory" : "Database initialized at " + file);
    }

    public Connection openConnection() throws SQLException {
        if (file != null) try {
            Files.createDirectories(file.getParent());
        } catch (IOException exception) {
            throw new SQLException("Cannot create database directory: " + file.getParent(), exception);
        }

        Connection connection = DriverManager.getConnection(jdbcUrl);
        try {
            // Static configuration only. Future queries with values must use prepared statements.
            try (var statement = connection.createStatement()) {
                statement.execute("PRAGMA foreign_keys = ON");
                if (file == null) statement.execute("PRAGMA temp_store = MEMORY");
                if (readOnly) statement.execute("PRAGMA query_only = ON");
                statement.execute("PRAGMA busy_timeout = 5000");
                try (var result = statement.executeQuery("PRAGMA foreign_keys")) {
                    if (!result.next() || result.getInt(1) != 1) {
                        throw new SQLException("SQLite foreign key enforcement could not be enabled");
                    }
                }
            }
            return connection;
        } catch (SQLException exception) {
            try {
                connection.close();
            } catch (SQLException closeFailure) {
                exception.addSuppressed(closeFailure);
            }
            throw exception;
        }
    }
}
