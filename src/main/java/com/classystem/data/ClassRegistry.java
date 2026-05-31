package com.classystem.data;

import com.classystem.model.*;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public class ClassRegistry {

    private final Map<String, ClassDef> classes = new LinkedHashMap<>();
    private final List<GeneralUpgrade> generalUpgrades = new ArrayList<>();

    public ClassRegistry(JavaPlugin plugin) {
        loadClasses(plugin);
        loadGeneralUpgrades(plugin);
    }

    public void reload(JavaPlugin plugin) {
        classes.clear();
        generalUpgrades.clear();
        loadClasses(plugin);
        loadGeneralUpgrades(plugin);
    }

    private void loadClasses(JavaPlugin plugin) {
        ConfigurationSection classesSection = plugin.getConfig().getConfigurationSection("classes");
        if (classesSection == null) {
            plugin.getLogger().warning("No 'classes' section found in config.yml!");
            return;
        }

        for (String classId : classesSection.getKeys(false)) {
            ConfigurationSection cs = classesSection.getConfigurationSection(classId);
            if (cs == null) continue;
            try {
                ClassDef def = parseClassDef(classId, cs);
                classes.put(classId, def);
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to load class '" + classId + "': " + e.getMessage());
            }
        }
        plugin.getLogger().info("Loaded " + classes.size() + " classes.");
    }

    private void loadGeneralUpgrades(JavaPlugin plugin) {
        List<Map<?, ?>> list = plugin.getConfig().getMapList("general-upgrades");
        for (Map<?, ?> map : list) {
            try {
                String flagId = (String) map.get("flag-id");
                String displayName = (String) map.get("display-name");
                List<String> lore = castStringList(map.get("lore"));
                double cost = toDouble(map.get("money-cost"));
                String lpGroup = (String) map.get("lp-group");
                generalUpgrades.add(new GeneralUpgrade(flagId, displayName, lore, cost, lpGroup));
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load a general upgrade: " + e.getMessage());
            }
        }
        plugin.getLogger().info("Loaded " + generalUpgrades.size() + " general upgrades.");
    }

    // ---- Parsers ----

    private ClassDef parseClassDef(String classId, ConfigurationSection cs) {
        String displayName = cs.getString("display-name", classId);
        String lpGroup = cs.getString("lp-group", classId);
        Material starterItem = parseMaterial(cs.getString("starter-item", "STICK"));
        String starterItemName = cs.getString("starter-item-name", displayName);
        String extraStarterStr = cs.getString("extra-starter");
        Material extraStarter = (extraStarterStr == null || extraStarterStr.equalsIgnoreCase("null"))
                ? null : parseMaterial(extraStarterStr);
        int extraStarterAmount = cs.getInt("extra-starter-amount", 0);
        List<String> lore = cs.getStringList("lore");

        ConfigurationSection treeSection = cs.getConfigurationSection("tree");
        if (treeSection == null) {
            throw new IllegalArgumentException("Missing required 'tree' section");
        }
        SkillTree tree = parseSkillTree(treeSection);

        return new ClassDef(classId, displayName, lpGroup, starterItem, starterItemName,
                extraStarter, extraStarterAmount, lore, tree);
    }

    private SkillTree parseSkillTree(ConfigurationSection ts) {
        List<UpgradeNode> trunkUpgrades = parseUpgradeList(ts, "trunk-upgrades");
        MilestoneNode trunkMilestone = parseMilestoneNode(ts.getConfigurationSection("trunk-milestone"));
        if (trunkMilestone == null) {
            throw new IllegalArgumentException("Missing required 'trunk-milestone' section");
        }
        BranchDef branchA = parseBranchDef(ts.getConfigurationSection("branch-a"));
        BranchDef branchB = parseBranchDef(ts.getConfigurationSection("branch-b"));
        return new SkillTree(trunkUpgrades, trunkMilestone, branchA, branchB);
    }

    private List<UpgradeNode> parseUpgradeList(ConfigurationSection parent, String key) {
        List<UpgradeNode> result = new ArrayList<>();
        ConfigurationSection section = parent.getConfigurationSection(key);
        if (section == null) {
            // try as a list of maps
            List<Map<?, ?>> list = parent.getMapList(key);
            for (Map<?, ?> map : list) {
                result.add(parseUpgradeNodeFromMap(map));
            }
            return result;
        }
        for (String slot : section.getKeys(false)) {
            ConfigurationSection ns = section.getConfigurationSection(slot);
            if (ns != null) result.add(parseUpgradeNodeFromSection(ns));
        }
        return result;
    }

    private UpgradeNode parseUpgradeNodeFromMap(Map<?, ?> map) {
        int slot = toInt(map.get("slot"));
        String displayName = (String) map.get("display-name");
        List<String> lore = castStringList(map.get("lore"));
        double cost = toDouble(map.get("money-cost"));
        return new UpgradeNode(slot, displayName, lore, cost);
    }

    private UpgradeNode parseUpgradeNodeFromSection(ConfigurationSection cs) {
        int slot = cs.getInt("slot");
        String displayName = cs.getString("display-name", "Upgrade");
        List<String> lore = cs.getStringList("lore");
        double cost = cs.getDouble("money-cost", 0);
        return new UpgradeNode(slot, displayName, lore, cost);
    }

    private MilestoneNode parseMilestoneNode(ConfigurationSection cs) {
        if (cs == null) return null;
        int slot = cs.getInt("slot");
        String displayName = cs.getString("display-name", "Milestone");
        List<String> lore = cs.getStringList("lore");
        Material item = parseMaterial(cs.getString("item", "NETHER_STAR"));
        double cost = cs.getDouble("money-cost", 0);
        String lpGroup = cs.getString("lp-group", "");
        return new MilestoneNode(slot, displayName, lore, item, cost, lpGroup);
    }

    private BranchDef parseBranchDef(ConfigurationSection cs) {
        if (cs == null) return null;
        List<UpgradeNode> upgrades = parseUpgradeListFromSection(cs, "upgrades");

        UpgradeNode psu = null;
        ConfigurationSection psuSection = cs.getConfigurationSection("pre-subclass-upgrade");
        if (psuSection != null) {
            psu = parseUpgradeNodeFromSection(psuSection);
        }

        MilestoneNode milestone = parseMilestoneNode(cs.getConfigurationSection("milestone"));
        if (milestone == null) {
            throw new IllegalArgumentException("Branch is missing required 'milestone' section");
        }
        SubclassDef subA = parseSubclassDef(cs.getConfigurationSection("subclass-a"));
        SubclassDef subB = parseSubclassDef(cs.getConfigurationSection("subclass-b"));
        return new BranchDef(upgrades, psu, milestone, subA, subB);
    }

    private List<UpgradeNode> parseUpgradeListFromSection(ConfigurationSection parent, String key) {
        List<UpgradeNode> result = new ArrayList<>();
        List<Map<?, ?>> list = parent.getMapList(key);
        if (!list.isEmpty()) {
            for (Map<?, ?> map : list) result.add(parseUpgradeNodeFromMap(map));
            return result;
        }
        ConfigurationSection section = parent.getConfigurationSection(key);
        if (section != null) {
            for (String s : section.getKeys(false)) {
                ConfigurationSection ns = section.getConfigurationSection(s);
                if (ns != null) result.add(parseUpgradeNodeFromSection(ns));
            }
        }
        return result;
    }

    private SubclassDef parseSubclassDef(ConfigurationSection cs) {
        if (cs == null) return null;
        String id = cs.getString("id", "unknown");
        String displayName = cs.getString("display-name", id);
        String lpGroup = cs.getString("lp-group", id);
        int milestoneSlot = cs.getInt("milestone-slot");
        Material milestoneItem = parseMaterial(cs.getString("milestone-item", "NETHER_STAR"));
        String milestoneItemName = cs.getString("milestone-item-name", displayName);
        List<String> milestoneLore = cs.getStringList("milestone-lore");
        double milestoneCost = cs.getDouble("milestone-money-cost", 0);

        UpgradeNode psu = null;
        ConfigurationSection psuSection = cs.getConfigurationSection("pre-subclass-upgrade");
        if (psuSection != null) {
            psu = parseUpgradeNodeFromSection(psuSection);
        }

        List<UpgradeNode> upgrades = parseUpgradeListFromSection(cs, "upgrades");

        return new SubclassDef(id, displayName, lpGroup, milestoneSlot, milestoneItem,
                milestoneItemName, milestoneLore, milestoneCost, psu, upgrades);
    }

    // ---- Utilities ----

    private Material parseMaterial(String name) {
        if (name == null) return Material.STONE;
        Material m = Material.matchMaterial(name.toUpperCase());
        return m != null ? m : Material.STONE;
    }

    @SuppressWarnings("unchecked")
    private List<String> castStringList(Object obj) {
        if (obj instanceof List<?> list) {
            List<String> result = new ArrayList<>();
            for (Object o : list) result.add(String.valueOf(o));
            return result;
        }
        return new ArrayList<>();
    }

    private int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        return Integer.parseInt(String.valueOf(o));
    }

    private double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return Double.parseDouble(String.valueOf(o));
    }

    // ---- Public API ----

    public ClassDef getClass(String classId) {
        return classes.get(classId);
    }

    public Collection<ClassDef> getAllClasses() {
        return classes.values();
    }

    public List<GeneralUpgrade> getGeneralUpgrades() {
        return generalUpgrades;
    }
}
