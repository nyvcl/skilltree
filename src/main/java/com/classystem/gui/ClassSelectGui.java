package com.classystem.gui;

import com.classystem.data.ClassRegistry;
import com.classystem.data.PlayerDataManager;
import com.classystem.lp.LuckPermsHook;
import com.classystem.lp.VaultHook;
import com.classystem.model.ClassDef;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds and opens the 9-slot class selection GUI.
 * Slot layout: filler, melee, filler, ranged, filler, magic, filler, summoner, filler
 * (1-indexed: classes at slots 2, 4, 6, 8)
 */
public class ClassSelectGui {

    public static final String TITLE = "§8Choose Your Class";

    private final ClassRegistry registry;
    private final PlayerDataManager pdm;
    private final LuckPermsHook lp;
    private final VaultHook vault;
    private final JavaPlugin plugin;

    public ClassSelectGui(ClassRegistry registry, PlayerDataManager pdm, LuckPermsHook lp, VaultHook vault, JavaPlugin plugin) {
        this.registry = registry;
        this.pdm = pdm;
        this.lp = lp;
        this.vault = vault;
        this.plugin = plugin;
    }

    public void open(Player player) {
        Inventory inv = Bukkit.createInventory(null, 9, TITLE);

        ItemStack filler = ItemBuilder.filler(Material.GRAY_STAINED_GLASS_PANE);
        for (int i = 0; i < 9; i++) inv.setItem(i, filler);

        // Place classes at 0-indexed slots 1, 3, 5, 7 (Bukkit inventory is 0-indexed)
        int[] classSlots = {1, 3, 5, 7};
        String[] classOrder = {"melee", "ranged", "magic", "summoner"};

        for (int i = 0; i < classOrder.length; i++) {
            ClassDef def = registry.getClass(classOrder[i]);
            if (def == null) continue;
            ItemStack item = buildClassItem(def);
            inv.setItem(classSlots[i], item);
        }

        player.openInventory(inv);
    }

    private ItemStack buildClassItem(ClassDef def) {
        List<String> lore = new ArrayList<>(def.lore());
        return ItemBuilder.build(def.starterItem(), def.starterItemName(), lore);
    }

    /**
     * Handles a click inside the class select GUI.
     * Returns the chosen class ID, or null if the click was not on a class slot.
     */
    public String handleClick(int rawSlot) {
        // Bukkit raw slots are 0-indexed
        return switch (rawSlot) {
            case 1 -> "melee";
            case 3 -> "ranged";
            case 5 -> "magic";
            case 7 -> "summoner";
            default -> null;
        };
    }

    /**
     * Confirms the class selection: assigns flags, gives starter items, grants LP group.
     */
    public void confirmSelection(Player player, String classId) {
        ClassDef def = registry.getClass(classId);
        if (def == null) return;

        pdm.setClass(player, classId);
        // tree_panes and tree_items start empty — no need to set, getters default to empty list

        lp.addGroup(player, def.lpGroup());

        player.closeInventory();

        // Give starter item — magic class needs the cs_arcane_staff NBT tag for casting
        // Summoner class needs the cs_summon_staff NBT tag for summoning
        ItemStack starter;
        if ("magic".equals(classId)) {
            starter = ItemBuilder.buildStaff(def.starterItemName(), List.of(), plugin);
        } else if ("summoner".equals(classId)) {
            starter = com.classystem.listener.SummonerAbilityListener.createSummonStaff(plugin);
        } else {
            starter = ItemBuilder.build(def.starterItem(), def.starterItemName(), List.of());
        }
        player.getInventory().addItem(starter);

        // Give extra starter (e.g. arrows for ranged)
        if (def.extraStarter() != null) {
            ItemStack extra = new ItemStack(def.extraStarter(), def.extraStarterAmount());
            player.getInventory().addItem(extra);
        }

        player.sendMessage("§aYou chose the §6" + def.displayName() + "§a class!");
        player.sendMessage("§7Use §e/skilltree§7 to view and purchase upgrades.");
    }
}
