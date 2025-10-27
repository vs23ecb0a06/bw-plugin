package com.example.bwqueue.queue;

import com.example.bwqueue.BWQueuePlugin;
import com.example.bwqueue.config.PluginConfig;
import com.example.bwqueue.db.Database;
import com.example.bwqueue.discord.DiscordBot;
import com.example.bwqueue.link.LinkingService;
import com.example.bwqueue.party.PartyService;
import com.example.bwqueue.bw.BedWarsService;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.requests.restaction.ChannelAction;
import org.bukkit.Bukkit;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

public class QueueManager {

    private final PluginConfig config;
    private final DiscordBot bot;
    private final Database db;
    private final LinkingService linkingService;
    private final Logger log;

    private final PartyService partyService;
    private final BedWarsService bedWars;
    private final com.example.bwqueue.session.SessionRegistry sessions;

    private final Queue<QueueEntry> queue = new ConcurrentLinkedQueue<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> new Thread(r, "BWQueue-Worker"));

    private final AtomicInteger activeGames = new AtomicInteger(0);

    public QueueManager(PluginConfig config, DiscordBot bot, Database db, LinkingService linkingService) {
        this.config = config;
        this.bot = bot;
        this.db = db;
        this.linkingService = linkingService;
        this.log = BWQueuePlugin.get().getLogger();
        this.partyService = new PartyService(config);
        this.bedWars = new BedWarsService(config);
        this.sessions = new com.example.bwqueue.session.SessionRegistry();
        startTick();
    }

    private void startTick() {
        // Periodically attempt to form games
        Bukkit.getScheduler().runTaskTimerAsynchronously(BWQueuePlugin.get(), () -> {
            try { tryStartGame(); } catch (Exception e) { e.printStackTrace(); }
        }, 40L, 40L);
    }

    public void enqueue(Member member, UUID uuid, String name, Guild guild) {
        // Ensure not duplicated
        boolean exists = queue.stream().anyMatch(q -> q.member.getIdLong() == member.getIdLong());
        if (!exists) {
            queue.add(new QueueEntry(member, uuid, name, guild, Instant.now().getEpochSecond()));
            log.info("Enqueued " + name + " (" + uuid + ") from Discord " + member.getUser().getAsTag());
        }
    }

    private final List<PendingBatch> pendingBatches = new ArrayList<>();

    public void handlePartyCommand(net.dv8tion.jda.api.events.message.MessageReceivedEvent event) {
        if (partyService != null) partyService.handle(event);
    }

    private void tryStartGame() {
        // First, try to allocate pending batches if any arena is available
        Iterator<PendingBatch> it = pendingBatches.iterator();
        while (it.hasNext()) {
            PendingBatch pb = it.next();
            if (activeGames.get() >= config.getMaxConcurrentGames()) break;
            if (isBatchInvalid(pb)) {
                pb.textChannel.sendMessage("Batch cancelled: someone left the queue or went offline.").queue();
                cleanupBatchChannels(pb);
                it.remove();
                continue;
            }
            if (isArenaAvailable(getArenaGroupFromConfig())) {
                it.remove();
                activeGames.incrementAndGet();
                worker.submit(() -> orchestrateGame(pb.entries));
            }
        }

        if (activeGames.get() >= config.getMaxConcurrentGames()) return;
        int teamSize = getConfiguredTeamSize();
        int min = Math.max(config.getMinPlayers(), teamSize * 2); // ensure two full teams
        int maxPlayers = 2 * teamSize; // always 2 teams only

        List<QueueEntry> candidates = new ArrayList<>();
        while (candidates.size() < maxPlayers) {
            QueueEntry e = queue.poll();
            if (e == null) break;
            // Verify still eligible (online, in queue VC)
            org.bukkit.entity.Player p = Bukkit.getPlayer(e.uuid);
            boolean online = p != null && p.isOnline();
            boolean inQueueVc = e.member.getVoiceState() != null && e.member.getVoiceState().inAudioChannel() &&
                    e.member.getVoiceState().getChannel().getId().equals(config.getQueueVoiceId());
            if (!online || !inQueueVc) {
                // skip
                continue;
            }
            candidates.add(e);
        }

        if (candidates.size() < min) {
            // put back
            for (QueueEntry e : candidates) queue.add(e);
            return;
        }

        // Apply party grouping if enabled
        List<QueueEntry> batch = buildTeamsRespectingParties(candidates, teamSize);
        if (batch.size() < min) {
            for (QueueEntry e : candidates) queue.add(e);
            return;
        }

        // If arena available, start immediately; else keep as pending batch; do NOT move people to waiting room.
        if (isArenaAvailable(getArenaGroupFromConfig())) {
            activeGames.incrementAndGet();
            worker.submit(() -> orchestrateGame(batch));
            // return leftovers to queue
            Set<Long> used = new HashSet<>();
            for (QueueEntry e : batch) used.add(e.member.getIdLong());
            for (QueueEntry e : candidates) if (!used.contains(e.member.getIdLong())) queue.add(e);
        } else {
            // create a temporary text channel for status and keep in pending list
            Guild guild = batch.get(0).guild;
            Category cat = bot.getGamesCategory(guild);
            if (cat == null) cat = guild.createCategory("bw-pending-" + System.currentTimeMillis()/1000L).complete();
            TextChannel text = guild.createTextChannel("bw-pending", cat).complete();
            text.sendMessage("No arenas free for group '" + getArenaGroupFromConfig() + "'. Your game will start automatically as soon as one is free. If someone leaves the queue VC or goes offline, batch will cancel.").queue();
            pendingBatches.add(new PendingBatch(batch, text, cat));
            // return leftovers to queue
            Set<Long> used = new HashSet<>();
            for (QueueEntry e : batch) used.add(e.member.getIdLong());
            for (QueueEntry e : candidates) if (!used.contains(e.member.getIdLong())) queue.add(e);
        }
    }

    private void orchestrateGame(List<QueueEntry> batch) {
        // Always 2 teams: Red and Green
        int teamSize = getConfiguredTeamSize();
        List<List<QueueEntry>> teams = new ArrayList<>();
        teams.add(new ArrayList<>()); // Red
        teams.add(new ArrayList<>()); // Green

        // If parties are enabled, try to keep party members on same team
        List<QueueEntry> arranged = buildTeamsRespectingParties(batch, teamSize);
        for (int i = 0; i < arranged.size(); i++) {
            teams.get(i % 2).add(arranged.get(i));
        }

        Guild guild = batch.get(0).guild;
        Category cat = bot.getGamesCategory(guild);
        String arenaGroup = getArenaGroupFromConfig();

        // Create a category per game if none specified
        if (cat == null) {
            cat = guild.createCategory("bw-game-" + System.currentTimeMillis()/1000L).complete();
        }

        TextChannel text = guild.createTextChannel("bw-game-chat", cat).complete();
        VoiceChannel redVc = guild.createVoiceChannel("team-1-red", cat).complete();
        VoiceChannel greenVc = guild.createVoiceChannel("team-2-green", cat).complete();

        // Move users and announce
        text.sendMessage("Spinning up a BedWars game for group '" + arenaGroup + "' with " + batch.size() + " players. Teams of size " + teamSize + ".").queue();
        for (QueueEntry qe : teams.get(0)) {
            safeMove(guild, qe.member, redVc);
        }
        for (QueueEntry qe : teams.get(1)) {
            safeMove(guild, qe.member, greenVc);
        }

        long sessionId = recordSessionStart(arenaGroup);
        try {
            text.sendMessage("[Stub] Starting game on available arena from group '" + arenaGroup + "'...").queue();
            // TODO: Select an arena, add players to teams, start arena via BW1058 API.

            // Wait for game to end - placeholder sleep or hook into events
            try { Thread.sleep(15000L); } catch (InterruptedException ignored) {}

            String winner = "Red"; // Placeholder
            recordSessionEnd(sessionId, winner);

            // Build scorecard (placeholder)
            StringBuilder sb = new StringBuilder();
            sb.append("Winner: ").append(winner).append("\n");
            sb.append("Players:\n");
            sb.append("Red: "); for (QueueEntry qe : teams.get(0)) sb.append(qe.name).append(", "); if (sb.length()>6) sb.setLength(sb.length()-2); sb.append("\n");
            sb.append("Green: "); for (QueueEntry qe : teams.get(1)) sb.append(qe.name).append(", "); if (sb.length()>8) sb.setLength(sb.length()-2); sb.append("\n");
            text.sendMessage("Game summary:\n" + sb).queue();
        } finally {
            // Cleanup channels
            try {
                if (config.isDeleteTeamVoiceAtEnd()) {
                    redVc.delete().queue();
                    greenVc.delete().queue();
                }
            } catch (Exception ignored) {}
            int ttl = config.getTextChannelTtlSeconds();
            if (ttl <= 0) {
                try { text.delete().queue(); } catch (Exception ignored) {}
            } else {
                Bukkit.getScheduler().runTaskLaterAsynchronously(BWQueuePlugin.get(), () -> {
                    try { text.delete().queue(); } catch (Exception ignored) {}
                }, ttl * 20L);
            }
            activeGames.decrementAndGet();
        }
    }

    private void safeMove(Guild guild, Member m, VoiceChannel vc) {
        try { guild.moveVoiceMember(m, vc).queue(); } catch (Exception ex) { log.warning("Failed to move " + m.getUser().getAsTag() + ": " + ex.getMessage()); }
    }

    private boolean isArenaAvailable(String group) {
        try {
            return bedWars.isArenaAvailable(group);
        } catch (Exception e) {
            return true; // be optimistic
        }
    }

    private String getArenaGroupFromConfig() {
        String type = config.getQueueType();
        return "3".equals(type) ? config.getGroupFor3() : config.getGroupFor4();
    }

    private int getConfiguredTeamSize() {
        String type = config.getQueueType();
        if ("3".equals(type)) return 3;
        if ("4".equals(type)) return 4;
        return Math.max(1, config.getTeamSize());
    }

    private boolean isBatchInvalid(PendingBatch pb) {
        for (QueueEntry e : pb.entries) {
            org.bukkit.entity.Player p = Bukkit.getPlayer(e.uuid);
            boolean online = p != null && p.isOnline();
            boolean inQueueVc = e.member.getVoiceState() != null && e.member.getVoiceState().inAudioChannel() &&
                    e.member.getVoiceState().getChannel().getId().equals(config.getQueueVoiceId());
            if (!online || !inQueueVc) return true;
        }
        return false;
    }

    private void cleanupBatchChannels(PendingBatch pb) {
        try { pb.textChannel.delete().queue(); } catch (Exception ignored) {}
        try { if (pb.categoryCreated) pb.category.delete().queue(); } catch (Exception ignored) {}
    }

    private List<QueueEntry> buildTeamsRespectingParties(List<QueueEntry> candidates, int teamSize) {
        if (!config.isPartyEnabled()) return new ArrayList<>(candidates);
        // Very simple: try to keep members of the same party together by grouping by party leader
        Map<Long, List<QueueEntry>> byLeader = new LinkedHashMap<>();
        for (QueueEntry e : candidates) {
            long gid = e.guild.getIdLong();
            // We cannot access PartyService here for membership without exposing it; for now, return as-is.
            // TODO: Integrate PartyService lookup to group members by party.
        }
        return new ArrayList<>(candidates);
    }

    static class PendingBatch {
        final List<QueueEntry> entries;
        final TextChannel textChannel;
        final Category category;
        final boolean categoryCreated;
        PendingBatch(List<QueueEntry> entries, TextChannel textChannel, Category category) {
            this.entries = entries; this.textChannel = textChannel; this.category = category; this.categoryCreated = false;
        }
    }

    private long recordSessionStart(String group) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("INSERT INTO sessions(arena, group_name, started_at) VALUES(?,?,?)")) {
            ps.setString(1, "TBD");
            ps.setString(2, group);
            ps.setLong(3, Instant.now().getEpochSecond());
            ps.executeUpdate();
            try (java.sql.ResultSet rs = c.createStatement().executeQuery("SELECT last_insert_rowid()")) {
                if (rs.next()) return rs.getLong(1);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return -1L;
    }

    private void recordSessionEnd(long sessionId, String winnerTeam) {
        try (Connection c = db.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE sessions SET ended_at=?, winner_team=? WHERE id=?")) {
            ps.setLong(1, Instant.now().getEpochSecond());
            ps.setString(2, winnerTeam);
            ps.setLong(3, sessionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    static class QueueEntry {
        final Member member;
        final UUID uuid;
        final String name;
        final Guild guild;
        final long enqueuedAt;
        QueueEntry(Member member, UUID uuid, String name, Guild guild, long enqueuedAt) {
            this.member = member; this.uuid = uuid; this.name = name; this.guild = guild; this.enqueuedAt = enqueuedAt;
        }
    }

    public int getQueuedCount() {
        return queue.size();
    }

    public int getActiveGames() {
        return activeGames.get();
    }
}
