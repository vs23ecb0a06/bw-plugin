package com.example.bwqueue.listeners;

import com.example.bwqueue.db.Database;
import com.example.bwqueue.session.SessionRegistry;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;

public class BWListeners implements Listener {

    private final Database db;
    private final SessionRegistry sessions;

    public BWListeners(Database db, SessionRegistry sessions) {
        this.db = db;
        this.sessions = sessions;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent e) {
        SessionRegistry.Entry en = sessions.get(e.getPlayer().getUniqueId());
        if (en == null) return;
        record(en.sessionId, "BLOCK_PLACE", e.getPlayer().getUniqueId().toString(), e.getBlockPlaced().getType().name());
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent e) {
        SessionRegistry.Entry en = sessions.get(e.getPlayer().getUniqueId());
        if (en == null) return;
        record(en.sessionId, "BLOCK_BREAK", e.getPlayer().getUniqueId().toString(), e.getBlock().getType().name());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(PlayerPickupItemEvent e) {
        SessionRegistry.Entry en = sessions.get(e.getPlayer().getUniqueId());
        if (en == null) return;
        String mat = e.getItem().getItemStack().getType().name();
        // Track only common BedWars resources
        if (!(mat.contains("IRON") || mat.contains("GOLD") || mat.contains("EMERALD") || mat.contains("DIAMOND"))) return;
        record(en.sessionId, "PICKUP", e.getPlayer().getUniqueId().toString(), mat + ":" + e.getItem().getItemStack().getAmount());
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        if (e.getEntity() == null) return;
        SessionRegistry.Entry victimEn = sessions.get(e.getEntity().getUniqueId());
        if (victimEn != null) {
            String killerUuid = e.getEntity().getKiller() != null ? e.getEntity().getKiller().getUniqueId().toString() : "";
            record(victimEn.sessionId, "KILL", killerUuid, "victim=" + e.getEntity().getUniqueId());
        }
    }

    private void record(long sessionId, String type, String playerUuid, String value) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO events(session_id, type, player_uuid, value, ts) VALUES(?,?,?,?,?)")) {
            ps.setLong(1, sessionId);
            ps.setString(2, type);
            ps.setString(3, playerUuid);
            ps.setString(4, value);
            ps.setLong(5, Instant.now().getEpochSecond());
            ps.executeUpdate();
        } catch (SQLException ex) {
            ex.printStackTrace();
        }
    }
}
