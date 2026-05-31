package com.classystem.command;

import com.classystem.ClassSystemPlugin;
import com.classystem.data.PlayerDataManager;
import com.classystem.gui.SkillTreeGui;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class SkillTreeCommand implements CommandExecutor {

    private final SkillTreeGui gui;
    private final PlayerDataManager pdm;
    private final ClassSystemPlugin plugin;

    public SkillTreeCommand(SkillTreeGui gui, PlayerDataManager pdm, ClassSystemPlugin plugin) {
        this.gui = gui;
        this.pdm = pdm;
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }

        if (!player.hasPermission("classsystem.skilltree")) {
            player.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        if (!plugin.isWorldAllowed(player)) {
            player.sendMessage("§cThe class system is not active in this world.");
            return true;
        }

        if (!pdm.hasClass(player)) {
            player.sendMessage("§cYou haven't chosen a class yet. Use §e/selectclass§c.");
            return true;
        }
        gui.open(player);
        return true;
    }
}
