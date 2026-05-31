package com.classystem.model;

import java.util.List;

public record BranchDef(
        List<UpgradeNode> upgrades,
        UpgradeNode preSubclassUpgrade,  // nullable
        MilestoneNode milestone,
        SubclassDef subclassA,           // nullable
        SubclassDef subclassB            // nullable
) {}
