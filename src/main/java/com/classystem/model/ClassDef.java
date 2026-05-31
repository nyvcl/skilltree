package com.classystem.model;

import org.bukkit.Material;

import java.util.List;

public record ClassDef(
        String id,
        String displayName,
        String lpGroup,
        Material starterItem,
        String starterItemName,
        Material extraStarter,    // nullable
        int extraStarterAmount,
        List<String> lore,
        SkillTree tree
) {}
