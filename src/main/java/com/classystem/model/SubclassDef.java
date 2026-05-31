package com.classystem.model;

import org.bukkit.Material;

import java.util.List;

public record SubclassDef(
        String id,
        String displayName,
        String lpGroup,
        int milestoneSlot,
        Material milestoneItem,
        String milestoneItemName,
        List<String> milestoneLore,
        double milestoneMoneyCost,
        UpgradeNode preSubclassUpgrade,  // nullable
        List<UpgradeNode> upgrades
) {}
