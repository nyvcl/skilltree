package com.classystem.listener;

import com.classystem.data.PlayerDataManager;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.metadata.FixedMetadataValue;
import com.classystem.ClassSystemPlugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Implements all ranged-class upgrade gameplay effects.
 *
 * Slot map (matches config.yml):
 *
 *   GENERAL PATH (Trunk)
 *   50  Steady Aim             41  Quiver Mastery
 *   32  Hunter's Mark (trunk milestone)
 *
 *   BRANCH A — Precision / Single-Target
 *   33  Eagle Eye              34  Bullseye (branch milestone)
 *   25  Armor Pierce
 *   16  Predator's Focus (pre-subclass — shared by both A subclasses)
 *   15  Deadeye (Sniper upgrade)
 *    6  The Sniper (subclass milestone)
 *   17  Skirmisher (Duelist upgrade)
 *    8  The Duelist (subclass milestone)
 *
 *   BRANCH B — Suppression / Multi-Target
 *   31  Volley Training        30  Suppressing Fire (branch milestone)
 *   21  Incendiary Tips
 *   12  Shrapnel Shot (pre-subclass — Bombardier path)
 *   11  Explosive Payload (Bombardier upgrade)
 *    2  The Bombardier (subclass milestone)
 *   13  Shrapnel Shot (pre-subclass — Trapper path)
 *   22  Tangle Shot (Trapper upgrade)
 *    4  The Trapper (subclass milestone)
 *
 * Subclass milestones for Sniper (6) and Duelist (8) also unlock:
 *   Sniper   → Ghost Round  (once-per-20s pass-through shot)
 *   Duelist  → Execute       (2x damage below 25% HP)
 *   Bombardier → Carpet Bombing (Volley fires 5, each triggers Explosive Payload)
 *   Trapper  → Web of Pain  (snared mobs take +30% dmg; snares pulse AoE slow)
 */
public class RangedAbilityListener implements Listener {

    // ── Metadata keys ────────────────────────────────────────────────────────
    private static final String META_MARKED        = "cs_huntermark";   // UUID of marking player
    private static final String META_SNARE         = "cs_snare";        // true = snare location marker
    private static final String META_GHOST_ROUND   = "cs_ghostround";   // true = this arrow passes through
    private static final String META_VOLLEY        = "cs_volley";       // true = volley arrow (no recurse)
    private static final String META_SUPPRESSION   = "cs_suppression";  // cooldown gate per arrow entity
    private static final String META_SHOT_X        = "cs_shot_x";
    private static final String META_SHOT_Y        = "cs_shot_y";
    private static final String META_SHOT_Z        = "cs_shot_z";

    // ── Slot constants ───────────────────────────────────────────────────────
    // General
    private static final int SLOT_STEADY_AIM       = 50;
    private static final int SLOT_QUIVER_MASTERY   = 41;
    private static final int SLOT_HUNTERS_MARK     = 32;
    // Branch A
    private static final int SLOT_EAGLE_EYE        = 33;
    private static final int SLOT_BULLSEYE         = 34;
    private static final int SLOT_ARMOR_PIERCE     = 25;
    private static final int SLOT_PREDATORS_FOCUS  = 16;
    private static final int SLOT_DEADEYE          = 15;
    private static final int SLOT_SNIPER_SUB       =  6;
    private static final int SLOT_SKIRMISHER       = 17;
    private static final int SLOT_DUELIST_SUB      =  8;
    // Branch B
    private static final int SLOT_VOLLEY_TRAINING  = 31;
    private static final int SLOT_SUPPRESS_FIRE    = 30;
    private static final int SLOT_INCENDIARY_TIPS  = 21;
    private static final int SLOT_SHRAPNEL_SHOT    = 12; // Shared PSU — Bombardier & Trapper
    private static final int SLOT_EXPLOSIVE_PAYLOAD= 11;
    private static final int SLOT_BOMBARDIER_SUB   =  2;
    private static final int SLOT_TANGLE_SHOT      = 13; // Trapper path (PSU slot)
    private static final int SLOT_TRAPPER_SUB      =  4;

    // ── Cooldown / state maps ────────────────────────────────────────────────
    private final Map<UUID, Long>    lastMoveTime        = new HashMap<>();
    private final Map<UUID, Long>    ghostRoundCooldown  = new HashMap<>();
    private final Map<UUID, Boolean> ghostRoundReady     = new HashMap<>();
    // Mark expiry: arrowVictim UUID → expiry timestamp
    private final Map<UUID, Long>    markExpiry          = new HashMap<>();
    // Snare locations: we store ArmorStand entities acting as snare sensors
    // (keyed by their UUID, value = owner player UUID + expiry)
    private final Map<UUID, SnareMeta> activeSnares      = new HashMap<>();
    // Predator's Focus: track partially-drawn bow for 50% draw reset
    private final Map<UUID, Boolean> drawResetPending    = new HashMap<>();

    // ── Cooldowns ────────────────────────────────────────────────────────────
    private static final long COOLDOWN_GHOST_ROUND  = 20_000L;
    private static final long MARK_DURATION_MS      =  6_000L;
    private static final long SNARE_DURATION_MS     = 10_000L;
    private static final long SNARE_ROOT_MS         =  1_500L;
    private static final long SUPPRESS_DURATION_TICKS = 40L; // 2s

    private final PlayerDataManager pdm;
    private final ClassSystemPlugin plugin;

    public RangedAbilityListener(PlayerDataManager pdm, ClassSystemPlugin plugin) {
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

    private boolean isRanged(Player p) {
        return "ranged".equals(pdm.getClass(p));
    }

    private boolean holdingBow(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null) return false;
        Material m = hand.getType();
        return m == Material.BOW || m == Material.CROSSBOW;
    }

    private long now() { return System.currentTimeMillis(); }

    private boolean onCooldown(Map<UUID, Long> map, UUID id, long ms) {
        Long last = map.get(id);
        return last != null && (now() - last) < ms;
    }

    static int volleyArrowCost(int volleyCount) {
        return (int) Math.ceil(volleyCount * 0.5);
    }

    static double deadeyeBonus(double distance) {
        return Math.min(0.40, Math.floor(distance / 5.0) * 0.05);
    }

    static double armorPierceMultiplier() {
        return 1.15;
    }

    /** Resolve the shooter of an arrow back to a Player, or null. */
    private Player getShooter(Arrow arrow) {
        if (arrow.getShooter() instanceof Player p) return p;
        return null;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Movement tracking — for Steady Aim & Skirmisher
    // ────────────────────────────────────────────────────────────────────────

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        Player p = e.getPlayer();
        if (!isRanged(p)) return;
        if (!plugin.isWorldAllowed(p)) return;

        Location from = e.getFrom();
        Location to   = e.getTo();
        if (to == null) return;

        if (from.getX() != to.getX() || from.getZ() != to.getZ()) {
            lastMoveTime.put(p.getUniqueId(), now());
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Quiver Mastery — 20% faster draw speed via attribute
    // Applied passively whenever the player has the upgrade.
    // ────────────────────────────────────────────────────────────────────────

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!plugin.isWorldAllowed(p)) return;
        applyQuiverMastery(p);
    }

    private void applyQuiverMastery(Player p) {
        if (!isRanged(p)) return;
        // Quiver Mastery: handled indirectly — Bukkit has no "draw speed" attribute.
        // We simulate it in EntityShootBowEvent by firing at max power if drawing.
        // The lore says "arrows fired at full charge always" — we enforce this there.
    }

    // ────────────────────────────────────────────────────────────────────────
    // Volley Training — sneak + right-click with bow fires 3-arrow spread
    // ────────────────────────────────────────────────────────────────────────

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!isRanged(p))    return;
        if (!plugin.isWorldAllowed(p)) return;
        if (!holdingBow(p))  return;
        if (!p.isSneaking()) return;

        Action action = e.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        // Volley Training (and Carpet Bombing upgrade)
        if (!hasUpgrade(p, SLOT_VOLLEY_TRAINING)) return;

        e.setCancelled(true);

        // Arrow cost check — 1.5x normal = ceil(1.5) = need at least 2 arrows
        // (We consume 2 arrows for a 3-arrow volley; 5-arrow Carpet Bombing costs 3)
        int volleyCount = hasUpgrade(p, SLOT_BOMBARDIER_SUB) ? 5 : 3;
        int arrowCost = volleyArrowCost(volleyCount);

        if (countArrows(p) < arrowCost) {
            p.sendMessage("§cNot enough arrows for Volley! (need " + arrowCost + ")");
            return;
        }

        consumeArrows(p, arrowCost);

        fireVolley(p, volleyCount);

        String label = (volleyCount == 5) ? "§c💣 Carpet Bombing" : "§c⬦ Volley";
        p.sendMessage(label + " — " + volleyCount + " arrows fired!");
        p.playSound(p.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1f, 0.9f);
    }

    private void fireVolley(Player p, int count) {
        double spreadDeg = 12.0;
        double step = (count > 1) ? (spreadDeg * 2) / (count - 1) : 0;

        for (int i = 0; i < count; i++) {
            double offset = -spreadDeg + (step * i);
            Vector dir = p.getLocation().getDirection().clone();
            // Rotate around Y axis for horizontal spread
            double rad = Math.toRadians(offset);
            double cos = Math.cos(rad), sin = Math.sin(rad);
            double x = dir.getX() * cos - dir.getZ() * sin;
            double z = dir.getX() * sin + dir.getZ() * cos;
            dir.setX(x).setZ(z).normalize();

            Arrow arrow = p.getWorld().spawnArrow(
                    p.getEyeLocation(), dir, 2.5f, 8f);
            arrow.setShooter(p);
            arrow.setDamage(arrow.getDamage() * 0.85); // slight spread penalty
            arrow.setMetadata(META_VOLLEY, new FixedMetadataValue(plugin, true));
            tagShotOrigin(arrow, p.getEyeLocation());

            // If Explosive Payload is unlocked, Carpet Bombing triggers it on ground hit
            // (handled in onProjectileHit)
        }
    }

    private void consumeArrows(Player p, int amount) {
        int remaining = amount;
        for (ItemStack item : p.getInventory().getContents()) {
            if (item != null && item.getType() == Material.ARROW && remaining > 0) {
                int take = Math.min(item.getAmount(), remaining);
                item.setAmount(item.getAmount() - take);
                remaining -= take;
                if (remaining <= 0) break;
            }
        }
    }

    private int countArrows(Player p) {
        int total = 0;
        for (ItemStack item : p.getInventory().getContents()) {
            if (item != null && item.getType() == Material.ARROW) total += item.getAmount();
        }
        return total;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Ghost Round — Sniper subclass active ability
    // Fires a pass-through arrow (sneak + right-click while Ghost Round is off CD)
    // Actually triggered as a special bow shot — we flag the next arrow fired.
    // ────────────────────────────────────────────────────────────────────────

    // Ghost Round is triggered automatically — the next arrow the Sniper fires
    // (via normal bow shot) becomes a Ghost Round when the cooldown has elapsed.
    // We set the flag and the EntityShootBowEvent marks the arrow.

    // ────────────────────────────────────────────────────────────────────────
    // EntityShootBowEvent — apply Quiver Mastery, Eagle Eye, Ghost Round flag
    // ────────────────────────────────────────────────────────────────────────

    @EventHandler
    public void onShootBow(EntityShootBowEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        if (!isRanged(p)) return;
        if (!plugin.isWorldAllowed(p)) return;
        if (!(e.getProjectile() instanceof Arrow arrow)) return;
        tagShotOrigin(arrow, p.getEyeLocation());

        if (drawResetPending.remove(p.getUniqueId()) != null) {
            new BukkitRunnable() {
                @Override public void run() {
                    if (arrow.isValid()) {
                        Vector dir = arrow.getVelocity().normalize();
                        arrow.setVelocity(dir.multiply(3.0));
                    }
                }
            }.runTaskLater(plugin, 1L);
            p.sendMessage("§6⚡ Predator's Focus — chain shot released.");
        }

        // Quiver Mastery: always fire at full-charge velocity.
        // EntityShootBowEvent.setForce() does not exist in Paper 1.21.1 —
        // we normalise the arrow's velocity to the max-charge magnitude (3.0) instead.
        if (hasUpgrade(p, SLOT_QUIVER_MASTERY)) {
            new BukkitRunnable() {
                @Override public void run() {
                    if (arrow.isValid()) {
                        Vector dir = arrow.getVelocity().normalize();
                        arrow.setVelocity(dir.multiply(3.0)); // full-charge speed
                    }
                }
            }.runTaskLater(plugin, 1L);
        }

        // Eagle Eye: increase velocity by 30% (applied after Quiver Mastery tick)
        if (hasUpgrade(p, SLOT_EAGLE_EYE)) {
            new BukkitRunnable() {
                @Override public void run() {
                    if (arrow.isValid()) {
                        arrow.setVelocity(arrow.getVelocity().multiply(1.30));
                    }
                }
            }.runTaskLater(plugin, 2L); // tick 2 so it stacks after QM if both unlocked
        }

        // Ghost Round: auto-ready after 20s cooldown (Sniper subclass)
        if (hasUpgrade(p, SLOT_SNIPER_SUB)) {
            UUID id = p.getUniqueId();
            if (!onCooldown(ghostRoundCooldown, id, COOLDOWN_GHOST_ROUND)) {
                ghostRoundCooldown.put(id, now());
                arrow.setMetadata(META_GHOST_ROUND, new FixedMetadataValue(plugin, true));
                p.sendMessage("§b✦ Ghost Round — this arrow passes through!");
                p.playSound(p.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1f, 1.8f);
                p.getWorld().spawnParticle(Particle.WITCH,
                        arrow.getLocation(), 5, 0.1, 0.1, 0.1, 0.02);
            }
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // ProjectileHitEvent — Tangle Shot snares & Explosive Payload
    // ────────────────────────────────────────────────────────────────────────

    @EventHandler
    public void onProjectileHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof Arrow arrow)) return;
        Player p = getShooter(arrow);
        if (p == null || !isRanged(p)) return;
        if (!plugin.isWorldAllowed(p)) return;

        boolean hitBlock  = e.getHitBlock() != null;
        boolean hitEntity = e.getHitEntity() != null;

        // ── Tangle Shot — arrow misses mobs and hits ground ─────────────────
        if (hitBlock && !hitEntity && hasUpgrade(p, SLOT_TANGLE_SHOT)) {
            placeTangleSnare(p, arrow.getLocation());
        }

        // ── Explosive Payload — arrow hits block (or Carpet Bombing hit) ─────
        boolean isCarpetBombing = arrow.hasMetadata(META_VOLLEY) &&
                                  hasUpgrade(p, SLOT_BOMBARDIER_SUB);
        if (hitBlock && (hasUpgrade(p, SLOT_EXPLOSIVE_PAYLOAD) || isCarpetBombing)) {
            triggerExplosivePayload(p, arrow.getLocation());
        }
    }

    private void triggerExplosivePayload(Player p, Location loc) {
        // No block damage — damage nearby living entities
        for (Entity e : loc.getWorld().getNearbyEntities(loc, 3, 3, 3)) {
            if (e.equals(p) || !(e instanceof LivingEntity le)) continue;
            double dmg = 4.0;
            // Carpet Bombing: 5 arrows, same payload power
            le.damage(dmg, p);
        }
        loc.getWorld().spawnParticle(Particle.EXPLOSION, loc, 1);
        loc.getWorld().playSound(loc, Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.5f);
    }

    private void placeTangleSnare(Player owner, Location loc) {
        // Spawn an invisible ArmorStand as the snare sensor
        ArmorStand stand = (ArmorStand) loc.getWorld().spawnEntity(loc, EntityType.ARMOR_STAND);
        stand.setVisible(false);
        stand.setGravity(false);
        stand.setInvulnerable(true);
        stand.setMarker(true);
        stand.setMetadata(META_SNARE, new FixedMetadataValue(plugin, true));

        long expiry = now() + SNARE_DURATION_MS;
        activeSnares.put(stand.getUniqueId(), new SnareMeta(owner.getUniqueId(), stand, expiry));

        // Particles to show snare
        loc.getWorld().spawnParticle(Particle.WHITE_ASH, loc.add(0, 0.5, 0), 12, 0.3, 0.1, 0.3, 0);
        loc.getWorld().playSound(loc, Sound.BLOCK_TRIPWIRE_CLICK_ON, 0.6f, 1.2f);

        // Auto-remove after 10s
        new BukkitRunnable() {
            @Override public void run() {
                stand.remove();
                activeSnares.remove(stand.getUniqueId());
            }
        }.runTaskLater(plugin, (SNARE_DURATION_MS / 50));
    }

    // ────────────────────────────────────────────────────────────────────────
    // EntityDamageByEntityEvent — main arrow hit processing
    // ────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onArrowHit(EntityDamageByEntityEvent e) {
        if (!(e.getDamager() instanceof Arrow arrow)) return;
        Player p = getShooter(arrow);
        if (p == null || !isRanged(p)) return;
        if (!plugin.isWorldAllowed(p)) return;
        if (e.isCancelled()) return;
        if (!(e.getEntity() instanceof LivingEntity victim)) return;

        double dmg = e.getDamage();

        // ── Steady Aim: +15% if stationary for 1.5s ─────────────────────────
        if (hasUpgrade(p, SLOT_STEADY_AIM)) {
            // Skirmisher negates the movement penalty but not the bonus itself.
            // Steady Aim bonus applies if standing still OR if Skirmisher negates.
            boolean stationary = (now() - lastMoveTime.getOrDefault(p.getUniqueId(), 0L)) >= 1500L;
            if (stationary) {
                dmg *= 1.15;
            }
        }

        // ── Skirmisher: moving no longer reduces damage ──────────────────────
        // (Steady Aim penalty negation — Bukkit doesn't apply a movement penalty
        //  natively so this is a no-op for now; the bonus still only applies
        //  when standing still unless we want always-on bonus for Duelist.)
        //  Design intent: Duelist keeps the Steady Aim bonus always.
        if (hasUpgrade(p, SLOT_SKIRMISHER)) {
            // Always apply Steady Aim bonus regardless of movement
            boolean wasMoving = (now() - lastMoveTime.getOrDefault(p.getUniqueId(), 0L)) < 1500L;
            if (wasMoving && hasUpgrade(p, SLOT_STEADY_AIM)) {
                dmg *= 1.15; // apply the bonus they'd have missed
            }
        }

        // ── Hunter's Mark: first hit marks; subsequent hits +10% ─────────────
        if (hasUpgrade(p, SLOT_HUNTERS_MARK)) {
            UUID victimId = victim.getUniqueId();
            Long expiry = markExpiry.get(victimId);
            String markOwner = victim.hasMetadata(META_MARKED)
                    ? victim.getMetadata(META_MARKED).get(0).asString() : null;

            if (markOwner != null && markOwner.equals(p.getUniqueId().toString())
                    && expiry != null && now() < expiry) {
                // Already marked — apply bonus
                dmg *= 1.10;
            } else {
                // First hit — apply mark
                victim.setMetadata(META_MARKED,
                        new FixedMetadataValue(plugin, p.getUniqueId().toString()));
                markExpiry.put(victimId, now() + MARK_DURATION_MS);
                p.sendMessage("§b◆ Hunter's Mark applied!");

                // Schedule mark removal
                new BukkitRunnable() {
                    @Override public void run() {
                        if (victim.isValid()) victim.removeMetadata(META_MARKED, plugin);
                        markExpiry.remove(victimId);
                    }
                }.runTaskLater(plugin, MARK_DURATION_MS / 50);
            }
        }

        // ── Bullseye: headshot — top 20% of bounding box = 2x damage ─────────
        if (hasUpgrade(p, SLOT_BULLSEYE)) {
            double entityHeight = victim.getHeight();
            double hitY = arrow.getLocation().getY() - victim.getLocation().getY();
            if (hitY >= entityHeight * 0.80) {
                dmg *= 2.0;
                p.sendMessage("§6★ HEADSHOT!");
                victim.getWorld().spawnParticle(Particle.CRIT,
                        victim.getLocation().add(0, entityHeight, 0), 8, 0.2, 0.2, 0.2, 0.1);
            }
        }

        // ── Armor Pierce: ignore 15% of armor ────────────────────────────────
        if (hasUpgrade(p, SLOT_ARMOR_PIERCE) && victim instanceof Player target) {
            // Spigot does not expose partial armor bypass here, so a flat boost represents pierce.
            dmg *= armorPierceMultiplier();
        }

        // ── Deadeye: +5% per 5 blocks of travel, cap +40% at 40 blocks ───────
        if (hasUpgrade(p, SLOT_DEADEYE)) {
            double distance = getShotDistance(arrow, p.getLocation());
            dmg *= (1.0 + deadeyeBonus(distance));
        }

        // ── Execute (Duelist subclass milestone): 2x vs targets below 25% HP ─
        if (hasUpgrade(p, SLOT_DUELIST_SUB)) {
            double maxHp = Objects.requireNonNull(
                    victim.getAttribute(Attribute.GENERIC_MAX_HEALTH)).getValue();
            if (victim.getHealth() / maxHp < 0.25) {
                dmg *= 2.0;
                p.sendMessage("§6⚡ EXECUTE!");
            }
        }

        // ── Suppressing Fire: slow on hit ─────────────────────────────────────
        if (hasUpgrade(p, SLOT_SUPPRESS_FIRE)) {
            victim.addPotionEffect(new PotionEffect(
                    PotionEffectType.SLOWNESS, (int) SUPPRESS_DURATION_TICKS, 0, false, true, true));
        }

        // ── Incendiary Tips: 30% chance to ignite for 3s ─────────────────────
        if (hasUpgrade(p, SLOT_INCENDIARY_TIPS) && Math.random() < 0.30) {
            victim.setFireTicks(60); // 3 seconds
        }

        // ── Ghost Round: arrow passes through one entity ──────────────────────
        if (arrow.hasMetadata(META_GHOST_ROUND)) {
            e.setCancelled(false); // still deal damage
            // Schedule the arrow to continue — Bukkit will remove it on hit,
            // so we spawn a clone continuing through.
            final double finalDmg = dmg;
            final Location hitLoc = arrow.getLocation().clone();
            final Vector velocity = arrow.getVelocity().clone();
            new BukkitRunnable() {
                @Override public void run() {
                    Arrow ghost = hitLoc.getWorld().spawnArrow(
                            hitLoc, velocity, (float) velocity.length(), 0f);
                    ghost.setShooter(p);
                    ghost.setDamage(finalDmg * 0.7); // second target gets 70%
                    // No further pass-through
                }
            }.runTaskLater(plugin, 1L);
            p.sendMessage("§b✦ Ghost Round — pierced through!");
        }

        // ── Predator's Focus: killing a marked target resets draw 50% ─────────
        // (handled in EntityDeathEvent)

        // ── Skirmisher: +10% move speed for 2s after firing ──────────────────
        if (hasUpgrade(p, SLOT_SKIRMISHER)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 40, 0, false, false, true));
        }

        e.setDamage(dmg);
    }

    // ────────────────────────────────────────────────────────────────────────
    // EntityDeathEvent — kill effects
    // ────────────────────────────────────────────────────────────────────────

    @EventHandler
    public void onEntityDeath(EntityDeathEvent e) {
        LivingEntity dead = e.getEntity();

        // Find if a ranged player killed this via arrow
        Player killer = dead.getKiller();
        if (killer == null || !isRanged(killer)) return;
        if (!plugin.isWorldAllowed(killer)) return;

        // ── Predator's Focus: reset bow draw if the kill was on a marked target ─
        if (hasUpgrade(killer, SLOT_PREDATORS_FOCUS)) {
            String markOwner = dead.hasMetadata(META_MARKED)
                    ? dead.getMetadata(META_MARKED).get(0).asString() : null;
            if (markOwner != null && markOwner.equals(killer.getUniqueId().toString())) {
                // Simulate 50% draw reset — we give the player a momentary
                // full-power next shot because Bukkit does not expose partial bow draw.
                killer.sendMessage("§6⚡ Predator's Focus — draw reset! Chain shot ready.");
                killer.playSound(killer.getLocation(), Sound.ENTITY_ARROW_SHOOT, 0.7f, 1.6f);
                // Flag for Quiver Mastery to fire next arrow at full charge immediately
                drawResetPending.put(killer.getUniqueId(), true);
            }
        }

        // ── Shrapnel Shot: splinters into 2 projectiles at 30% damage ─────────
        // Shrapnel Shot (slot 12) is the shared PSU for both Bombardier and Trapper
        boolean hasShrapnel = hasUpgrade(killer, SLOT_SHRAPNEL_SHOT);
        if (hasShrapnel) {
            spawnShrapnel(killer, dead.getLocation());
        }
    }

    private void spawnShrapnel(Player p, Location origin) {
        // Find 2 nearby mobs to target
        List<Entity> nearby = new ArrayList<>(
                origin.getWorld().getNearbyEntities(origin, 6, 6, 6));
        nearby.removeIf(e -> !(e instanceof LivingEntity) || e.equals(p));

        int shot = 0;
        for (Entity e : nearby) {
            if (shot >= 2) break;
            LivingEntity target = (LivingEntity) e;
            Vector dir = target.getLocation().add(0, 1, 0)
                    .subtract(origin).toVector().normalize();
            Location spawnLoc = origin.clone().add(0, 0.5, 0);
            Arrow frag = origin.getWorld().spawnArrow(spawnLoc, dir, 2.0f, 5f);
            frag.setShooter(p);
            frag.setDamage(frag.getDamage() * 0.30);
            frag.setMetadata(META_VOLLEY, new FixedMetadataValue(plugin, true)); // avoid recurse
            tagShotOrigin(frag, spawnLoc);
            shot++;
        }

        origin.getWorld().spawnParticle(Particle.CRIT, origin, 6, 0.3, 0.3, 0.3, 0.1);
        origin.getWorld().playSound(origin, Sound.ENTITY_ARROW_SHOOT, 0.5f, 1.4f);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Periodic tasks — snare detection, mark cleanup
    // ────────────────────────────────────────────────────────────────────────

    private void startPeriodicTasks() {
        // Snare detection: check every 5 ticks (0.25s)
        new BukkitRunnable() {
            @Override public void run() {
                if (activeSnares.isEmpty()) return;

                Iterator<Map.Entry<UUID, SnareMeta>> it = activeSnares.entrySet().iterator();
                while (it.hasNext()) {
                    Map.Entry<UUID, SnareMeta> entry = it.next();
                    SnareMeta meta = entry.getValue();

                    if (now() > meta.expiry || !meta.stand.isValid()) {
                        meta.stand.remove();
                        it.remove();
                        continue;
                    }

                    Player owner = Bukkit.getPlayer(meta.ownerUuid);
                    if (owner == null) continue;

                    // Check for mobs walking near the snare
                    Location snareLoc = meta.stand.getLocation();
                    for (Entity e : snareLoc.getWorld().getNearbyEntities(snareLoc, 1.0, 1.0, 1.0)) {
                        if (!(e instanceof LivingEntity le) || e instanceof Player) continue;
                        if (e.equals(meta.stand)) continue;

                        // Root the mob
                        le.addPotionEffect(new PotionEffect(
                                PotionEffectType.SLOWNESS,
                                (int) (SNARE_ROOT_MS / 50), 10, false, true, true));

                        // Web of Pain: snared mobs take +30% damage from all sources
                        if (hasUpgrade(owner, SLOT_TRAPPER_SUB)) {
                            // Tag the entity so damage handler can boost
                            le.setMetadata("cs_webofpain",
                                    new FixedMetadataValue(plugin, now() + SNARE_ROOT_MS));

                            // Web of Pain: AoE slow pulse around snare on trigger
                            for (Entity nearby : snareLoc.getWorld().getNearbyEntities(snareLoc, 3, 3, 3)) {
                                if (!(nearby instanceof LivingEntity nearbyLe) || nearby instanceof Player) continue;
                                nearbyLe.addPotionEffect(new PotionEffect(
                                        PotionEffectType.SLOWNESS, 30, 0, false, true, true));
                            }

                            snareLoc.getWorld().spawnParticle(Particle.WHITE_ASH, snareLoc, 16, 0.5, 0.5, 0.5, 0);
                        }

                        // Consume the snare on first trigger
                        meta.stand.remove();
                        it.remove();
                        snareLoc.getWorld().playSound(snareLoc, Sound.BLOCK_TRIPWIRE_CLICK_OFF, 1f, 0.8f);
                        break;
                    }
                }
            }
        }.runTaskTimer(plugin, 5L, 5L);

        // Passive Quiver Mastery "draw speed" simulation:
        // Players with Quiver Mastery using a bow get a small Haste effect.
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!isRanged(p)) continue;
                    if (hasUpgrade(p, SLOT_QUIVER_MASTERY)) {
                        // Haste I simulates faster bow draw
                        if (!p.hasPotionEffect(PotionEffectType.HASTE)) {
                            p.addPotionEffect(new PotionEffect(
                                    PotionEffectType.HASTE, 40, 0, false, false, false));
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Web of Pain — +30% damage boost to snared mobs
    // ────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onSnareDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof LivingEntity le)) return;
        if (!le.hasMetadata("cs_webofpain")) return;

        long expiry = le.getMetadata("cs_webofpain").get(0).asLong();
        if (now() > expiry) {
            le.removeMetadata("cs_webofpain", plugin);
            return;
        }

        e.setDamage(e.getDamage() * 1.30);
    }

    // ────────────────────────────────────────────────────────────────────────
    // Inner class for snare metadata
    // ────────────────────────────────────────────────────────────────────────

    private static class SnareMeta {
        final UUID ownerUuid;
        final ArmorStand stand;
        final long expiry;

        SnareMeta(UUID ownerUuid, ArmorStand stand, long expiry) {
            this.ownerUuid = ownerUuid;
            this.stand     = stand;
            this.expiry    = expiry;
        }
    }

    private void tagShotOrigin(Arrow arrow, Location origin) {
        arrow.setMetadata(META_SHOT_X, new FixedMetadataValue(plugin, origin.getX()));
        arrow.setMetadata(META_SHOT_Y, new FixedMetadataValue(plugin, origin.getY()));
        arrow.setMetadata(META_SHOT_Z, new FixedMetadataValue(plugin, origin.getZ()));
    }

    private double getShotDistance(Arrow arrow, Location fallbackOrigin) {
        if (!arrow.hasMetadata(META_SHOT_X) || !arrow.hasMetadata(META_SHOT_Y) || !arrow.hasMetadata(META_SHOT_Z)) {
            return arrow.getLocation().distance(fallbackOrigin);
        }
        double x = arrow.getMetadata(META_SHOT_X).get(0).asDouble();
        double y = arrow.getMetadata(META_SHOT_Y).get(0).asDouble();
        double z = arrow.getMetadata(META_SHOT_Z).get(0).asDouble();
        return arrow.getLocation().distance(new Location(arrow.getWorld(), x, y, z));
    }
}
