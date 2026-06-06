package com.classystem.listener;

import org.bukkit.event.block.Action;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AbilityLogicTest {

    @Test
    void volleyCostsMatchDesign() {
        assertEquals(2, RangedAbilityListener.volleyArrowCost(3));
        assertEquals(3, RangedAbilityListener.volleyArrowCost(5));
    }

    @Test
    void deadeyeBonusUsesStoredShotDistanceAndCapsAtFortyPercent() {
        assertEquals(0.0, RangedAbilityListener.deadeyeBonus(4.99), 0.0001);
        assertEquals(0.05, RangedAbilityListener.deadeyeBonus(5.0), 0.0001);
        assertEquals(0.40, RangedAbilityListener.deadeyeBonus(40.0), 0.0001);
        assertEquals(0.40, RangedAbilityListener.deadeyeBonus(100.0), 0.0001);
    }

    @Test
    void armorPierceUsesFifteenPercentDamageBoost() {
        assertEquals(1.15, RangedAbilityListener.armorPierceMultiplier(), 0.0001);
    }

    @Test
    void magicBasicBoltCostsTenManaAndAwakeningGrantsFortyPercentMana() {
        assertEquals(10.0, MagicAbilityListener.basicBoltManaCost(), 0.0001);
        assertEquals(100.0, MagicAbilityListener.maxManaForAwakening(false), 0.0001);
        assertEquals(140.0, MagicAbilityListener.maxManaForAwakening(true), 0.0001);
    }

    @Test
    void awakeningAuraOnlyRunsForAllowedWorldMagesWithUpgrade() {
        assertTrue(MagicAbilityListener.shouldSpawnAwakeningAura(true, true, true));
        assertFalse(MagicAbilityListener.shouldSpawnAwakeningAura(false, true, true));
        assertFalse(MagicAbilityListener.shouldSpawnAwakeningAura(true, false, true));
        assertFalse(MagicAbilityListener.shouldSpawnAwakeningAura(true, true, false));
    }

    @Test
    void gravityWellOnlyUsesActualBlockRightClick() {
        assertTrue(MagicAbilityListener.shouldRouteGravityWell(Action.RIGHT_CLICK_BLOCK, true));
        assertFalse(MagicAbilityListener.shouldRouteGravityWell(Action.RIGHT_CLICK_AIR, true));
        assertFalse(MagicAbilityListener.shouldRouteGravityWell(Action.RIGHT_CLICK_BLOCK, false));
    }

    @Test
    void spellKillMetadataExpires() {
        assertTrue(MagicAbilityListener.isRecentSpellDamage(1_000L, 4_000L));
        assertFalse(MagicAbilityListener.isRecentSpellDamage(1_000L, 6_500L));
        assertFalse(MagicAbilityListener.isRecentSpellDamage(null, 1_000L));
    }

    @Test
    void soulBondRespawnWaitsUntilScheduledTime() {
        assertFalse(SummonerAbilityListener.isSoulBondRespawnDue(5_000L, 4_999L));
        assertTrue(SummonerAbilityListener.isSoulBondRespawnDue(5_000L, 5_000L));
        assertTrue(SummonerAbilityListener.isSoulBondRespawnDue(null, 5_000L));
    }

    @Test
    void bloodLinkDoesNotRedirectMinionAttackDamageToOwner() {
        assertEquals(0.0, SummonerAbilityListener.bloodLinkOwnerDamageFromMinionAttack(20.0), 0.0001);
    }

    @Test
    void recklessChargeSprintTrackingDoesNotRequireShadowstrike() {
        assertTrue(MeleeAbilityListener.shouldTrackSprint(true, false));
        assertTrue(MeleeAbilityListener.shouldTrackSprint(false, true));
        assertFalse(MeleeAbilityListener.shouldTrackSprint(false, false));
    }

    @Test
    void spellbladeIgnoresDamageCreatedBySpells() {
        assertFalse(MagicAbilityListener.shouldRollSpellbladeProc(true, true, 0.0));
        assertTrue(MagicAbilityListener.shouldRollSpellbladeProc(false, true, 0.24));
        assertFalse(MagicAbilityListener.shouldRollSpellbladeProc(false, true, 0.25));
        assertFalse(MagicAbilityListener.shouldRollSpellbladeProc(false, false, 0.0));
    }
}
