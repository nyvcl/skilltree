package com.classystem.model;

import org.bukkit.Material;

import java.util.List;

public record MilestoneNode(
        int slot,
        String displayName,
        List<String> lore,
        Material item,
        double moneyCost,
        String lpGroup
) {}
