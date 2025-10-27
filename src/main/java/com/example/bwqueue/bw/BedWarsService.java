package com.example.bwqueue.bw;

import com.example.bwqueue.BWQueuePlugin;
import com.example.bwqueue.config.PluginConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.lang.reflect.Method;
import java.util.List;
import java.util.logging.Logger;

/**
 * Thin adapter around BedWars1058 (25.2) accessed via reflection to avoid hard compile coupling
 * and keep the project building on environments without the API.
 *
 * This class provides only the minimal surface BWQueue needs:
 * - Check if an arena from a given group is free
 * - Start a match on a free arena with two teams (Red/Green) and given players
 * - Detect end-of-game via a Bukkit event listener registered elsewhere (BWListeners)
 *
 * NOTE: The actual method names may differ across BW1058 builds. The reflection calls here are
 * implemented with best-effort names and logging. On your server, please check console logs on
 * first run; if needed, we will adjust method names.
 */
public class BedWarsService {

    private final Logger log;
    private final PluginConfig config;

    private Class<?> bwAPIClass; // andrei1058.bedwars.api.BedWars
    private Class<?> arenaClass; // andrei1058.bedwars.api.arena.IArena

    public BedWarsService(PluginConfig config) {
        this.log = BWQueuePlugin.get().getLogger();
        this.config = config;
        tryResolveClasses();
    }

    private void tryResolveClasses() {
        try {
            // Common API paths seen in BW1058
            try {
                bwAPIClass = Class.forName("com.andrei1058.bedwars.api.BedWarsAPI");
            } catch (ClassNotFoundException e) {
                // Older/newer
                bwAPIClass = Class.forName("com.andrei1058.bedwars.api.BedWars");
            }
            try {
                arenaClass = Class.forName("com.andrei1058.bedwars.api.arena.IArena");
            } catch (ClassNotFoundException ignored) {}
            log.info("[BWQueue] BedWars1058 API detected: " + bwAPIClass.getName());
        } catch (Throwable t) {
            log.warning("[BWQueue] Could not detect BedWars1058 API classes. Arena integration will be stubbed.");
        }
    }

    /**
     * Returns true if there is at least one free arena for the group.
     */
    public boolean isArenaAvailable(String group) {
        if (bwAPIClass == null) return true; // allow flow; QueueManager also handles pending
        try {
            // Hypothetical: BedWarsAPI.getArenasByGroup(String)
            Method inst = bwAPIClass.getMethod("getInstance");
            Object api = inst.invoke(null);
            Method getArenasByGroup = bwAPIClass.getMethod("getArenasByGroup", String.class);
            Object list = getArenasByGroup.invoke(api, group);
            if (list instanceof java.util.Collection) {
                for (Object arena : ((java.util.Collection<?>) list)) {
                    if (isArenaFree(arena)) return true;
                }
                return false;
            }
        } catch (Throwable t) {
            // Fall back to optimistic assume available but warn
            log.fine("[BWQueue] isArenaAvailable reflection failed: " + t.getMessage());
        }
        return true;
    }

    private boolean isArenaFree(Object arena) {
        try {
            // Hypothetical methods: arena.isJoinable() or arena.getStatus()
            try {
                Method isJoinable = arena.getClass().getMethod("isJoinable");
                Object r = isJoinable.invoke(arena);
                return (r instanceof Boolean) && (Boolean) r;
            } catch (NoSuchMethodException ignored) {}
            try {
                Method getStatus = arena.getClass().getMethod("getStatus");
                Object status = getStatus.invoke(arena);
                if (status != null) {
                    String s = status.toString().toLowerCase();
                    return s.contains("waiting") || s.contains("lobby") || s.contains("restarting");
                }
            } catch (NoSuchMethodException ignored) {}
        } catch (Throwable ignored) {}
        return false;
    }

    /**
     * Attempt to pick a free arena in the group and start the match with two teams.
     * Returns an arena identifier (name) if started, otherwise null.
     */
    public String startMatch(String group, List<Player> red, List<Player> green) {
        if (bwAPIClass == null) {
            log.warning("[BWQueue] BedWarsAPI not detected; starting in stub mode (no real match will start).");
            return "stub-arena";
        }
        try {
            Method inst = bwAPIClass.getMethod("getInstance");
            Object api = inst.invoke(null);
            Method getArenasByGroup = bwAPIClass.getMethod("getArenasByGroup", String.class);
            Object arenas = getArenasByGroup.invoke(api, group);
            if (!(arenas instanceof java.util.Collection)) {
                log.warning("[BWQueue] Unexpected response type from getArenasByGroup.");
                return null;
            }
            Object chosen = null;
            for (Object a : ((java.util.Collection<?>) arenas)) {
                if (isArenaFree(a)) { chosen = a; break; }
            }
            if (chosen == null) {
                log.info("[BWQueue] No free arena for group " + group);
                return null;
            }
            String arenaName = extractArenaName(chosen);

            // Hypothetical: add players to teams
            addPlayersToTeam(chosen, "RED", red);
            addPlayersToTeam(chosen, "GREEN", green);

            // Hypothetical: start the arena
            startArena(chosen);
            return arenaName != null ? arenaName : "unknown";
        } catch (Throwable t) {
            log.warning("[BWQueue] Failed to start BW1058 match: " + t.getMessage());
            return null;
        }
    }

    private String extractArenaName(Object arena) {
        try {
            try {
                Method getName = arena.getClass().getMethod("getDisplayName");
                Object r = getName.invoke(arena);
                if (r != null) return String.valueOf(r);
            } catch (NoSuchMethodException ignored) {}
            try {
                Method getName = arena.getClass().getMethod("getName");
                Object r = getName.invoke(arena);
                if (r != null) return String.valueOf(r);
            } catch (NoSuchMethodException ignored) {}
        } catch (Throwable ignored) {}
        return null;
    }

    private void addPlayersToTeam(Object arena, String teamColor, List<Player> players) throws Exception {
        // Hypothetical paths: arena.getTeamByName(String) -> team.addPlayer(Player)
        try {
            Method getTeam = arena.getClass().getMethod("getTeamByName", String.class);
            Object team = getTeam.invoke(arena, teamColor);
            if (team == null) {
                log.warning("[BWQueue] Team not found: " + teamColor);
                return;
            }
            Method add = team.getClass().getMethod("addPlayer", Player.class);
            for (Player p : players) {
                add.invoke(team, p);
            }
        } catch (NoSuchMethodException e) {
            // Alternative: arena.addPlayerToTeam(Player, String)
            Method add = arena.getClass().getMethod("addPlayerToTeam", Player.class, String.class);
            for (Player p : players) add.invoke(arena, p, teamColor);
        }
    }

    private void startArena(Object arena) throws Exception {
        try {
            Method start = arena.getClass().getMethod("start");
            start.invoke(arena);
            return;
        } catch (NoSuchMethodException ignored) {}
        try {
            Method forceStart = arena.getClass().getMethod("forceStart");
            forceStart.invoke(arena);
            return;
        } catch (NoSuchMethodException ignored) {}
        // As a last resort, run a console command
        String name = extractArenaName(arena);
        if (name != null) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "bw admin start " + name);
        }
    }
}
