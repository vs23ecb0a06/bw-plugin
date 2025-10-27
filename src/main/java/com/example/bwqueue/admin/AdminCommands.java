package com.example.bwqueue.admin;

import com.example.bwqueue.BWQueuePlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class AdminCommands implements CommandExecutor {
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("bwqueue.admin")) {
            sender.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage("§e/bwqueue reload §7- Reloads config");
            sender.sendMessage("§e/bwqueue status §7- Shows queue status");
            return true;
        }
        String sub = args[0].toLowerCase();
        switch (sub) {
            case "reload":
                BWQueuePlugin.get().reloadConfig();
                sender.sendMessage("§aConfig reloaded.");
                return true;
            case "status":
                int queued = BWQueuePlugin.get().getQueueManager().getQueuedCount();
                int active = BWQueuePlugin.get().getQueueManager().getActiveGames();
                sender.sendMessage("§eQueued: §b" + queued + " §7| Active games: §b" + active);
                return true;
            default:
                sender.sendMessage("§cUnknown subcommand.");
                return true;
        }
    }
}
