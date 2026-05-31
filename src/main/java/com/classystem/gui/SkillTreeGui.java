package com.classystem.gui;

import com.classystem.data.ClassRegistry;
import com.classystem.data.PlayerDataManager;
import com.classystem.model.*;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * Builds the 54-slot skill tree GUI for a player.
 *
 * Virtual slot layout (1-indexed, matches Denizen original):
 *   Subclass milestones : 2, 4, 6, 8
 *   Branch A upgrades   : 11, 12, 13
 *   Branch B upgrades   : 15, 16, 17
 *   Branch A PSU        : 21
 *   Branch B PSU        : 25
 *   Branch A milestone  : 30
 *   Branch A upgrade    : 31
 *   Trunk milestone     : 32
 *   Branch B upgrade    : 33
 *   Branch B milestone  : 34
 *   Trunk upgrade 2     : 41
 *   Trunk upgrade 1     : 50
 *   Scroll up arrow     : 9  (Bukkit slot 8)
 *   Scroll down arrow   : 54 (Bukkit slot 53)
 *
 * Scroll shifts which virtual rows appear in the 6-row chest.
 * display_slot = virtual_slot - (scroll * 9)
 * Valid display range: 1-54, excluding 9 and 54 (reserved for arrows).
 */
public class SkillTreeGui {

    public static final String TITLE = "§8Skill Tree";

    // Pane materials
    private static final Material LOCKED_PANE   = Material.RED_STAINED_GLASS_PANE;
    private static final Material UNLOCKED_PANE = Material.GREEN_STAINED_GLASS_PANE;
    private static final Material SELECTED_PANE = Material.LIME_STAINED_GLASS_PANE;
    private static final Material FILLER_PANE   = Material.BLACK_STAINED_GLASS_PANE;

    private final ClassRegistry registry;
    private final PlayerDataManager pdm;

    public SkillTreeGui(ClassRegistry registry, PlayerDataManager pdm) {
        this.registry = registry;
        this.pdm = pdm;
    }

    public void open(Player player) {
        String classId = pdm.getClass(player);
        ClassDef classDef = registry.getClass(classId);
        if (classDef == null) return;

        SkillTree tree = classDef.tree();
        List<Integer> purchasedPanes = pdm.getTreePanes(player);
        List<Integer> purchasedItems = pdm.getTreeItems(player);
        int scroll = pdm.getScroll(player);
        int activeSubclassSlot = pdm.getSubclassSlot(player);

        Inventory inv = Bukkit.createInventory(null, 54, TITLE);

        // Fill all slots with black filler
        ItemStack filler = ItemBuilder.filler(FILLER_PANE);
        for (int i = 0; i < 54; i++) inv.setItem(i, filler);

        // ---- Trunk upgrades ----
        for (UpgradeNode upg : tree.trunkUpgrades()) {
            setPane(inv, upg, scroll, purchasedPanes);
        }

        // ---- Trunk milestone ----
        MilestoneNode tm = tree.trunkMilestone();
        boolean trunkUnlocked = purchasedItems.contains(tm.slot());
        {
            List<String> lore = buildMilestoneLore(tm.lore(), trunkUnlocked,
                    countPurchased(tree.trunkUpgrades(), purchasedPanes),
                    tree.trunkUpgrades().size(), false, tm.moneyCost());
            setSlot(inv, tm.slot(), scroll,
                    ItemBuilder.build(tm.item(), tm.displayName(), lore));
        }

        // ---- Starter weapon (virtual slot 59) ----
        int starterDisplaySlot = 59 - (scroll * 9);
        if (isValidDisplaySlot(starterDisplaySlot)) {
            ItemStack starterItem = ItemBuilder.build(
                    classDef.starterItem(),
                    classDef.starterItemName(),
                    List.of("§eStarter Weapon§f - Given on class selection")
            );
            inv.setItem(starterDisplaySlot - 1, starterItem); // convert to 0-indexed
        }

        // ---- Branches (only if trunk unlocked) ----
        if (trunkUnlocked) {
            String lockedBranchSide = pdm.getBranchSide(player);

            // Update renderBranch call signature to accept it:
            renderBranch(inv, tree.branchA(), "branch_a", scroll, purchasedPanes, purchasedItems, activeSubclassSlot, lockedBranchSide);
            renderBranch(inv, tree.branchB(), "branch_b", scroll, purchasedPanes, purchasedItems, activeSubclassSlot, lockedBranchSide);
        }

        // ---- General upgrades (virtual rows 7-10) ----
        renderGeneralUpgrades(inv, player, scroll);

        // ---- Scroll arrows ----
        if (scroll > 0) {
            inv.setItem(8, ItemBuilder.arrow("§a▲ Scroll Up", "§7Click to scroll up"));
        }
        if (scroll < 4) {
            inv.setItem(53, ItemBuilder.arrow("§a▼ Scroll Down", "§7Click to scroll down"));
        }

        player.openInventory(inv);
    }

    private void renderBranch(Inventory inv, BranchDef branch, String branchKey,
                                int scroll, List<Integer> purchasedPanes,
                                List<Integer> purchasedItems, int activeSubclassSlot,
                                String lockedBranchSide) { 
        if (branch == null) return;

        boolean isLockedOut = lockedBranchSide != null && !lockedBranchSide.isEmpty()
        && !lockedBranchSide.equals(branchKey);

        if (isLockedOut) {
            // Still render the pre-milestone branch upgrade normally
            for (UpgradeNode upg : branch.upgrades()) {
                setPane(inv, upg, scroll, purchasedPanes);
            }
        
            // Only lock the milestone and everything past it
            MilestoneNode bm = branch.milestone();
            List<String> lore = new ArrayList<>(bm.lore());
            lore.add("§cLocked — you committed to the other branch.");
            setSlot(inv, bm.slot(), scroll,
                    ItemBuilder.build(Material.BARRIER, "§cLocked Branch", lore));
            return;
        }

        // Branch pane upgrades
        for (UpgradeNode upg : branch.upgrades()) {
            setPane(inv, upg, scroll, purchasedPanes);
        }

        // Branch PSU (slot 21 or 25)
        if (branch.preSubclassUpgrade() != null) {
            setPane(inv, branch.preSubclassUpgrade(), scroll, purchasedPanes);
        }

        // Branch milestone
        MilestoneNode bm = branch.milestone();
        boolean branchUnlocked = purchasedItems.contains(bm.slot());
        {
            List<String> lore = buildMilestoneLore(bm.lore(), branchUnlocked,
                    countPurchased(branch.upgrades(), purchasedPanes),
                    branch.upgrades().size(), true, bm.moneyCost());
            setSlot(inv, bm.slot(), scroll, ItemBuilder.build(bm.item(), bm.displayName(), lore));
        }

        if (!branchUnlocked) return;

        // Determine if branch PSU is done
        boolean branchPsuDone = isBranchPsuDone(branch, purchasedPanes);

        // Subclasses
        renderSubclass(inv, branch.subclassA(), branch, branchPsuDone, scroll,
                purchasedPanes, purchasedItems, activeSubclassSlot);
        renderSubclass(inv, branch.subclassB(), branch, branchPsuDone, scroll,
                purchasedPanes, purchasedItems, activeSubclassSlot);
    }

    private void renderSubclass(Inventory inv, SubclassDef sub, BranchDef branch,
                                 boolean branchPsuDone, int scroll,
                                 List<Integer> purchasedPanes, List<Integer> purchasedItems,
                                 int activeSubclassSlot) {
        if (sub == null) return;

        // Sub-level PSU (slot 12 or 16) — only show after branch PSU done
        if (sub.preSubclassUpgrade() != null && branchPsuDone) {
            setPane(inv, sub.preSubclassUpgrade(), scroll, purchasedPanes);
        }

        boolean subPsuDone = isSubPsuDone(sub, purchasedPanes);
        if (!subPsuDone) return;

        // Sub pane upgrades
        for (UpgradeNode upg : sub.upgrades()) {
            setPane(inv, upg, scroll, purchasedPanes);
        }

        // Sub milestone
        boolean subBought = purchasedItems.contains(sub.milestoneSlot());
        List<String> lore = buildSubclassMilestoneLore(
                sub.milestoneLore(), subBought,
                sub.milestoneSlot() == activeSubclassSlot,
                countPurchased(sub.upgrades(), purchasedPanes),
                sub.upgrades().size(),
                sub.milestoneMoneyCost()
        );
        setSlot(inv, sub.milestoneSlot(), scroll,
                ItemBuilder.build(sub.milestoneItem(), sub.milestoneItemName(), lore));
    }

    private void renderGeneralUpgrades(Inventory inv, Player player, int scroll) {
        List<com.classystem.model.GeneralUpgrade> genUpgs = registry.getGeneralUpgrades();
        List<String> purchasedGen = pdm.getGenUpgrades(player);

        // Virtual rows 7-10, columns 2-8 (7 items per row)
        for (int vrow = 7; vrow <= 10; vrow++) {
            int displayRow = vrow - scroll + 2;
            if (displayRow < 1 || displayRow > 6) continue;
            int rowBaseDisplaySlot = (displayRow - 1) * 9; // 0-indexed base
            int genStartIdx = (vrow - 7) * 7;

            for (int col = 0; col < 7; col++) {
                int displaySlot0 = rowBaseDisplaySlot + col + 1; // +1 for col offset (columns 2-8)
                // Skip arrow slots (0-indexed: 8 and 53)
                if (displaySlot0 == 8 || displaySlot0 == 53) continue;
                if (displaySlot0 < 0 || displaySlot0 >= 54) continue;

                int genIdx = genStartIdx + col;
                if (genIdx >= genUpgs.size()) continue;

                com.classystem.model.GeneralUpgrade upg = genUpgs.get(genIdx);
                boolean purchased = purchasedGen.contains(upg.flagId());

                List<String> lore = new ArrayList<>(upg.lore());
                if (purchased) {
                    lore.add("§aPURCHASED");
                } else {
                    lore.add("§eCost: §f$" + formatCost(upg.moneyCost()));
                }

                Material mat = purchased ? UNLOCKED_PANE : LOCKED_PANE;
                inv.setItem(displaySlot0, ItemBuilder.build(mat, upg.displayName(), lore));
            }
        }
    }

    // ---- Slot placement helpers ----

    /**
     * Places a pane upgrade into the inventory, respecting scroll offset.
     */
    private void setPane(Inventory inv, UpgradeNode upg, int scroll, List<Integer> purchasedPanes) {
        boolean purchased = purchasedPanes.contains(upg.slot());
        Material mat = purchased ? UNLOCKED_PANE : LOCKED_PANE;

        List<String> lore = new ArrayList<>(upg.lore() instanceof List ? upg.lore() : List.of(upg.lore().toString()));
        if (!purchased) {
            lore.add("§eCost: §f$" + formatCost(upg.moneyCost()));
        }

        setSlot(inv, upg.slot(), scroll, ItemBuilder.build(mat, upg.displayName(), lore));
    }

    /**
     * Converts a virtual slot (1-indexed) to a display slot considering scroll,
     * then places the item. Skips arrow slots (9 and 54, i.e. 0-indexed 8 and 53).
     */
    private void setSlot(Inventory inv, int virtualSlot, int scroll, ItemStack item) {
        int displaySlot = virtualSlot - (scroll * 9);
        if (!isValidDisplaySlot(displaySlot)) return;
        inv.setItem(displaySlot - 1, item); // convert to 0-indexed
    }

    private boolean isValidDisplaySlot(int displaySlot) {
        if (displaySlot < 1 || displaySlot > 54) return false;
        if (displaySlot == 9 || displaySlot == 54) return false; // reserved for arrows
        return true;
    }

    // ---- Lore builders ----

    private List<String> buildMilestoneLore(List<String> baseLore, boolean purchased,
                                            int panesDone, int panesTotal, boolean isBranchMilestone,
                                            double cost) {
        List<String> lore = new ArrayList<>(baseLore);
        if (purchased) {
            lore.add("§aPURCHASED");
        } else if (panesDone >= panesTotal) {
            lore.add("§eClick to purchase!");
            lore.add("§eCost: §f$" + formatCost(cost));
            if (isBranchMilestone) {
                lore.add("§c⚠ This will lock you out of the other branch permanently.");
            }
        } else {
            lore.add("§cRequires " + (panesTotal - panesDone) + " more upgrade(s).");
        }
        return lore;
    }

    private List<String> buildSubclassMilestoneLore(List<String> baseLore, boolean purchased,
                                                     boolean isActive, int panesDone, int panesTotal,
                                                     double cost) {
        List<String> lore = new ArrayList<>(baseLore);
        if (purchased) {
            lore.add(isActive ? "§a★ ACTIVE SUBCLASS" : "§7Unlocked — click to switch");
        } else if (panesDone >= panesTotal) {
            lore.add("§eClick to purchase!");
            lore.add("§eCost: §f$" + formatCost(cost));
        } else {
            lore.add("§cRequires " + (panesTotal - panesDone) + " more upgrade(s).");
        }
        return lore;
    }

    // ---- Gate helpers ----

    public boolean isBranchPsuDone(BranchDef branch, List<Integer> purchasedPanes) {
        if (branch.preSubclassUpgrade() == null) return true;
        return purchasedPanes.contains(branch.preSubclassUpgrade().slot());
    }

    public boolean isSubPsuDone(SubclassDef sub, List<Integer> purchasedPanes) {
        if (sub.preSubclassUpgrade() == null) return true;
        return purchasedPanes.contains(sub.preSubclassUpgrade().slot());
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
