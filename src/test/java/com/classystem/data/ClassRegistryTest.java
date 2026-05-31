package com.classystem.data;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ClassRegistryTest {

    @Test
    void configDefinesFourBaseClasses() throws Exception {
        String config = Files.readString(Path.of("src/main/resources/config.yml"));

        assertTrue(config.contains("  melee:"), "melee class must exist");
        assertTrue(config.contains("  ranged:"), "ranged class must exist");
        assertTrue(config.contains("  magic:"), "magic class must exist");
        assertTrue(config.contains("  summoner:"), "summoner class must exist");
    }

    @Test
    void configDefinesGeneralRankStyleUpgrades() throws Exception {
        String config = Files.readString(Path.of("src/main/resources/config.yml"));

        assertTrue(config.contains("general-upgrades:"), "general upgrades must exist");
        assertTrue(config.contains("upg_extra_hearts"), "extra hearts upgrade must exist");
        assertTrue(config.contains("upg_fire"), "fire resistance upgrade must exist");
    }

    @Test
    void everyBaseClassDefinesATreeAndTrunkMilestone() throws Exception {
        String config = Files.readString(Path.of("src/main/resources/config.yml"));

        for (String classId : new String[] {"melee", "ranged", "magic", "summoner"}) {
            int classStart = config.indexOf("  " + classId + ":");
            assertTrue(classStart >= 0, classId + " class must exist");

            int treeStart = config.indexOf("    tree:", classStart);
            assertTrue(treeStart > classStart, classId + " must define a tree");

            int nextClassStart = nextClassStart(config, classStart + 1);
            String classBlock = config.substring(classStart, nextClassStart);
            assertTrue(classBlock.contains("      trunk-milestone:"), classId + " must define a trunk milestone");
            assertTrue(classBlock.contains("      branch-a:"), classId + " must define branch-a");
            assertTrue(classBlock.contains("      branch-b:"), classId + " must define branch-b");
            assertTrue(classBlock.contains("        milestone:"), classId + " branches must define milestones");
        }
    }

    private int nextClassStart(String config, int fromIndex) {
        int next = config.length();
        for (String classId : new String[] {"melee", "ranged", "magic", "summoner"}) {
            int idx = config.indexOf("  " + classId + ":", fromIndex);
            if (idx >= 0 && idx < next) {
                next = idx;
            }
        }
        return next;
    }
}
