package com.example.bwqueue.party;

import com.example.bwqueue.config.PluginConfig;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simple in-memory Party system managed via Discord commands.
 * Commands (prefix =party):
 * - =party create
 * - =party invite @user
 * - =party accept
 * - =party leave
 * - =party disband
 */
public class PartyService {

    private final PluginConfig config;

    // guildId -> leaderId -> Party
    private final Map<Long, Map<Long, Party>> parties = new ConcurrentHashMap<>();
    // guildId -> userId -> leaderId (pending invites)
    private final Map<Long, Map<Long, Long>> invites = new ConcurrentHashMap<>();

    public PartyService(PluginConfig config) {
        this.config = config;
    }

    public void handle(MessageReceivedEvent event) {
        String[] parts = event.getMessage().getContentRaw().trim().split("\\s+");
        if (parts.length < 2) return;
        if (!config.isPartyEnabled()) {
            event.getMessage().reply("Parties are disabled by config.").queue();
            return;
        }
        String sub = parts[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "create":
                onCreate(event);
                break;
            case "invite":
                onInvite(event);
                break;
            case "accept":
                onAccept(event);
                break;
            case "leave":
                onLeave(event);
                break;
            case "disband":
                onDisband(event);
                break;
            default:
                event.getMessage().reply("Usage: =party create | invite @user | accept | leave | disband").queue();
        }
    }

    private void onCreate(MessageReceivedEvent e) {
        long guildId = e.getGuild().getIdLong();
        long author = e.getAuthor().getIdLong();
        Map<Long, Party> map = parties.computeIfAbsent(guildId, k -> new ConcurrentHashMap<>());
        // Check if already leader or member
        if (getPartyOf(guildId, author) != null) {
            e.getMessage().reply("You are already in a party.").queue();
            return;
        }
        Party p = new Party(author);
        p.members.add(author);
        map.put(author, p);
        e.getMessage().reply("Created a new party. You are the leader. Max members: " + config.getPartyMaxMembers()).queue();
    }

    private void onInvite(MessageReceivedEvent e) {
        if (e.getMessage().getMentions().getMembers().isEmpty()) {
            e.getMessage().reply("Mention a user to invite.").queue();
            return;
        }
        Member target = e.getMessage().getMentions().getMembers().get(0);
        long guildId = e.getGuild().getIdLong();
        long leader = e.getAuthor().getIdLong();
        Party p = getOwnedParty(guildId, leader);
        if (p == null) {
            e.getMessage().reply("You must be a party leader to invite.").queue();
            return;
        }
        if (p.members.size() >= config.getPartyMaxMembers()) {
            e.getMessage().reply("Party is full (max " + config.getPartyMaxMembers() + ").").queue();
            return;
        }
        if (getPartyOf(guildId, target.getIdLong()) != null) {
            e.getMessage().reply("That user is already in a party.").queue();
            return;
        }
        Map<Long, Long> gInv = invites.computeIfAbsent(guildId, k -> new ConcurrentHashMap<>());
        gInv.put(target.getIdLong(), leader);
        e.getMessage().reply("Invited " + target.getAsMention() + ". They can type `=party accept`.").queue();
    }

    private void onAccept(MessageReceivedEvent e) {
        long guildId = e.getGuild().getIdLong();
        long user = e.getAuthor().getIdLong();
        Map<Long, Long> gInv = invites.computeIfAbsent(guildId, k -> new ConcurrentHashMap<>());
        Long leader = gInv.remove(user);
        if (leader == null) {
            e.getMessage().reply("You have no pending party invite.").queue();
            return;
        }
        Party p = getOwnedParty(guildId, leader);
        if (p == null) {
            e.getMessage().reply("That party no longer exists.").queue();
            return;
        }
        if (p.members.size() >= config.getPartyMaxMembers()) {
            e.getMessage().reply("Party is full.").queue();
            return;
        }
        if (getPartyOf(guildId, user) != null) {
            e.getMessage().reply("You are already in a party.").queue();
            return;
        }
        p.members.add(user);
        e.getMessage().reply("Joined the party.").queue();
    }

    private void onLeave(MessageReceivedEvent e) {
        long guildId = e.getGuild().getIdLong();
        long user = e.getAuthor().getIdLong();
        Party p = getPartyOf(guildId, user);
        if (p == null) {
            e.getMessage().reply("You are not in a party.").queue();
            return;
        }
        if (p.leaderId == user) {
            // disband
            parties.getOrDefault(guildId, Collections.emptyMap()).remove(user);
            e.getMessage().reply("You left and disbanded the party (leader).").queue();
        } else {
            p.members.remove(user);
            e.getMessage().reply("You left the party.").queue();
        }
    }

    private void onDisband(MessageReceivedEvent e) {
        long guildId = e.getGuild().getIdLong();
        long leader = e.getAuthor().getIdLong();
        Party p = getOwnedParty(guildId, leader);
        if (p == null) {
            e.getMessage().reply("You are not a party leader.").queue();
            return;
        }
        parties.getOrDefault(guildId, Collections.emptyMap()).remove(leader);
        e.getMessage().reply("Party disbanded.").queue();
    }

    public Party getPartyOf(long guildId, long userId) {
        Map<Long, Party> map = parties.get(guildId);
        if (map == null) return null;
        for (Party p : map.values()) {
            if (p.members.contains(userId)) return p;
        }
        return null;
    }

    public Party getOwnedParty(long guildId, long leaderId) {
        Map<Long, Party> map = parties.get(guildId);
        if (map == null) return null;
        return map.get(leaderId);
    }

    public static class Party {
        public final long leaderId;
        public final Set<Long> members = Collections.newSetFromMap(new ConcurrentHashMap<>());
        public Party(long leaderId) { this.leaderId = leaderId; }
    }
}
