package com.example.bwqueue.session;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which session a player currently belongs to and their team color.
 */
public class SessionRegistry {

    public static class Entry {
        public final long sessionId;
        public final String team; // "Red" or "Green"
        public Entry(long sessionId, String team) {
            this.sessionId = sessionId;
            this.team = team;
        }
    }

    private final Map<UUID, Entry> byPlayer = new ConcurrentHashMap<>();

    public void set(UUID uuid, long sessionId, String team) {
        byPlayer.put(uuid, new Entry(sessionId, team));
    }

    public void clear(UUID uuid) {
        byPlayer.remove(uuid);
    }

    public Entry get(UUID uuid) {
        return byPlayer.get(uuid);
    }
}
