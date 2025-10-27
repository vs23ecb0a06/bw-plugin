package com.example.bwqueue.db;

import com.example.bwqueue.config.PluginConfig;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class Database {
    private final PluginConfig config;
    private HikariDataSource ds;

    public Database(PluginConfig config) {
        this.config = config;
    }

    public void init() throws SQLException {
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl("jdbc:sqlite:" + config.getSqliteFile());
        hc.setMaximumPoolSize(config.getMaxPoolSize());
        hc.setPoolName("BWQueue-SQLite");
        hc.setDriverClassName("org.sqlite.JDBC");
        this.ds = new HikariDataSource(hc);

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("PRAGMA journal_mode=WAL");
            st.execute("CREATE TABLE IF NOT EXISTS users (" +
                    "discord_id TEXT PRIMARY KEY, " +
                    "uuid TEXT, " +
                    "name TEXT, " +
                    "linked_at INTEGER" +
                    ")");
            // Add ELO column if missing
            try {
                st.execute("ALTER TABLE users ADD COLUMN elo INTEGER DEFAULT 0");
            } catch (SQLException ignored) {}

            st.execute("CREATE TABLE IF NOT EXISTS sessions (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "arena TEXT, " +
                    "group_name TEXT, " +
                    "started_at INTEGER, " +
                    "ended_at INTEGER, " +
                    "winner_team TEXT" +
                    ")");

            st.execute("CREATE TABLE IF NOT EXISTS session_players (" +
                    "session_id INTEGER, " +
                    "uuid TEXT, " +
                    "name TEXT, " +
                    "team TEXT, " +
                    "stats_json TEXT, " +
                    "PRIMARY KEY(session_id, uuid)" +
                    ")");

            st.execute("CREATE TABLE IF NOT EXISTS events (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "session_id INTEGER, " +
                    "type TEXT, " +
                    "player_uuid TEXT, " +
                    "value TEXT, " +
                    "ts INTEGER" +
                    ")");
        }
    }

    public Connection getConnection() throws SQLException {
        return ds.getConnection();
    }

    public void shutdown() {
        if (ds != null) ds.close();
    }
}
