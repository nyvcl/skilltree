package com.classystem.gui;

import com.classystem.data.ClassRegistry;
import com.classystem.data.PlayerDataManager;
import com.classystem.lp.LuckPermsHook;
import com.classystem.lp.VaultHook;
import com.classystem.model.*;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Handles all click logic for the skill tree GUI.
 * Translates a raw Bukkit click slot into a virtual tree slot, then resolves
 * which node was clicked and applies purchase / switch logic.
 *
 * All methods are called from the main thread (InventoryClickEvent).
 */
public class SkillTreeClickHandler {

    private final ClassRegistry registry;
    private final PlayerDataManager pdm;
    private final LuckPermsHook lp;
    private final VaultHook vault;
    private final SkillTreeGui gui;

    public SkillTreeClickHandler(ClassRegistry registry, PlayerDataManager pdm,
                                  LuckPermsHook lp, VaultHook vault, SkillTreeGui gui) {
        this.registry = registry;
        this.pdm = pdm;
        this.lp = lp;
        this.vault = vault;
        this.gui = gui;
    }

    /**
     * Entry point. rawSlot is 0-indexed Bukkit slot.
     */
    public void handle(Player player, int rawSlot) {
        int scroll = pdm.getScroll(player);

        // ---- Scroll arrows (0-indexed: 8 = up, 53 = down) ----
        if (rawSlot == 8) {
            if (scroll > 0) {
                pdm.setScroll(player, scroll - 1);
                gui.open(player);
            }
            return;
        }
        if (rawSlot == 53) {
            if (scroll < 4) {
                pdm.setScroll(player, scroll + 1);
                gui.open(player);
            }
            return;
        }

        // ---- Convert raw (0-indexed) to display slot (1-indexed) ----
        int displaySlot = rawSlot + 1;

        // ---- General upgrades: virtual row >= 7 ----
        int displayRow = (displaySlot - 1) / 9 + 1;
        int vrow = displayRow + scroll - 2;
        if (vrow >= 7) {
            int vcol = (displaySlot - 1) % 9; // 0-indexed column
            if (vcol >= 1 && vcol <= 7) { // columns 2-8 (0-indexed 1-7)
                int genIdx = (vrow - 7) * 7 + (vcol - 1);
                List<GeneralUpgrade> genUpgs = registry.getGeneralUpgrades();
                if (genIdx >= 0 && genIdx < genUpgs.size()) {
                    handleGenUpgrade(player, genUpgs.get(genIdx));
                    gui.open(player);
                }
            }
            return;
        }

        // ---- Convert display slot back to virtual slot ----
        int virtualSlot = displaySlot + (scroll * 9);
        if (virtualSlot < 1 || virtualSlot > 54) return;

        String classId = pdm.getClass(player);
        ClassDef classDef = registry.getClass(classId);
        if (classDef == null) return;

        SkillTree tree = classDef.tree();
        List<Integer> purchasedPanes = pdm.getTreePanes(player);
        List<Integer> purchasedItems = pdm.getTreeItems(player);
        boolean trunkUnlocked = purchasedItems.contains(tree.trunkMilestone().slot());

        // ---- Trunk panes ----
        List<UpgradeNode> trunkUpgrades = tree.trunkUpgrades();
        for (int i = 0; i < trunkUpgrades.size(); i++) {
            UpgradeNode upg = trunkUpgrades.get(i);
            if (upg.slot() == virtualSlot) {
                Integer prevSlot = (i > 0) ? trunkUpgrades.get(i - 1).slot() : null;
                buyPane(player, upg, prevSlot, purchasedPanes, purchasedItems);
                gui.open(player);
                return;
            }
        }

        // ---- Trunk milestone ----
        MilestoneNode tm = tree.trunkMilestone();
        if (tm.slot() == virtualSlot) {
            boolean gate = countPurchased(trunkUpgrades, purchasedPanes) >= trunkUpgrades.size();
            buyMilestone(player, tm.slot(), tm.displayName(), tm.moneyCost(), tm.lpGroup(),
                    gate, purchasedItems);
            gui.open(player);
            return;
        }

        // ---- Branches (only if trunk unlocked) ----
        if (!trunkUnlocked) return;

        for (BranchDef branch : List.of(tree.branchA(), tree.branchB())) {
            if (branch == null) continue;
            String branchKey = (branch == tree.branchA()) ? "branch_a" : "branch_b";
            if (handleBranchClick(player, virtualSlot, branch, branchKey, tree, purchasedPanes, purchasedItems)) {
                gui.open(player);
                return;
            }
        }
    }

    private boolean branchOwnsSlot(BranchDef branch, int slot) {
        // Branch pre-milestone upgrades
        for (UpgradeNode upg : branch.upgrades()) {
            if (upg.slot() == slot) return true;
        }
        // Branch milestone
        if (branch.milestone().slot() == slot) return true;
        // Branch PSU
        if (branch.preSubclassUpgrade() != null && branch.preSubclassUpgrade().slot() == slot) return true;
    
        // Subclass slots
        for (SubclassDef sub : List.of(branch.subclassA(), branch.subclassB())) {
            if (sub == null) continue;
            if (sub.milestoneSlot() == slot) return true;
            if (sub.preSubclassUpgrade() != null && sub.preSubclassUpgrade().slot() == slot) return true;
            for (UpgradeNode upg : sub.upgrades()) {
                if (upg.slot() == slot) return true;
            }
        }
        return false;
    }

    static boolean isLockedOutOfBranch(String lockedSide, String branchKey) {
        return lockedSide != null && !lockedSide.isEmpty() && !lockedSide.equals(branchKey);
    }

    private boolean handleBranchClick(Player player, int virtualSlot, BranchDef branch,
                                        String branchKey, SkillTree tree,
                                        List<Integer> purchasedPanes,
                                        List<Integer> purchasedItems) {

        if (!branchOwnsSlot(branch, virtualSlot)) return false;

        // Branch pane upgrades — always purchasable regardless of branch lock
        List<UpgradeNode> branchUpgrades = branch.upgrades();
        for (int i = 0; i < branchUpgrades.size(); i++) {
            UpgradeNode upg = branchUpgrades.get(i);
            if (upg.slot() == virtualSlot) {
                Integer prevSlot = (i > 0) ? branchUpgrades.get(i - 1).slot() : null;
                buyPane(player, upg, prevSlot, purchasedPanes, purchasedItems);
                return true;
            }
        }
        
        // Hard lock only applies from the milestone onwards
        String lockedSide = pdm.getBranchSide(player);
        if (isLockedOutOfBranch(lockedSide, branchKey)) {
            player.sendMessage("§cYou have committed to the other branch. Reset your class to switch.");
            return true;
        }

        // Branch milestone
        MilestoneNode bm = branch.milestone();
        if (bm.slot() == virtualSlot) {
            boolean gate = countPurchased(branchUpgrades, purchasedPanes) >= branchUpgrades.size();
            boolean alreadyBought = purchasedItems.contains(bm.slot());
            boolean bought = buyMilestone(player, bm.slot(), bm.displayName(), bm.moneyCost(), bm.lpGroup(),
                    gate, purchasedItems);
            if (bought && !alreadyBought) {
                pdm.setBranchSide(player, branchKey);
            }
            return true;
        }

        boolean branchUnlocked = purchasedItems.contains(bm.slot());
        if (!branchUnlocked) return false;

        // Branch PSU (slot 21 / 25)
        if (branch.preSubclassUpgrade() != null) {
            UpgradeNode psu = branch.preSubclassUpgrade();
            if (psu.slot() == virtualSlot) {
                buyPane(player, psu, bm.slot(), purchasedPanes, purchasedItems);
                return true;
            }
        }

        boolean branchPsuDone = gui.isBranchPsuDone(branch, purchasedPanes);

        // Subclasses
        for (SubclassDef sub : List.of(branch.subclassA(), branch.subclassB())) {
            if (sub == null) continue;
            if (handleSubclassClick(player, virtualSlot, sub, branch, branchPsuDone,
                    purchasedPanes, purchasedItems)) {
                return true;
            }
        }

        return false;
    }

    private boolean handleSubclassClick(Player player, int virtualSlot, SubclassDef sub,
                                         BranchDef branch, boolean branchPsuDone,
                                         List<Integer> purchasedPanes, List<Integer> purchasedItems) {
        // Sub-level PSU (slot 12 / 16)
        if (sub.preSubclassUpgrade() != null) {
            UpgradeNode subPsu = sub.preSubclassUpgrade();
            if (subPsu.slot() == virtualSlot) {
                if (!branchPsuDone) {
                    player.sendMessage("§cYou must purchase the branch upgrade first.");
                    return true;
                }
                Integer prevSlot = branch.preSubclassUpgrade() != null
                        ? branch.preSubclassUpgrade().slot() : null;
                buyPane(player, subPsu, prevSlot, purchasedPanes, purchasedItems);
                return true;
            }
        }

        boolean subPsuDone = gui.isSubPsuDone(sub, purchasedPanes);

        // Sub pane upgrades
        List<UpgradeNode> subUpgrades = sub.upgrades();
        for (int i = 0; i < subUpgrades.size(); i++) {
            UpgradeNode upg = subUpgrades.get(i);
            if (upg.slot() == virtualSlot) {
                if (!subPsuDone) {
                    player.sendMessage("§cYou must purchase the subclass upgrade first.");
                    return true;
                }
                Integer prevSlot;
                if (i > 0) {
                    prevSlot = subUpgrades.get(i - 1).slot();
                } else {
                    prevSlot = sub.preSubclassUpgrade() != null
                            ? sub.preSubclassUpgrade().slot() : null;
                }
                buyPane(player, upg, prevSlot, purchasedPanes, purchasedItems);
                return true;
            }
        }

        // Sub milestone
        if (sub.milestoneSlot() == virtualSlot) {
            if (!subPsuDone) {
                player.sendMessage("§cYou must purchase the subclass upgrade first.");
                return true;
            }
            boolean gate = countPurchased(subUpgrades, purchasedPanes) >= subUpgrades.size();
            boolean alreadyBought = purchasedItems.contains(sub.milestoneSlot());

            if (alreadyBought) {
                // Switch active subclass
                pdm.setSubclassSlot(player, sub.milestoneSlot());
                pdm.setSubclass(player, sub.id());
                switchSubclassGroup(player, sub);
                player.sendMessage("§aSwitched to §6" + sub.displayName() + "§a subclass!");
                return true;
            }

            boolean bought = buyMilestone(player, sub.milestoneSlot(), sub.displayName(),
                    sub.milestoneMoneyCost(), sub.lpGroup(), gate, purchasedItems);

            if (bought && !alreadyBought) {
                pdm.setSubclassSlot(player, sub.milestoneSlot());
                pdm.setSubclass(player, sub.id());
                switchSubclassGroup(player, sub);
            }
            return true;
        }

        return false;
    }

    // ---- General upgrades ----

    private void handleGenUpgrade(Player player, GeneralUpgrade upg) {
        List<String> purchasedGen = pdm.getGenUpgrades(player);
        if (purchasedGen.contains(upg.flagId())) {
            player.sendMessage("§cYou already purchased this upgrade.");
            return;
        }
        if (!vault.has(player, upg.moneyCost())) {
            player.sendMessage("§cYou need §6$" + formatCost(upg.moneyCost())
                    + "§c but only have §6$" + formatCost(vault.getBalance(player)) + "§c.");
            return;
        }
        vault.withdraw(player, upg.moneyCost());
        pdm.addGenUpgrade(player, upg.flagId());
        lp.addGroup(player, upg.lpGroup());
        player.sendMessage("§aPurchased: §6" + upg.displayName()
                + " §afor §6$" + formatCost(upg.moneyCost()) + "§a!");
    }

    // ---- Buy helpers ----

    private void buyPane(Player player, UpgradeNode upg, Integer prevSlot,
                          List<Integer> purchasedPanes, List<Integer> purchasedItems) {
        if (purchasedPanes.contains(upg.slot())) {
            player.sendMessage("§cYou already purchased this upgrade.");
            return;
        }
        // Prerequisite check: prev slot must be in panes OR items
        if (prevSlot != null && !purchasedPanes.contains(prevSlot) && !purchasedItems.contains(prevSlot)) {
            player.sendMessage("§cYou must purchase the previous upgrade first.");
            return;
        }
        if (!vault.has(player, upg.moneyCost())) {
            player.sendMessage("§cYou need §6$" + formatCost(upg.moneyCost())
                    + "§c but only have §6$" + formatCost(vault.getBalance(player)) + "§c.");
            return;
        }
        vault.withdraw(player, upg.moneyCost());
        pdm.addTreePane(player, upg.slot());
        player.sendMessage("§aPurchased: §6" + upg.displayName()
                + " §7for §6$" + formatCost(upg.moneyCost()) + "§a!");
    }

    private boolean buyMilestone(Player player, int slot, String displayName,
                               double cost, String lpGroup, boolean gate,
                               List<Integer> purchasedItems) {
        if (purchasedItems.contains(slot)) {
            player.sendMessage("§cYou already purchased this milestone.");
            return false;
        }
        if (!gate) {
            player.sendMessage("§cYou haven't unlocked all required upgrades yet.");
            return false;
        }
        if (cost > 0 && !vault.has(player, cost)) {
            player.sendMessage("§cYou need §6$" + formatCost(cost)
                    + "§c but only have §6$" + formatCost(vault.getBalance(player)) + "§c.");
            return false;
        }
        if (cost > 0) vault.withdraw(player, cost);
        pdm.addTreeItem(player, slot);
        lp.addGroup(player, lpGroup);
        player.sendMessage("§aMilestone unlocked: §6" + displayName + "§a!");
        return true;
    }

    private void switchSubclassGroup(Player player, SubclassDef activeSub) {
        String classId = pdm.getClass(player);
        ClassDef classDef = registry.getClass(classId);
        if (classDef == null) {
            lp.addGroup(player, activeSub.lpGroup());
            return;
        }

        for (BranchDef branch : List.of(classDef.tree().branchA(), classDef.tree().branchB())) {
            if (branch == null) continue;
            for (SubclassDef sub : List.of(branch.subclassA(), branch.subclassB())) {
                if (sub == null) continue;
                if (!sub.id().equals(activeSub.id())) {
                    lp.removeGroup(player, sub.lpGroup());
                }
            }
        }
        lp.addGroup(player, activeSub.lpGroup());
    }

    private int countPurchased(List<UpgradeNode> upgrades, List<Integer> purchasedPanes) {
        int count = 0;
        for (UpgradeNode upg : upgrades) {
            if (purchasedPanes.contains(upg.slot())) count++;
        }
        return count;
    }

    private String formatCost(double cost) {
        if (cost == Math.floor(cost)) return String.valueOf((long) cost);
        return String.valueOf(cost);
    }
}
