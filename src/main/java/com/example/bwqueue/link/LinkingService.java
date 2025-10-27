package com.example.bwqueue.link;

import com.example.bwqueue.config.PluginConfig;
import com.example.bwqueue.db.Database;

import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LinkingService {

    private final Database db;
    private final PluginConfig config;
    private final SecureRandom random = new SecureRandom();

    private static class Pending {
        final UUID uuid;
        final String name;
        final long expiresAt;
        Pending(UUID uuid, String name, long expiresAt) {
            this.uuid = uuid; this.name = name; this.expiresAt = expiresAt;
        }
    }

    private final Map<String, Pending> codes = new ConcurrentHashMap<>();

    public LinkingService(Database db, PluginConfig config) {
        this.db = db;
        this.config = config;
    }

    public String generateCode(UUID uuid, String name) {
        cleanup();
        String code;
        do {
            code = randomCode(config.getCodeLength());
        } while (codes.containsKey(code));
        long exp = Instant.now().getEpochSecond() + config.getCodeTtlSeconds();
        codes.put(code, new Pending(uuid, name, exp));
        return code;
    }

    public boolean consumeCodeAndLink(String code, String discordId) {
        cleanup();
        Pending p = codes.remove(code);
        if (p == null) return false;
        long now = Instant.now().getEpochSecond();
        if (p.expiresAt < now) return false;
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO users(discord_id, uuid, name, linked_at) VALUES(?,?,?,?) ON CONFLICT(discord_id) DO UPDATE SET uuid=excluded.uuid, name=excluded.name, linked_at=excluded.linked_at")) {
            ps.setString(1, discordId);
            ps.setString(2, p.uuid.toString());
            ps.setString(3, p.name);
            ps.setLong(4, now);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) {
            e.printStackTrace();
            return false;
        }
    }

    public UUID getLinkedUuid(String discordId) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT uuid FROM users WHERE discord_id=?")) {
            ps.setString(1, discordId);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String s = rs.getString(1);
                    if (s != null) return UUID.fromString(s);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    private void cleanup() {
        long now = Instant.now().getEpochSecond();
        codes.entrySet().removeIf(e -> e.getValue().expiresAt < now);
    }

    private String randomCode(int len) {
        // Use alphanumeric upper-case for simplicity
        final String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
