package com.classystem.model;

import java.util.List;

public record UpgradeNode(
        int slot,
        String displayName,
        List<String> lore,
        double moneyCost
) {}
