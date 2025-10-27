package com.example.bwqueue.config;

import org.bukkit.configuration.file.FileConfiguration;

public class PluginConfig {
    private final String discordToken;
    private final String queueVoiceId;
    private final String waitingVoiceId;
    private final String gamesCategoryId;
    private final int textChannelTtlSeconds;
    private final boolean deleteTeamVoiceAtEnd;

    private final int minPlayers;
    private final int teamSize;
    private final int maxTeams;
    private final String queueType;
    private final String groupFor3;
    private final String groupFor4;
    private final int startTimeoutSeconds;
    private final int maxConcurrentGames;

    private final boolean partyEnabled;
    private final int partyMaxMembers;

    private final int codeLength;
    private final int codeTtlSeconds;
    private final boolean requireOnlineForCode;

    private final int eloDefault;

    private final String sqliteFile;
    private final int maxPoolSize;

    private final boolean debug;

    public PluginConfig(FileConfiguration cfg) {
        this.discordToken = cfg.getString("discord.token", "");
        this.queueVoiceId = cfg.getString("discord.queueVoiceChannelId", "");
        this.waitingVoiceId = cfg.getString("discord.waitingRoomVoiceChannelId", "");
        this.gamesCategoryId = cfg.getString("discord.gamesCategoryId", "");
        this.textChannelTtlSeconds = cfg.getInt("discord.textChannelTtlSeconds", 120);
        this.deleteTeamVoiceAtEnd = cfg.getBoolean("discord.deleteTeamVoiceAtEnd", true);

        this.queueType = cfg.getString("queue.type", "4");
        this.groupFor3 = cfg.getString("queue.groups.3", "3v3v3v3");
        this.groupFor4 = cfg.getString("queue.groups.4", "4v4v4v4");
        this.minPlayers = cfg.getInt("queue.minPlayers", 4);
        this.teamSize = cfg.getInt("queue.teamSize", 2);
        this.maxTeams = cfg.getInt("queue.maxTeams", 2);
        this.startTimeoutSeconds = cfg.getInt("queue.startTimeoutSeconds", 60);
        this.maxConcurrentGames = cfg.getInt("queue.maxConcurrentGames", 5);

        this.partyEnabled = cfg.getBoolean("party.enabled", false);
        this.partyMaxMembers = cfg.getInt("party.maxMembers", 4);

        this.codeLength = cfg.getInt("linking.codeLength", 6);
        this.codeTtlSeconds = cfg.getInt("linking.codeTtlSeconds", 300);
        this.requireOnlineForCode = cfg.getBoolean("linking.requireOnlineForCode", true);

        this.eloDefault = cfg.getInt("elo.default", 0);

        this.sqliteFile = cfg.getString("storage.sqliteFile", "plugins/BWQueue/bwqueue.db");
        this.maxPoolSize = cfg.getInt("storage.maxPoolSize", 4);

        this.debug = cfg.getBoolean("logging.debug", false);
    }

    public String getDiscordToken() { return discordToken; }
    public String getQueueVoiceId() { return queueVoiceId; }
    public String getWaitingVoiceId() { return waitingVoiceId; }
    public String getGamesCategoryId() { return gamesCategoryId; }
    public int getTextChannelTtlSeconds() { return textChannelTtlSeconds; }
    public boolean isDeleteTeamVoiceAtEnd() { return deleteTeamVoiceAtEnd; }

    public String getQueueType() { return queueType; }
    public String getGroupFor3() { return groupFor3; }
    public String getGroupFor4() { return groupFor4; }
    public int getMinPlayers() { return minPlayers; }
    public int getTeamSize() { return teamSize; }
    public int getMaxTeams() { return maxTeams; }
    public int getStartTimeoutSeconds() { return startTimeoutSeconds; }
    public int getMaxConcurrentGames() { return maxConcurrentGames; }

    public boolean isPartyEnabled() { return partyEnabled; }
    public int getPartyMaxMembers() { return partyMaxMembers; }

    public int getCodeLength() { return codeLength; }
    public int getCodeTtlSeconds() { return codeTtlSeconds; }
    public boolean isRequireOnlineForCode() { return requireOnlineForCode; }

    public int getEloDefault() { return eloDefault; }

    public String getSqliteFile() { return sqliteFile; }
    public int getMaxPoolSize() { return maxPoolSize; }

    public boolean isDebug() { return debug; }
}
