package com.classystem.data;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Stores all player-specific class/skill data using the Bukkit PersistentDataContainer API.
 * Data is saved automatically with the player's data file.
 */
public class PlayerDataManager {

    private final NamespacedKey keyClass;
    private final NamespacedKey keyTreePanes;
    private final NamespacedKey keyTreeItems;
    private final NamespacedKey keyGenUpgrades;
    private final NamespacedKey keyScroll;
    private final NamespacedKey keySubclass;
    private final NamespacedKey keySubclassSlot;
    private final NamespacedKey keyBranchSide;
    private final NamespacedKey keyMana;
    private final NamespacedKey keyMaxMana;

    public PlayerDataManager(JavaPlugin plugin) {
        keyClass       = new NamespacedKey(plugin, "class");
        keyTreePanes   = new NamespacedKey(plugin, "tree_panes");
        keyTreeItems   = new NamespacedKey(plugin, "tree_items");
        keyGenUpgrades = new NamespacedKey(plugin, "gen_upgrades");
        keyScroll      = new NamespacedKey(plugin, "scroll");
        keySubclass    = new NamespacedKey(plugin, "subclass");
        keySubclassSlot= new NamespacedKey(plugin, "subclass_slot");
        keyBranchSide  = new NamespacedKey(plugin, "branch_side");
        keyMana        = new NamespacedKey(plugin, "mana");
        keyMaxMana     = new NamespacedKey(plugin, "max_mana");
    }

    // ---- Class ----

    public boolean hasClass(Player player) {
        return player.getPersistentDataContainer().has(keyClass, PersistentDataType.STRING);
    }

    public String getClass(Player player) {
        return player.getPersistentDataContainer().getOrDefault(keyClass, PersistentDataType.STRING, "");
    }

    public void setClass(Player player, String classId) {
        player.getPersistentDataContainer().set(keyClass, PersistentDataType.STRING, classId);
    }

    // ---- Tree panes (csv list of slot ints) ----

    public List<Integer> getTreePanes(Player player) {
        return getIntList(player, keyTreePanes);
    }

    public void addTreePane(Player player, int slot) {
        List<Integer> list = getTreePanes(player);
        if (!list.contains(slot)) {
            list.add(slot);
            setIntList(player, keyTreePanes, list);
        }
    }

    // ---- Tree items (milestone slots) ----

    public List<Integer> getTreeItems(Player player) {
        return getIntList(player, keyTreeItems);
    }

    public void addTreeItem(Player player, int slot) {
        List<Integer> list = getTreeItems(player);
        if (!list.contains(slot)) {
            list.add(slot);
            setIntList(player, keyTreeItems, list);
        }
    }

    // ---- General upgrades (csv list of flag ids) ----

    public List<String> getGenUpgrades(Player player) {
        return getStringList(player, keyGenUpgrades);
    }

    public void addGenUpgrade(Player player, String flagId) {
        List<String> list = getGenUpgrades(player);
        if (!list.contains(flagId)) {
            list.add(flagId);
            setStringList(player, keyGenUpgrades, list);
        }
    }

    // ---- Scroll position ----

    public int getScroll(Player player) {
        return player.getPersistentDataContainer().getOrDefault(keyScroll, PersistentDataType.INTEGER, 0);
    }

    public void setScroll(Player player, int scroll) {
        player.getPersistentDataContainer().set(keyScroll, PersistentDataType.INTEGER, scroll);
    }

    // ---- Subclass ----

    public String getSubclass(Player player) {
        return player.getPersistentDataContainer().getOrDefault(keySubclass, PersistentDataType.STRING, "");
    }

    public void setSubclass(Player player, String subclassId) {
        player.getPersistentDataContainer().set(keySubclass, PersistentDataType.STRING, subclassId);
    }

    public int getSubclassSlot(Player player) {
        return player.getPersistentDataContainer().getOrDefault(keySubclassSlot, PersistentDataType.INTEGER, -1);
    }

    public void setSubclassSlot(Player player, int slot) {
        player.getPersistentDataContainer().set(keySubclassSlot, PersistentDataType.INTEGER, slot);
    }

    // ---- Branch side ----

    public String getBranchSide(Player player) {
        return player.getPersistentDataContainer().getOrDefault(keyBranchSide, PersistentDataType.STRING, "");
    }

    public void setBranchSide(Player player, String side) {
        player.getPersistentDataContainer().set(keyBranchSide, PersistentDataType.STRING, side);
    }

    // ---- Mana ----

    public double getMana(Player player) {
        String raw = player.getPersistentDataContainer().getOrDefault(keyMana, PersistentDataType.STRING, "100.0");
        try { return Double.parseDouble(raw); } catch (NumberFormatException e) { return 100.0; }
    }

    public void setMana(Player player, double mana) {
        player.getPersistentDataContainer().set(keyMana, PersistentDataType.STRING, String.valueOf(mana));
    }

    public double getMaxMana(Player player) {
        String raw = player.getPersistentDataContainer().getOrDefault(keyMaxMana, PersistentDataType.STRING, "100.0");
        try { return Double.parseDouble(raw); } catch (NumberFormatException e) { return 100.0; }
    }

    public void setMaxMana(Player player, double maxMana) {
        player.getPersistentDataContainer().set(keyMaxMana, PersistentDataType.STRING, String.valueOf(maxMana));
    }

    // ---- Full reset ----

    public void fullReset(Player player) {
        PersistentDataContainer pdc = player.getPersistentDataContainer();
        pdc.remove(keyClass);
        pdc.remove(keyTreePanes);
        pdc.remove(keyTreeItems);
        pdc.remove(keyGenUpgrades);
        pdc.remove(keyScroll);
        pdc.remove(keySubclass);
        pdc.remove(keySubclassSlot);
        pdc.remove(keyBranchSide);
        pdc.remove(keyMana);
        pdc.remove(keyMaxMana);
    }

    // ---- Helpers ----

    private List<Integer> getIntList(Player player, NamespacedKey key) {
        String raw = player.getPersistentDataContainer().getOrDefault(key, PersistentDataType.STRING, "");
        if (raw.isEmpty()) return new ArrayList<>();
        try {
            return new ArrayList<>(Arrays.stream(raw.split(","))
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .map(Integer::parseInt)
                    .collect(Collectors.toList()));
        } catch (NumberFormatException e) {
            return new ArrayList<>();
        }
    }

    private void setIntList(Player player, NamespacedKey key, List<Integer> list) {
        player.getPersistentDataContainer().set(key, PersistentDataType.STRING,
                list.stream().map(String::valueOf).collect(Collectors.joining(",")));
    }

    private List<String> getStringList(Player player, NamespacedKey key) {
        String raw = player.getPersistentDataContainer().getOrDefault(key, PersistentDataType.STRING, "");
        if (raw.isEmpty()) return new ArrayList<>();
        return new ArrayList<>(Arrays.asList(raw.split(",")));
    }

    private void setStringList(Player player, NamespacedKey key, List<String> list) {
        player.getPersistentDataContainer().set(key, PersistentDataType.STRING,
                String.join(",", list));
    }
}
