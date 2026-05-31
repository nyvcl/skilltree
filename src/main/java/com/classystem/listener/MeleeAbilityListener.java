package com.classystem.listener;

import com.classystem.data.PlayerDataManager;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import com.classystem.ClassSystemPlugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Implements all melee class upgrade gameplay effects.
 *
 * Slot map (matches config.yml):
 *   50  Hardened Strikes       41  Combat Rhythm
 *   32  Warrior's Resolve (milestone)
 *   33  Bloodlust              34  War Cry (branch-a milestone)
 *   25  Reckless Charge        16  Frenzy State
 *   15  Juggernaut              6  Warlord (subclass milestone)
 *   17  Blood Price             8  Hexblade (subclass milestone)
 *   31  Riposte                30  Master's Form (branch-b milestone)
 *   21  Exploit Weakness       12  Footwork
 *   11  Sentinel                2  Ironclad (subclass milestone)
 *   13  Phantom Step            4  Shadowstrike (subclass milestone)
 *
 * FIX LOG:
 *   v2 — Fixed ENTITY_PLAYER_SPRINT → ENTITY_PLAYER_ATTACK_SWEEP
 *   v3 — Fix 1: Slot constants updated for new tree structure
 *        Fix 2: Blood Price now checks subclass (Hexblade) before War Cry
 *               so shift+right-click resolves correctly per branch
 *        Fix 3: Riposte rewritten — detects isBlocking() at moment of hit
 *               rather than relying on a cancelled event window
 */
public class MeleeAbilityListener implements Listener {

    // ── Cooldown / state maps ────────────────────────────────────────────────
    private final Map<UUID, Long>    warCryCooldown       = new HashMap<>();
    private final Map<UUID, Long>    footworkCooldown     = new HashMap<>();
    private final Map<UUID, Long>    bloodPriceCooldown   = new HashMap<>();
    private final Map<UUID, Long>    unstoppableCooldown  = new HashMap<>();
    private final Map<UUID, Boolean> bloodPriceActive     = new HashMap<>();
    private final Map<UUID, Boolean> deathPactUsed        = new HashMap<>();

    // Riposte: timestamp of when the player last raised their shield.
    // A riposte only triggers if the shield was raised within a short window before the hit.
    private final Map<UUID, Long>   shieldRaisedAt       = new HashMap<>();

    // Exploit Weakness: consecutive hit counter per target
    private final Map<UUID, Map<UUID, Integer>> hitStreaks = new HashMap<>();
    private final Map<UUID, UUID>   lastTarget            = new HashMap<>();

    // Frenzy State: kill stack tracking
    private final Map<UUID, Long>    lastKillTime         = new HashMap<>();
    private final Map<UUID, Integer> frenzyStacks         = new HashMap<>();

    // Ghost Strike: sprint duration tracking
    private final Map<UUID, Long>    sprintStartTime      = new HashMap<>();
    private final Map<UUID, Boolean> ghostStrikeReady     = new HashMap<>();
    private final Map<UUID, Boolean> recklessChargeReady  = new HashMap<>();

    // Immovable Object: last movement timestamp
    private final Map<UUID, Long>    lastMoveTime         = new HashMap<>();

    private final PlayerDataManager pdm;
    private final ClassSystemPlugin plugin;

    // ── Slot constants ───────────────────────────────────────────────────────
    private static final int SLOT_HARDENED_STRIKES  = 50;
    private static final int SLOT_COMBAT_RHYTHM     = 41;
    private static final int SLOT_RESOLVE_MILESTONE = 32; // trunk milestone
    private static final int SLOT_BLOODLUST         = 33;
    private static final int SLOT_WAR_CRY           = 34; // branch-a milestone
    private static final int SLOT_RECKLESS_CHARGE   = 25;
    private static final int SLOT_FRENZY_MILESTONE  = 16;
    private static final int SLOT_JUGGERNAUT        = 15;
    private static final int SLOT_WARLORD_SUB       =  6; // subclass milestone
    private static final int SLOT_BLOOD_PRICE       = 17;
    private static final int SLOT_HEXBLADE_SUB      =  8; // subclass milestone
    private static final int SLOT_RIPOSTE           = 31;
    private static final int SLOT_MASTERS_MILESTONE = 30; // branch-b milestone
    private static final int SLOT_EXPLOIT_WEAKNESS  = 21;
    private static final int SLOT_FOOTWORK          = 12;
    private static final int SLOT_SENTINEL          = 11;
    private static final int SLOT_IRONCLAD_SUB      =  2; // subclass milestone
    private static final int SLOT_PHANTOM_STEP      = 13;
    private static final int SLOT_SHADOWSTRIKE_SUB  =  4; // subclass milestone

    // ── Cooldown durations ───────────────────────────────────────────────────
    private static final long COOLDOWN_WAR_CRY      = 60_000L;
    private static final long COOLDOWN_FOOTWORK     =  8_000L;
    private static final long COOLDOWN_BLOOD_PRICE  = 15_000L;
    private static final long COOLDOWN_UNSTOPPABLE  = 30_000L;
    private static final long FRENZY_DECAY_MS       = 10_000L;
    private static final long GHOST_SPRINT_REQUIRED =  2_000L;

    public MeleeAbilityListener(PlayerDataManager pdm, ClassSystemPlugin plugin) {
        this.pdm    = pdm;
        this.plugin = plugin;
        startPeriodicTasks();
    }

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────
    private boolean hasUpgrade(Player p, int slot) {
        return pdm.getTreePanes(p).contains(slot) || pdm.getTreeItems(p).contains(slot);
    }

    private boolean isMelee(Player p) {
        return "melee".equals(pdm.getClass(p));
    }

    private boolean holdingSword(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null) return false;
        String type = hand.getType().name();
        return type.endsWith("_SWORD") || type.endsWith("_AXE");
    }

    private long now() { return System.currentTimeMillis(); }

    private boolean onCooldown(Map<UUID, Long> map, UUID id, long ms) {
        Long last = map.get(id);
        return last != null && (now() - last) < ms;
    }

    static boolean shouldTrackSprint(boolean hasRecklessCharge, boolean hasShadowstrike) {
        return hasRecklessCharge || hasShadowstrike;
    }

    private void sendCooldownMsg(Player p, String ability, long remainingMs) {
        long secs = (remainingMs / 1000) + 1;
        p.sendMessage("§c" + ability + " is on cooldown for " + secs + "s.");
    }

    // ────────────────────────────────────────────────────────────────────────
    // FIX 2: Active ability trigger — Blood Price checked BEFORE War Cry.
    // Each active is gated on the correct subclass/upgrade so players who
    // haven't unlocked War Cry won't accidentally trigger it, and Hexblade
    // players who have Blood Price will always get Blood Price on this input.
    // ────────────────────────────────────────────────────────────────────────
    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!isMelee(p))      return;
        if (!plugin.isWorldAllowed(p)) return;
        if (!holdingSword(p)) return;
        if (!p.isSneaking())  return;

        org.bukkit.event.block.Action action = e.getAction();
        if (action != org.bukkit.event.block.Action.RIGHT_CLICK_AIR &&
            action != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;

        e.setCancelled(true);

        // ── Blood Price (Hexblade subclass, slot 17) — checked FIRST ────────
        // Only fires if player is on the Hexblade path (has Blood Price upgrade)
        if (hasUpgrade(p, SLOT_BLOOD_PRICE)) {
            long remaining = COOLDOWN_BLOOD_PRICE - (now() - bloodPriceCooldown.getOrDefault(p.getUniqueId(), 0L));
            if (remaining > 0) {
                sendCooldownMsg(p, "Blood Price", remaining);
                return;
            }
            double hp = p.getHealth();
            if (hp <= 2.0) {
                p.sendMessage("§cNot enough health to activate Blood Price!");
                return;
            }
            bloodPriceCooldown.put(p.getUniqueId(), now());
            p.setHealth(Math.max(0.5, hp - 2.0));
            bloodPriceActive.put(p.getUniqueId(), true);
            p.sendMessage("§4⚔ Blood Price activated! Next hit deals +40% damage.");
            p.playSound(p.getLocation(), Sound.ENTITY_WITHER_HURT, 0.6f, 1.8f);
            return;
        }

        // ── War Cry (branch-a milestone, slot 34) ───────────────────────────
        // Only fires if player is on the Berserker path (has War Cry milestone)
        if (hasUpgrade(p, SLOT_WAR_CRY)) {
            long remaining = COOLDOWN_WAR_CRY - (now() - warCryCooldown.getOrDefault(p.getUniqueId(), 0L));
            if (remaining > 0) {
                sendCooldownMsg(p, "War Cry", remaining);
                return;
            }
            warCryCooldown.put(p.getUniqueId(), now());
            activateWarCry(p);
            return;
        }

        // ── Footwork (branch-b, slot 12) ────────────────────────────────────
        if (hasUpgrade(p, SLOT_FOOTWORK)) {
            long remaining = COOLDOWN_FOOTWORK - (now() - footworkCooldown.getOrDefault(p.getUniqueId(), 0L));
            if (remaining > 0) {
                sendCooldownMsg(p, "Footwork", remaining);
                return;
            }
            footworkCooldown.put(p.getUniqueId(), now());
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 40, 1, false, true, true));
            p.sendMessage("§b⚡ Footwork activated!");
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1f, 1.4f);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Damage dealt BY the player
    // ────────────────────────────────────────────────────────────────────────
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerDealDamage(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Player p)) return;
        if (!isMelee(p)) return;
        if (!plugin.isWorldAllowed(p)) return;
        if (e.isCancelled()) return;

        Entity victim = e.getEntity();
        double dmg    = e.getDamage();

        // ── Hardened Strikes: +15% melee damage ─────────────────────────────
        if (hasUpgrade(p, SLOT_HARDENED_STRIKES)) {
            dmg *= 1.15;
        }

        // ── Frenzy State: +5% per stack (max 3) ─────────────────────────────
        if (hasUpgrade(p, SLOT_FRENZY_MILESTONE)) {
            int stacks = getFrenzyStacks(p);
            if (stacks > 0) dmg *= (1.0 + stacks * 0.05);
        }

        // ── Reckless Charge: +4 damage on the first hit after starting a sprint
        if (hasUpgrade(p, SLOT_RECKLESS_CHARGE)
                && p.isSprinting()
                && recklessChargeReady.getOrDefault(p.getUniqueId(), false)) {
            dmg += 4.0;
            recklessChargeReady.put(p.getUniqueId(), false);
            p.sendMessage("§c⚡ Reckless Charge!");
        }

        // ── Blood Price: +40% damage on flagged next hit ─────────────────────
        if (bloodPriceActive.getOrDefault(p.getUniqueId(), false)) {
            dmg *= 1.40;
            bloodPriceActive.put(p.getUniqueId(), false);
            p.sendMessage("§4⚔ Blood Price consumed — massive strike!");
            p.playSound(p.getLocation(), Sound.ENTITY_PLAYER_ATTACK_CRIT, 1f, 0.8f);
        }

        // ── Ghost Strike: 2x crit after 2s of sprinting ──────────────────────
        if (hasUpgrade(p, SLOT_SHADOWSTRIKE_SUB) && ghostStrikeReady.getOrDefault(p.getUniqueId(), false)) {
            dmg *= 2.0;
            ghostStrikeReady.put(p.getUniqueId(), false);
            p.getWorld().spawnParticle(Particle.CRIT, victim.getLocation().add(0, 1, 0), 12, 0.3, 0.3, 0.3, 0.1);
            p.sendMessage("§b✦ Ghost Strike — critical hit!");
        }

        // ── Exploit Weakness: 3rd consecutive hit on same target ─────────────
        if (hasUpgrade(p, SLOT_EXPLOIT_WEAKNESS) && victim instanceof LivingEntity) {
            UUID pId  = p.getUniqueId();
            UUID vId  = victim.getUniqueId();
            UUID prev = lastTarget.get(pId);
            hitStreaks.computeIfAbsent(pId, k -> new HashMap<>());
            if (!vId.equals(prev)) hitStreaks.get(pId).clear();
            lastTarget.put(pId, vId);
            int streak = hitStreaks.get(pId).merge(vId, 1, Integer::sum);
            if (streak >= 3) {
                ((LivingEntity) victim).addPotionEffect(
                    new PotionEffect(PotionEffectType.WEAKNESS, 60, 1, false, true, true));
                hitStreaks.get(pId).put(vId, 0);
                p.sendMessage("§bExploit Weakness triggered!");
            }
        }

        // ── Combat Rhythm: reduce all cooldowns by 0.3s on hit ───────────────
        if (hasUpgrade(p, SLOT_COMBAT_RHYTHM)) {
            tickCooldowns(p, 300L);
        }

        e.setDamage(dmg);
    }

    // ────────────────────────────────────────────────────────────────────────
    // FIX 3: Damage received BY the player — Riposte rewritten.
    //
    // Old approach: tracked a "lastBlockedAt" timestamp from a cancelled
    // EntityDamageEvent, then checked that timestamp in a window. Problem:
    // Bukkit does NOT fire cancelled EntityDamageEvents for shield blocks —
    // it silently absorbs them and the event never arrives with isCancelled().
    //
    // New approach: at the moment the LIVE damage event fires, check
    // p.isBlocking() directly. If the player is holding their shield up right
    // now, that IS the parry. We store the damage, fire the riposte, then
    // reduce the incoming damage to 0 (shield absorbed it).
    // ────────────────────────────────────────────────────────────────────────
    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerReceiveDamage(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!isMelee(p)) return;
        if (!plugin.isWorldAllowed(p)) return;
        if (e.isCancelled()) return;

        double dmg = e.getDamage();

        // ── Riposte: only triggers if shield was raised within the parry window ─
        // The player must RAISE their shield (tracked via PlayerItemHeldEvent /
        // PlayerToggleSneakEvent) shortly BEFORE the hit lands — not just hold it.
        // Window: 0.5s base, 1.0s with Master's Form.
        if (hasUpgrade(p, SLOT_RIPOSTE) && e.getDamager() instanceof LivingEntity attacker) {
            long raised = shieldRaisedAt.getOrDefault(p.getUniqueId(), 0L);
            long window = hasUpgrade(p, SLOT_MASTERS_MILESTONE) ? 1000L : 500L;
            long timeSinceRaise = now() - raised;
            if (p.isBlocking() && timeSinceRaise <= window) {
                double riposteDmg = dmg * 2.0;
                attacker.damage(riposteDmg, p);
                e.setDamage(0); // shield absorbed the hit
                // Consume the parry — must re-raise shield for next riposte
                shieldRaisedAt.put(p.getUniqueId(), 0L);
                p.sendMessage("§b⚔ Riposte! Reflected §f" + String.format("%.1f", riposteDmg) + "§b damage!");

                // ── Master's Form: riposte also grants Speed II for 1.5s ─────
                if (hasUpgrade(p, SLOT_MASTERS_MILESTONE)) {
                    p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 30, 1, false, true, true));
                }

                // ── Sentinel: successful riposte stuns the attacker 2s ───────
                if (hasUpgrade(p, SLOT_SENTINEL)) {
                    attacker.addPotionEffect(
                        new PotionEffect(PotionEffectType.SLOWNESS, 40, 10, false, true, true));
                    p.sendMessage("§b⬛ Sentinel — attacker stunned for 2s!");
                }

                p.playSound(p.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1f, 0.6f);
                return; // damage cancelled, skip further reduction
            }
        }

        // ── Immovable Object (Ironclad subclass): -30% while standing still ──
        if (hasUpgrade(p, SLOT_IRONCLAD_SUB)) {
            long sinceMove = now() - lastMoveTime.getOrDefault(p.getUniqueId(), 0L);
            if (sinceMove > 500) {
                dmg *= 0.70;
            }
        }

        // ── Juggernaut: Resistance II passively when below 50% HP ────────────
        // (also handled in periodic task, this is a safety top-up on damage)
        if (hasUpgrade(p, SLOT_JUGGERNAUT)) {
            double maxHp = Objects.requireNonNull(
                p.getAttribute(Attribute.GENERIC_MAX_HEALTH)).getValue();
            if (p.getHealth() / maxHp < 0.5 && !p.hasPotionEffect(PotionEffectType.RESISTANCE)) {
                p.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, 60, 1, false, false, true));
            }
        }

        // ── Unstoppable Force (Warlord subclass): absorb proc below 30% HP ───
        if (hasUpgrade(p, SLOT_WARLORD_SUB)) {
            double maxHp = Objects.requireNonNull(
                p.getAttribute(Attribute.GENERIC_MAX_HEALTH)).getValue();
            if (p.getHealth() / maxHp < 0.30 &&
                !onCooldown(unstoppableCooldown, p.getUniqueId(), COOLDOWN_UNSTOPPABLE)) {
                unstoppableCooldown.put(p.getUniqueId(), now());
                p.addPotionEffect(new PotionEffect(PotionEffectType.ABSORPTION,      60, 1, false, true, true));
                p.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE, 60, 0, false, true, true));
                p.sendMessage("§c🛡 Unstoppable Force — you endure!");
                p.playSound(p.getLocation(), Sound.ENTITY_IRON_GOLEM_HURT, 0.8f, 0.6f);
            }
        }

        // ── Death Pact (Hexblade subclass): survive lethal hit once per life ──
        if (hasUpgrade(p, SLOT_HEXBLADE_SUB)) {
            boolean used = deathPactUsed.getOrDefault(p.getUniqueId(), false);
            if (!used && p.getHealth() - dmg <= 0) {
                e.setDamage(0);
                p.setHealth(1.0);
                deathPactUsed.put(p.getUniqueId(), true);
                p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, 80, 1, false, true, true));
                p.sendMessage("§4💀 Death Pact — you refuse to die! Strength II for 4s.");
                p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.5f, 1.5f);
                return;
            }
        }

        e.setDamage(dmg);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Kill events
    // ────────────────────────────────────────────────────────────────────────
    @EventHandler
    public void onKill(EntityDeathEvent e) {
        if (!(e.getEntity().getKiller() instanceof Player p)) return;
        if (!isMelee(p)) return;
        if (!plugin.isWorldAllowed(p)) return;

        UUID id = p.getUniqueId();

        // ── Bloodlust: Speed II for 2s on kill ───────────────────────────────
        if (hasUpgrade(p, SLOT_BLOODLUST)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 40, 1, false, true, true));
        }

        // ── Phantom Step (Shadowstrike): Speed III for 3s on kill ────────────
        if (hasUpgrade(p, SLOT_PHANTOM_STEP)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 60, 2, false, true, true));
        }

        // ── Frenzy State: stack kills within 10s for +5% damage per stack ────
        if (hasUpgrade(p, SLOT_FRENZY_MILESTONE)) {
            long timeSinceLast = now() - lastKillTime.getOrDefault(id, 0L);
            if (timeSinceLast < FRENZY_DECAY_MS) {
                int stacks = Math.min(3, frenzyStacks.getOrDefault(id, 0) + 1);
                frenzyStacks.put(id, stacks);
                p.sendMessage("§c🔥 Frenzy Stack x" + stacks + " — +" + (stacks * 5) + "% damage");
            } else {
                frenzyStacks.put(id, 1);
                p.sendMessage("§c🔥 Frenzy Stack x1 — +5% damage");
            }
            lastKillTime.put(id, now());
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Reset per-life state on death
    // ────────────────────────────────────────────────────────────────────────
    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent e) {
        UUID id = e.getEntity().getUniqueId();
        deathPactUsed.put(id, false);
        frenzyStacks.put(id, 0);
        ghostStrikeReady.put(id, false);
        bloodPriceActive.put(id, false);
        hitStreaks.remove(id);
        lastTarget.remove(id);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Track when player raises their shield for Riposte window.
    // We listen for PlayerItemHeldEvent (switching to offhand with shield)
    // and also PlayerInteractEvent for offhand shield raises.
    // The most reliable cross-version signal is: player starts blocking,
    // detected by checking isBlocking() in a tick after they right-click offhand.
    // We use PlayerItemHeldEvent + a 1-tick delayed check.
    // ────────────────────────────────────────────────────────────────────────
    @EventHandler
    public void onShieldRaise(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!isMelee(p)) return;
        if (!plugin.isWorldAllowed(p)) return;
        if (!hasUpgrade(p, SLOT_RIPOSTE)) return;

        org.bukkit.event.block.Action action = e.getAction();
        if (action != org.bukkit.event.block.Action.RIGHT_CLICK_AIR &&
            action != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;

        // Sneaking = your other ability
        if (p.isSneaking()) return;

        ItemStack offhand = p.getInventory().getItemInOffHand();
        if (offhand.getType() != Material.SHIELD) return;

        // Record immediately — this is the important part
        shieldRaisedAt.put(p.getUniqueId(), now());
    }

    // ────────────────────────────────────────────────────────────────────────
    // Track sprint for Ghost Strike
    // ────────────────────────────────────────────────────────────────────────
    @EventHandler
    public void onPlayerToggleSprint(PlayerToggleSprintEvent e) {
        Player p = e.getPlayer();
        if (!isMelee(p)) return;
        if (!plugin.isWorldAllowed(p)) return;
        boolean hasReckless = hasUpgrade(p, SLOT_RECKLESS_CHARGE);
        boolean hasShadowstrike = hasUpgrade(p, SLOT_SHADOWSTRIKE_SUB);
        if (!shouldTrackSprint(hasReckless, hasShadowstrike)) return;

        if (e.isSprinting()) {
            if (hasShadowstrike) {
                sprintStartTime.put(p.getUniqueId(), now());
            }
            if (hasReckless) {
                recklessChargeReady.put(p.getUniqueId(), true);
            }
        } else {
            long started = sprintStartTime.getOrDefault(p.getUniqueId(), now());
            if (hasShadowstrike && now() - started >= GHOST_SPRINT_REQUIRED) {
                ghostStrikeReady.put(p.getUniqueId(), true);
            }
            sprintStartTime.remove(p.getUniqueId());
            if (hasReckless) {
                recklessChargeReady.put(p.getUniqueId(), false);
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Track movement for Immovable Object
    // ────────────────────────────────────────────────────────────────────────
    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!isMelee(p)) return;
        if (!plugin.isWorldAllowed(p)) return;
        if (!hasUpgrade(p, SLOT_IRONCLAD_SUB)) return;

        org.bukkit.Location from = e.getFrom();
        org.bukkit.Location to   = e.getTo();
        if (to == null) return;

        if (from.getX() != to.getX() || from.getZ() != to.getZ()) {
            lastMoveTime.put(p.getUniqueId(), now());
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Warrior's Resolve: reduce knockback by 20% via post-damage velocity fix
    // ────────────────────────────────────────────────────────────────────────
    @EventHandler
    public void onKnockback(EntityDamageByEntityEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!isMelee(p)) return;
        if (!plugin.isWorldAllowed(p)) return;
        if (!hasUpgrade(p, SLOT_RESOLVE_MILESTONE)) return;

        new BukkitRunnable() {
            @Override public void run() {
                if (p.isOnline()) p.setVelocity(p.getVelocity().multiply(0.80));
            }
        }.runTaskLater(plugin, 1L);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Periodic: Juggernaut passive refresh + Frenzy stack decay
    // ────────────────────────────────────────────────────────────────────────
    private void startPeriodicTasks() {
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!isMelee(p)) continue;

                    // Juggernaut: keep Resistance II active while below 50% HP
                    if (hasUpgrade(p, SLOT_JUGGERNAUT)) {
                        double maxHp = Objects.requireNonNull(
                            p.getAttribute(Attribute.GENERIC_MAX_HEALTH)).getValue();
                        if (p.getHealth() / maxHp < 0.5) {
                            p.addPotionEffect(new PotionEffect(
                                PotionEffectType.RESISTANCE, 60, 1, false, false, true));
                        } else {
                            p.removePotionEffect(PotionEffectType.RESISTANCE);
                        }
                    }

                    // Frenzy State: clear stacks after 10s of no kills
                    UUID id = p.getUniqueId();
                    if (frenzyStacks.getOrDefault(id, 0) > 0) {
                        if (now() - lastKillTime.getOrDefault(id, 0L) > FRENZY_DECAY_MS) {
                            frenzyStacks.put(id, 0);
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    // ────────────────────────────────────────────────────────────────────────
    // War Cry implementation
    // ────────────────────────────────────────────────────────────────────────
    private void activateWarCry(Player p) {
        int affected = 0;
        for (Entity e : p.getNearbyEntities(5, 5, 5)) {
            if (!(e instanceof LivingEntity le) || e.equals(p)) continue;
            le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 80, 1, false, true, true));
            le.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING,  80, 0, false, true, true));
            affected++;
        }
        p.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, p.getLocation(), 3, 0.5, 0.5, 0.5, 0);
        p.playSound(p.getLocation(), Sound.ENTITY_RAVAGER_ROAR, 1f, 0.8f);
        p.sendMessage("§c⚔ War Cry! " + affected + " enemies slowed and revealed.");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Combat Rhythm: shave ms off all active cooldowns
    // ────────────────────────────────────────────────────────────────────────
    private void tickCooldowns(Player p, long reduceMs) {
        UUID id = p.getUniqueId();
        warCryCooldown.computeIfPresent(id,      (k, v) -> v - reduceMs);
        footworkCooldown.computeIfPresent(id,    (k, v) -> v - reduceMs);
        bloodPriceCooldown.computeIfPresent(id,  (k, v) -> v - reduceMs);
        unstoppableCooldown.computeIfPresent(id, (k, v) -> v - reduceMs);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Frenzy stack helper (auto-decays on read)
    // ────────────────────────────────────────────────────────────────────────
    private int getFrenzyStacks(Player p) {
        UUID id = p.getUniqueId();
        if (now() - lastKillTime.getOrDefault(id, 0L) > FRENZY_DECAY_MS) {
            frenzyStacks.put(id, 0);
            return 0;
        }
        return frenzyStacks.getOrDefault(id, 0);
    }
}
