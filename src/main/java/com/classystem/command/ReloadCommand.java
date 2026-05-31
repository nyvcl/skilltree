package com.classystem.command;

import com.classystem.ClassSystemPlugin;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

public class ReloadCommand implements CommandExecutor {

    private final ClassSystemPlugin plugin;

    public ReloadCommand(ClassSystemPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!sender.hasPermission("classsystem.reload")) {
            sender.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        try {
            plugin.reloadConfig();
            plugin.getClassRegistry().reload(plugin);
            sender.sendMessage("§aClassSystem config reloaded successfully.");
        } catch (Exception e) {
            sender.sendMessage("§cReload failed: " + e.getMessage());
            plugin.getLogger().severe("Reload failed: " + e.getMessage());
        }
        return true;
    }
}
