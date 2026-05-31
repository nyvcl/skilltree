package com.classystem.model;

import java.util.List;

public record SkillTree(
        List<UpgradeNode> trunkUpgrades,
        MilestoneNode trunkMilestone,
        BranchDef branchA,   // nullable
        BranchDef branchB    // nullable
) {}
