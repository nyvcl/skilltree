package com.classystem.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SkillTreeProgressionTest {

    @Test
    void committedBranchRejectsOtherBranchPastMilestone() {
        assertTrue(SkillTreeClickHandler.isLockedOutOfBranch("branch_a", "branch_b"));
    }

    @Test
    void sameBranchIsNotLockedOut() {
        assertFalse(SkillTreeClickHandler.isLockedOutOfBranch("branch_a", "branch_a"));
    }

    @Test
    void uncommittedPlayerIsNotLockedOut() {
        assertFalse(SkillTreeClickHandler.isLockedOutOfBranch("", "branch_a"));
        assertFalse(SkillTreeClickHandler.isLockedOutOfBranch(null, "branch_a"));
    }
}
