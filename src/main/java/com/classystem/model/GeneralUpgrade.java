package com.classystem.model;

import java.util.List;

public record GeneralUpgrade(
        String flagId,
        String displayName,
        List<String> lore,
        double moneyCost,
        String lpGroup
) {}
