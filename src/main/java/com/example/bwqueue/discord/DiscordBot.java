package com.example.bwqueue.discord;

import com.example.bwqueue.BWQueuePlugin;
import com.example.bwqueue.config.PluginConfig;
import com.example.bwqueue.link.LinkingService;
import com.example.bwqueue.queue.QueueManager;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.OnlineStatus;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.attribute.IAgeRestrictedChannel;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.unions.GuildChannelUnion;
import net.dv8tion.jda.api.events.ReadyEvent;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.guild.voice.GuildVoiceUpdateEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import org.bukkit.Bukkit;
import org.jetbrains.annotations.NotNull;

import javax.security.auth.login.LoginException;
import java.util.EnumSet;
import java.util.Objects;
import java.util.UUID;
import java.util.logging.Logger;

public class DiscordBot extends ListenerAdapter {
    private final PluginConfig config;
    private final LinkingService linkingService;
    private JDA jda;
    private QueueManager queueManager;
    private final Logger log = BWQueuePlugin.get().getLogger();

    public DiscordBot(PluginConfig config, LinkingService linkingService) {
        this.config = config;
        this.linkingService = linkingService;
    }

    public void setQueueManager(QueueManager queueManager) {
        this.queueManager = queueManager;
    }

    public void start() throws LoginException, InterruptedException {
        if (config.getDiscordToken() == null || config.getDiscordToken().isEmpty()) {
            log.warning("Discord token is empty; bot disabled.");
            return;
        }
        EnumSet<GatewayIntent> intents = EnumSet.of(
                GatewayIntent.GUILD_MEMBERS,
                GatewayIntent.GUILD_MESSAGES,
                GatewayIntent.MESSAGE_CONTENT,
                GatewayIntent.GUILD_VOICE_STATES
        );
        this.jda = JDABuilder.create(config.getDiscordToken(), intents)
                .setMemberCachePolicy(MemberCachePolicy.ALL)
                .setBulkDeleteSplittingEnabled(false)
                .setStatus(OnlineStatus.ONLINE)
                .addEventListeners(this)
                .build();
        this.jda.awaitReady();
        log.info("Discord bot connected as " + jda.getSelfUser().getAsTag());
    }

    public void shutdown() {
        if (jda != null) jda.shutdownNow();
    }

    public JDA getJda() { return jda; }

    @Override
    public void onReady(@NotNull ReadyEvent event) {
        log.info("Discord bot is ready; guilds=" + event.getJDA().getGuilds().size());
    }

    @Override
    public void onMessageReceived(@NotNull MessageReceivedEvent event) {
        if (event.getAuthor().isBot()) return;
        String content = event.getMessage().getContentRaw().trim();
        if (content.startsWith("=link ")) {
            String code = content.substring("=link ".length()).trim();
            boolean ok = linkingService.consumeCodeAndLink(code, event.getAuthor().getId());
            if (ok) {
                event.getMessage().reply("Linked successfully. You can now join the queue voice channel.").queue();
            } else {
                event.getMessage().reply("Invalid or expired code. Use /link in-game to get a fresh code.").queue();
            }
            return;
        }
        if (content.startsWith("=party ") && queueManager != null) {
            queueManager.handlePartyCommand(event);
        }
    }

    @Override
    public void onGuildVoiceUpdate(@NotNull GuildVoiceUpdateEvent event) {
        // User joined a voice channel
        AudioChannel joined = event.getChannelJoined();
        if (joined instanceof VoiceChannel) {
            String queueId = config.getQueueVoiceId();
            if (!queueId.isEmpty() && joined.getId().equals(queueId)) {
                Member m = event.getMember();
                handleQueueJoin(m, (VoiceChannel) joined);
            }
        }
    }

    private void handleQueueJoin(Member member, VoiceChannel joined) {
        if (queueManager == null) return;
        // Check link
        UUID uuid = linkingService.getLinkedUuid(member.getId());
        if (uuid == null) {
            moveToWaiting(member, "You must link your Minecraft account first. Use /bwlink in-game and !link <code> here.");
            return;
        }
        // Check online status
        org.bukkit.entity.Player p = Bukkit.getPlayer(uuid);
        if (p == null || !p.isOnline()) {
            moveToWaiting(member, "You are not online on the Minecraft server. Join the server, then rejoin the queue.");
            return;
        }
        queueManager.enqueue(member, uuid, p.getName(), joined.getGuild());
    }

    public void moveToWaiting(Member member, String reason) {
        String waitingId = config.getWaitingVoiceId();
        if (waitingId == null || waitingId.isEmpty()) return;
        VoiceChannel waiting = member.getGuild().getVoiceChannelById(waitingId);
        if (waiting == null) return;
        try {
            member.getGuild().moveVoiceMember(member, waiting).reason("BWQueue: " + reason).queue();
        } catch (Exception e) {
            log.warning("Failed to move member to waiting: " + e.getMessage());
        }
        try {
            member.getUser().openPrivateChannel().queue(pc -> pc.sendMessage(reason).queue(), err -> {});
        } catch (Exception ignored) {}
    }

    public Category getGamesCategory(net.dv8tion.jda.api.entities.Guild guild) {
        String catId = config.getGamesCategoryId();
        if (catId != null && !catId.isEmpty()) {
            Category c = guild.getCategoryById(catId);
            if (c != null) return c;
        }
        return null;
    }
}
