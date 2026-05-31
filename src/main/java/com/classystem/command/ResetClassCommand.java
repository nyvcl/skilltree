package com.classystem.command;

import com.classystem.ClassSystemPlugin;
import com.classystem.data.ClassRegistry;
import com.classystem.data.PlayerDataManager;
import com.classystem.listener.SummonerAbilityListener;
import com.classystem.lp.LuckPermsHook;
import com.classystem.model.*;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.ComponentBuilder;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class ResetClassCommand implements CommandExecutor {

    private final ClassRegistry registry;
    private final PlayerDataManager pdm;
    private final LuckPermsHook lp;
    private final ClassSystemPlugin plugin;
    private final SummonerAbilityListener summonerListener;

    private final Map<UUID, Long> pendingConfirm = new HashMap<>();

    public ResetClassCommand(ClassRegistry registry, PlayerDataManager pdm,
                              LuckPermsHook lp, ClassSystemPlugin plugin,
                              SummonerAbilityListener summonerListener) {
        this.registry = registry;
        this.pdm = pdm;
        this.lp = lp;
        this.plugin = plugin;
        this.summonerListener = summonerListener;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("§cThis command can only be used by players.");
            return true;
        }

        if (!player.hasPermission("classsystem.resetclass")) {
            player.sendMessage("§cYou don't have permission to use this command.");
            return true;
        }

        if (!plugin.isWorldAllowed(player)) {
            player.sendMessage("§cThe class system is not active in this world.");
            return true;
        }

        if (!pdm.hasClass(player)) {
            player.sendMessage("§cYou don't have a class to reset.");
            return true;
        }

        UUID uuid = player.getUniqueId();

        if (args.length > 0 && args[0].equalsIgnoreCase("confirm")) {
            Long timestamp = pendingConfirm.remove(uuid);
            if (timestamp == null || System.currentTimeMillis() - timestamp > 30_000) {
                player.sendMessage("§cConfirmation expired. Run §e/resetclass§c again.");
                return true;
            }
            executeReset(player);
            return true;
        }

        pendingConfirm.put(uuid, System.currentTimeMillis());
        new BukkitRunnable() {
            @Override
            public void run() { pendingConfirm.remove(uuid); }
        }.runTaskLater(plugin, 600L);

        player.sendMessage("§cAre you sure? You will lose all current progress and all benefits.");
        sendConfirmButtons(player);
        return true;
    }

    private void sendConfirmButtons(Player player) {
        TextComponent yes = new TextComponent("  [Yes, reset my class]");
        yes.setColor(net.md_5.bungee.api.ChatColor.GREEN);
        yes.setBold(true);
        yes.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/resetclass confirm"));
        yes.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("§cThis cannot be undone!").create()));

        TextComponent sep = new TextComponent("  /  ");
        sep.setColor(net.md_5.bungee.api.ChatColor.DARK_GRAY);

        TextComponent no = new TextComponent("[Cancel]");
        no.setColor(net.md_5.bungee.api.ChatColor.RED);
        no.setBold(true);
        no.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/resetclass cancel"));
        no.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                new ComponentBuilder("§7Cancel the reset").create()));

        player.spigot().sendMessage(yes, sep, no);
    }

    private void executeReset(Player player) {
        String classId = pdm.getClass(player);

        if ("summoner".equals(classId)) {
            summonerListener.dismissAllMinionsOnReset(player);
        }

        ClassDef classDef = registry.getClass(classId);
        if (classDef == null) {
            pdm.fullReset(player);
            player.sendMessage("§aYour class data has been reset.");
            return;
        }

        SkillTree tree = classDef.tree();
        List<Integer> purchasedItems = pdm.getTreeItems(player);

        // Remove base class LP group
        lp.removeGroup(player, classDef.lpGroup());

        // Remove trunk milestone LP group
        MilestoneNode tm = tree.trunkMilestone();
        if (purchasedItems.contains(tm.slot())) {
            lp.removeGroup(player, tm.lpGroup());
        }

        // Remove branch and subclass LP groups
        for (BranchDef branch : List.of(tree.branchA(), tree.branchB())) {
            if (branch == null) continue;
            MilestoneNode bm = branch.milestone();
            if (purchasedItems.contains(bm.slot())) {
                lp.removeGroup(player, bm.lpGroup());
            }
            for (SubclassDef sub : List.of(branch.subclassA(), branch.subclassB())) {
                if (sub == null) continue;
                if (purchasedItems.contains(sub.milestoneSlot())) {
                    lp.removeGroup(player, sub.lpGroup());
                }
            }
        }

        // Remove general upgrade LP groups
        List<String> purchasedGen = pdm.getGenUpgrades(player);
        for (GeneralUpgrade upg : registry.getGeneralUpgrades()) {
            if (purchasedGen.contains(upg.flagId())) {
                lp.removeGroup(player, upg.lpGroup());
            }
        }

        pdm.fullReset(player);
        player.sendMessage("§aYour class has been reset. Use §e/selectclass§a to choose a new class.");
    }
}
