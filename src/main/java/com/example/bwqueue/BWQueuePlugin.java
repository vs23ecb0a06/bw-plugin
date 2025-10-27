package com.example.bwqueue;

import com.example.bwqueue.config.PluginConfig;
import com.example.bwqueue.db.Database;
import com.example.bwqueue.discord.DiscordBot;
import com.example.bwqueue.link.LinkingService;
import com.example.bwqueue.queue.QueueManager;
import com.example.bwqueue.party.PartyService;
import com.example.bwqueue.bw.BedWarsService;
import com.example.bwqueue.session.SessionRegistry;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.logging.Logger;

public class BWQueuePlugin extends JavaPlugin {

    private static BWQueuePlugin instance;
    private Logger log;

    private PluginConfig configModel;
    private Database database;
    private DiscordBot discordBot;
    private LinkingService linkingService;
    private QueueManager queueManager;

    public static BWQueuePlugin get() { return instance; }

    @Override
    public void onEnable() {
        instance = this;
        this.log = getLogger();
        saveDefaultConfig();
        reloadConfig();
        this.configModel = new PluginConfig(getConfig());

        try {
            this.database = new Database(configModel);
            this.database.init();
        } catch (Exception e) {
            getLogger().severe("Failed to initialize database: " + e.getMessage());
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        this.linkingService = new LinkingService(database, configModel);
        this.discordBot = new DiscordBot(configModel, linkingService);
        try {
            this.discordBot.start();
        } catch (Exception e) {
            getLogger().severe("Failed to start Discord bot: " + e.getMessage());
            e.printStackTrace();
            // Don't hard fail server, but most features won't work
        }

        this.queueManager = new QueueManager(configModel, discordBot, database, linkingService);
        this.discordBot.setQueueManager(queueManager);

        // Register commands
        PluginCommand linkCmd = getCommand("link");
        if (linkCmd != null) {
            linkCmd.setExecutor((sender, command, label, args) -> {
                if (!(sender instanceof org.bukkit.entity.Player)) {
                    sender.sendMessage("Only players can use this command.");
                    return true;
                }
                org.bukkit.entity.Player p = (org.bukkit.entity.Player) sender;
                if (!p.hasPermission("bwqueue.link")) {
                    p.sendMessage("§cYou don't have permission.");
                    return true;
                }
                if (configModel.isRequireOnlineForCode() && !p.isOnline()) {
                    p.sendMessage("§cYou must be online to link.");
                    return true;
                }
                String code = linkingService.generateCode(p.getUniqueId(), p.getName());
                p.sendMessage("§aUse this code in Discord: §e=link " + code + " §7(in any server channel where the bot can read).");
                return true;
            });
        }
        PluginCommand adminCmd = getCommand("bwqueue");
        if (adminCmd != null) {
            adminCmd.setExecutor(new com.example.bwqueue.admin.AdminCommands());
        }

        // Hook into BW1058 events (optional now). We'll register listeners in a dedicated class later when needed.
        // getServer().getPluginManager().registerEvents(new BWListeners(database, queueManager), this);

        log.info("BWQueue enabled.");
    }

    @Override
    public void onDisable() {
        try {
            if (discordBot != null) discordBot.shutdown();
        } catch (Exception ignored) {}
        try {
            if (database != null) database.shutdown();
        } catch (Exception ignored) {}
        instance = null;
        log.info("BWQueue disabled.");
    }

    public PluginConfig getConfigModel() { return configModel; }
    public Database getDatabase() { return database; }
    public DiscordBot getDiscordBot() { return discordBot; }
    public LinkingService getLinkingService() { return linkingService; }
    public QueueManager getQueueManager() { return queueManager; }
}
