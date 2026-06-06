package com.classystem.listener;

import com.classystem.data.PlayerDataManager;
import org.bukkit.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.*;
import org.bukkit.event.player.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataType;
import com.classystem.ClassSystemPlugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

/**
 * Implements the full Magic class: Arcane Staff weapon, mana bar, casting system,
 * and all skill-tree upgrades from the design document.
 *
 * Slot map (matches config.yml magic tree):
 *
 *   GENERAL PATH (Trunk)
 *   50  Arcane Attunement      41  Mana Efficiency
 *   32  Awakening (milestone)
 *
 *   BRANCH A — Destruction Magic (Offensive)
 *   33  Volatile Casting       34  Pyroclasm (milestone)
 *   25  Chain Reaction
 *   16  Glass Cannon (shared pre-subclass for A & B)
 *    6  Battlemage (subclass milestone)
 *   15  Spellblade (Battlemage upgrade)
 *    8  Arcanist   (subclass milestone)
 *   17  Overload   (Arcanist upgrade)
 *
 *   BRANCH B — Arcane Arts (Control/Utility)
 *   31  Slow Field             30  Temporal Grasp (milestone)
 *   21  Gravity Well (pre-subclass)
 *   12  Arcane Shackles (shared pre-subclass for C & D)
 *    2  Illusionist (subclass milestone)
 *   11  Phantasm (Illusionist upgrade)
 *    4  Riftwalker  (subclass milestone)
 *   13  Phase Step (Riftwalker upgrade)
 *
 * Mana system:
 *   - Base max mana: 100. Awakening milestone: 140.
 *   - Basic bolt costs 10 mana.
 *   - Heavy (charged) bolt costs 35 mana.
 *   - Mana regenerates at 5/s passively; 10s after last cast.
 *   - Mana bar shown in action bar for 3s after mana changes.
 */
public class MagicAbilityListener implements Listener {

    // ── NBT / metadata keys ──────────────────────────────────────────────────
    private static final String META_STAFF          = "cs_arcane_staff";
    private static final String META_BOLT           = "cs_magic_bolt";
    private static final String META_HEAVY_BOLT     = "cs_magic_heavy_bolt";
    private static final String META_DEBUFFED        = "cs_magic_debuff"; // expiry timestamp
    private static final String META_SPELL_CASTER    = "cs_last_spell_caster";
    private static final String META_SPELL_TIME      = "cs_last_spell_time";
    private static final String META_SPELL_DAMAGE_EVENT = "cs_spell_damage_event";

    // ── Slot constants ───────────────────────────────────────────────────────
    // General
    private static final int SLOT_ARCANE_ATTUNEMENT = 50;
    private static final int SLOT_MANA_EFFICIENCY   = 41;
    private static final int SLOT_AWAKENING         = 32;
    // Branch A
    private static final int SLOT_VOLATILE_CASTING  = 33;
    private static final int SLOT_PYROCLASM         = 34;
    private static final int SLOT_CHAIN_REACTION    = 25;
    private static final int SLOT_GLASS_CANNON      = 16;
    private static final int SLOT_BATTLEMAGE_SUB    =  6;
    private static final int SLOT_SPELLBLADE        = 15;
    private static final int SLOT_ARCANIST_SUB      =  8;
    private static final int SLOT_OVERLOAD          = 17;
    // Branch B
    private static final int SLOT_SLOW_FIELD        = 31;
    private static final int SLOT_TEMPORAL_GRASP    = 30;
    private static final int SLOT_GRAVITY_WELL      = 21;
    private static final int SLOT_ARCANE_SHACKLES_C = 12; // Illusionist path (pre-subclass)
    private static final int SLOT_ARCANE_SHACKLES_D = 12; // Riftwalker path (pre-subclass)
    private static final int SLOT_ILLUSIONIST_SUB   =  2;
    private static final int SLOT_PHANTASM          = 11;
    private static final int SLOT_RIFTWALKER_SUB    =  4;
    private static final int SLOT_PHASE_STEP        = 13;

    // ── Mana constants ───────────────────────────────────────────────────────
    private static final double BASE_MAX_MANA          = 100.0;
    private static final double AWAKENING_MAX_MANA     = 140.0;
    private static final double BOLT_MANA_COST         = 10.0;
    private static final double HEAVY_BOLT_MANA_COST   = 35.0;
    private static final double MANA_REGEN_PASSIVE     = 5.0;   // per second
    private static final int    MANA_BAR_DISPLAY_TICKS = 60;    // 3 seconds

    // ── Damage constants ─────────────────────────────────────────────────────
    private static final double BOLT_BASE_DAMAGE       = 4.0;
    private static final double HEAVY_BOLT_DAMAGE_MULT = 2.0;

    // ── Cooldown durations ───────────────────────────────────────────────────
    private static final long CD_BASIC_BOLT_MS       =  1_000L;  // 1s
    private static final long CD_GRAVITY_WELL_MS     = 30_000L;
    private static final long CD_PHASE_STEP_MS       = 10_000L;
    private static final long CD_SINGULARITY_MS      = 60_000L;
    private static final long CD_MIRROR_IMAGE_MS     = 90_000L;
    private static final long CD_RIFT_ANCHOR_MS      = 45_000L;
    private static final long CD_RIFT_ANCHOR_POST_MS = 10_000L; // cooldown after recalling
    private static final long SPELL_KILL_WINDOW_MS   = 5_000L;

    // ── State maps ───────────────────────────────────────────────────────────
    private final Map<UUID, Long>     basicBoltCooldown   = new HashMap<>();
    private final Map<UUID, Long>     gravityWellCooldown = new HashMap<>();
    private final Map<UUID, Long>     phaseStepCooldown   = new HashMap<>();
    private final Map<UUID, Long>     singularityCooldown = new HashMap<>();
    private final Map<UUID, Long>     mirrorImageCooldown = new HashMap<>();
    private final Map<UUID, Long>     riftAnchorTime      = new HashMap<>();
    private final Map<UUID, Long>     riftAnchorCooldown  = new HashMap<>();
    private final Map<UUID, Location> riftAnchorLoc       = new HashMap<>();

    // Charge casting state
    private final Map<UUID, Long>    chargeStart         = new HashMap<>();
    private final Map<UUID, Integer> chargeTaskId        = new HashMap<>();

    // Mana bar display timer
    private final Map<UUID, Integer> manaBarTaskId       = new HashMap<>();

    // Debuffed targets (for Temporal Grasp)
    private final Set<UUID>          debuffedTargets     = new HashSet<>();

    private final PlayerDataManager pdm;
    private final ClassSystemPlugin plugin;

    public MagicAbilityListener(PlayerDataManager pdm, ClassSystemPlugin plugin) {
        this.pdm    = pdm;
        this.plugin = plugin;
        startPeriodicTasks();
    }

    // ────────────────────────────────────────────────────────────────────────
    // Helpers
    // ────────────────────────────────────────────────────────────────────────

    private boolean isMagic(Player p) {
        return "magic".equals(pdm.getClass(p));
    }

    private boolean holdingStaff(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() != Material.BLAZE_ROD) return false;
        if (!hand.hasItemMeta()) return false;
        return hand.getItemMeta().getPersistentDataContainer()
                .has(new NamespacedKey(plugin, META_STAFF), PersistentDataType.BYTE);
    }

    private boolean hasUpgrade(Player p, int slot) {
        return pdm.getTreePanes(p).contains(slot) || pdm.getTreeItems(p).contains(slot);
    }

    static boolean shouldRouteGravityWell(Action action, boolean hasClickedBlock) {
        return action == Action.RIGHT_CLICK_BLOCK && hasClickedBlock;
    }

    static boolean isRecentSpellDamage(Long spellTime, long currentTime) {
        return spellTime != null && currentTime - spellTime <= SPELL_KILL_WINDOW_MS;
    }

    static boolean shouldRollSpellbladeProc(boolean isSpellAppliedDamage, boolean hasSpellblade, double roll) {
        return !isSpellAppliedDamage && hasSpellblade && roll < 0.25;
    }

    static double basicBoltManaCost() {
        return BOLT_MANA_COST;
    }

    static double maxManaForAwakening(boolean hasAwakening) {
        return hasAwakening ? AWAKENING_MAX_MANA : BASE_MAX_MANA;
    }

    static boolean shouldSpawnAwakeningAura(boolean isMagic, boolean hasAwakening, boolean worldAllowed) {
        return isMagic && hasAwakening && worldAllowed;
    }

    private boolean hasArcaneShackles(Player p) {
        return hasUpgrade(p, SLOT_ARCANE_SHACKLES_C) || hasUpgrade(p, SLOT_ARCANE_SHACKLES_D);
    }

    private long now() { return System.currentTimeMillis(); }

    private boolean onCooldown(Map<UUID, Long> map, UUID id, long ms) {
        Long last = map.get(id);
        return last != null && (now() - last) < ms;
    }

    private long cdRemaining(Map<UUID, Long> map, UUID id, long ms) {
        Long last = map.get(id);
        if (last == null) return 0L;
        return Math.max(0L, ms - (now() - last));
    }

    private void sendCdMsg(Player p, String ability, long remainMs) {
        p.sendMessage("§c" + ability + " on cooldown — " + String.format("%.1f", remainMs / 1000.0) + "s remaining.");
    }

    // ── Mana management ─────────────────────────────────────────────────────

    private double getMaxMana(Player p) {
        return maxManaForAwakening(hasUpgrade(p, SLOT_AWAKENING));
    }

    private double getMana(Player p) {
        double stored = pdm.getMana(p);
        double max    = getMaxMana(p);
        return Math.min(stored, max);
    }

    private void setMana(Player p, double mana) {
        double max = getMaxMana(p);
        pdm.setMana(p, Math.max(0, Math.min(mana, max)));
        showManaBar(p);
    }

    /**
     * Try to spend mana. Returns false (and messages the player) if insufficient.
     * Applies Mana Efficiency (-15% cost) if unlocked.
     */
    private boolean spendMana(Player p, double baseCost) {
        double cost = baseCost;
        if (hasUpgrade(p, SLOT_MANA_EFFICIENCY)) cost *= 0.85;
        double current = getMana(p);
        if (current < cost) {
            p.sendMessage("§1✦ §9Insufficient mana! §1(" + (int) current + "/" + (int) getMaxMana(p) + ")");
            p.playSound(p.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.8f, 0.5f);
            showManaBar(p);
            return false;
        }
        setMana(p, current - cost);
        return true;
    }

    /** Show the mana bar in the action bar for MANA_BAR_DISPLAY_TICKS. */
    private void showManaBar(Player p) {
        // Cancel any existing hide task
        Integer old = manaBarTaskId.remove(p.getUniqueId());
        if (old != null) Bukkit.getScheduler().cancelTask(old);

        double mana    = getMana(p);
        double maxMana = getMaxMana(p);
        sendManaBarPacket(p, mana, maxMana);

        // Schedule hide after 3 seconds
        int taskId = new BukkitRunnable() {
            @Override public void run() {
                manaBarTaskId.remove(p.getUniqueId());
                p.sendActionBar("");
            }
        }.runTaskLater(plugin, MANA_BAR_DISPLAY_TICKS).getTaskId();
        manaBarTaskId.put(p.getUniqueId(), taskId);
    }

    private void sendManaBarPacket(Player p, double mana, double maxMana) {
        int filled  = (int) Math.round((mana / maxMana) * 20);
        int empty   = 20 - filled;
        String bar  = "§9" + "█".repeat(Math.max(0, filled))
                    + "§8" + "█".repeat(Math.max(0, empty));
        String text = "§1✦ §9Mana §f" + (int) mana + "§9/§f" + (int) maxMana + " " + bar;
        p.sendActionBar(text);
    }

    // ── Staff item creation ──────────────────────────────────────────────────

    /**
     * Creates the Arcane Staff ItemStack with NBT tag, name, and lore.
     * Called when a player selects the Magic class.
     */
    public static ItemStack createArcaneStaff(JavaPlugin plugin) {
        ItemStack staff = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta   = staff.getItemMeta();
        assert meta != null;
        meta.setDisplayName("§5Arcane Staff");
        meta.setLore(Arrays.asList(
                "§7An instrument of arcane power.",
                "§5Right-click §7— Fire magic bolt (10 mana)",
                "§5Shift+Right-click §7— Charge cast / Phase Step",
                "§5Left-click §7— Rift Anchor (Riftwalker only)",
                "§8This staff channels the Weave."
        ));
        // NBT tag so the plugin recognises it
        meta.getPersistentDataContainer().set(
                new NamespacedKey(plugin, META_STAFF),
                PersistentDataType.BYTE, (byte) 1);
        staff.setItemMeta(meta);
        return staff;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Staff distribution on join / class selection
    // ────────────────────────────────────────────────────────────────────────

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!isMagic(p)) return;
        if (!plugin.isWorldAllowed(p)) return;
        ensureHasStaff(p);
        // Initialise mana if never set
        if (pdm.getMana(p) == 100.0 && !p.getPersistentDataContainer()
                .has(new NamespacedKey(plugin, "mana"), PersistentDataType.STRING)) {
            pdm.setMana(p, getMaxMana(p));
        }
        applyPassiveEffects(p);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (!isMagic(p)) return;
        if (!plugin.isWorldAllowed(p)) return;
        // Restore partial mana on respawn
        new BukkitRunnable() {
            @Override public void run() {
                setMana(p, getMaxMana(p) * 0.5);
                ensureHasStaff(p);
                applyPassiveEffects(p);
            }
        }.runTaskLater(plugin, 1L);
    }

    private void ensureHasStaff(Player p) {
        // Check if player already has a tagged staff somewhere in inventory
        for (ItemStack item : p.getInventory().getContents()) {
            if (isStaffItem(item)) return;
        }
        p.getInventory().addItem(createArcaneStaff(plugin));
    }

    private boolean isStaffItem(ItemStack item) {
        if (item == null || item.getType() != Material.BLAZE_ROD) return false;
        if (!item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer()
                .has(new NamespacedKey(plugin, META_STAFF), PersistentDataType.BYTE);
    }

    private void applyPassiveEffects(Player p) {
        // Arcane Embodiment (Battlemage subclass): Strength I + Speed I on login/respawn
        if (hasUpgrade(p, SLOT_BATTLEMAGE_SUB)) {
            p.addPotionEffect(new PotionEffect(PotionEffectType.STRENGTH, Integer.MAX_VALUE, 0, false, false, true));
            p.addPotionEffect(new PotionEffect(PotionEffectType.SPEED,    Integer.MAX_VALUE, 0, false, false, true));
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Right-click — basic bolt / charge cast initiation
    // ────────────────────────────────────────────────────────────────────────

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!isMagic(p))    return;
        if (!plugin.isWorldAllowed(p)) return;
        if (!holdingStaff(p)) return;

        Action action = e.getAction();
        boolean isRightClick = (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK);
        boolean isLeftClick  = (action == Action.LEFT_CLICK_AIR  || action == Action.LEFT_CLICK_BLOCK);
        if (!isRightClick && !isLeftClick) return;

        e.setCancelled(true);

        UUID id = p.getUniqueId();

        // ── Left-click — Rift Anchor (set / recall) ──────────────────────────
        if (isLeftClick) {
            if (hasUpgrade(p, SLOT_RIFTWALKER_SUB)) {
                handleRiftAnchor(p);
            }
            return;
        }

        // ── Shift + right-click — charge cast or Phase Step ─────────────────
        if (p.isSneaking()) {
            // Phase Step — Riftwalker mobility
            if (hasUpgrade(p, SLOT_PHASE_STEP)) {
                long rem = cdRemaining(phaseStepCooldown, id, CD_PHASE_STEP_MS);
                if (rem > 0) {
                    sendCdMsg(p, "Phase Step", rem);
                    return;
                }
                phaseStepCooldown.put(id, now());
                doPhaseStep(p);
                return;
            }

            // Begin charge cast (Overload or default heavy bolt)
            if (chargeStart.containsKey(id)) return; // already charging
            chargeStart.put(id, now());

            // Visual feedback while charging
            int particleTaskId = new BukkitRunnable() {
                @Override public void run() {
                    if (!chargeStart.containsKey(id) || !p.isOnline()) { cancel(); return; }
                    p.getWorld().spawnParticle(Particle.WITCH,
                            p.getLocation().add(0, 1.5, 0), 3, 0.2, 0.2, 0.2, 0.01);
                }
            }.runTaskTimer(plugin, 0L, 3L).getTaskId();
            chargeTaskId.put(id, particleTaskId);

            p.sendActionBar("§5Charging... §8(release Shift to fire)");
            return;
        }

        // Gravity Well uses an intentional block click so normal staff casts still work in open terrain.
        if (hasUpgrade(p, SLOT_GRAVITY_WELL)
                && shouldRouteGravityWell(action, e.getClickedBlock() != null)) {
            activateGravityWell(p, e.getClickedBlock().getLocation().add(0.5, 1.0, 0.5));
            return;
        }

        // ── Regular right-click — fire basic bolt ────────────────────────────
        long remBolt = cdRemaining(basicBoltCooldown, id, getCooldownMs(p, CD_BASIC_BOLT_MS));
        if (remBolt > 0) {
            p.sendActionBar("§cCooldown: §f" + String.format("%.1f", remBolt / 1000.0) + "s");
            return;
        }

        if (!spendMana(p, BOLT_MANA_COST)) return;

        basicBoltCooldown.put(id, now());
        fireBasicBolt(p, BOLT_BASE_DAMAGE);
    }

    // Release shift — fire the charged bolt
    @EventHandler
    public void onSneak(PlayerToggleSneakEvent e) {
        Player p  = e.getPlayer();
        if (!isMagic(p) || !holdingStaff(p)) return;
        if (!plugin.isWorldAllowed(p)) return;
        if (e.isSneaking()) return; // this fires when sneak STARTS; we want when it ENDS

        UUID id = p.getUniqueId();
        if (!chargeStart.containsKey(id)) return;

        // Cancel particle task
        Integer ptId = chargeTaskId.remove(id);
        if (ptId != null) Bukkit.getScheduler().cancelTask(ptId);

        long holdMs   = now() - chargeStart.remove(id);
        double charge = Math.min(1.0, holdMs / 2000.0); // 0.0 – 1.0 over 2s

        if (!spendMana(p, HEAVY_BOLT_MANA_COST)) return;

        if (hasUpgrade(p, SLOT_ARCANIST_SUB) && hasUpgrade(p, SLOT_OVERLOAD)) {
            // Overload: full charge = 2x damage + 4-block knockback
            double dmgMult = (charge >= 1.0) ? 2.0 : (1.0 + charge * 0.5);
            fireHeavyBolt(p, BOLT_BASE_DAMAGE * dmgMult, charge >= 1.0);
        } else {
            // Default heavy bolt scales with charge
            double dmgMult = 1.0 + charge;
            fireHeavyBolt(p, BOLT_BASE_DAMAGE * dmgMult, false);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Bolt firing
    // ────────────────────────────────────────────────────────────────────────

    private long getCooldownMs(Player p, long base) {
        return hasUpgrade(p, SLOT_MANA_EFFICIENCY) ? (long)(base * 0.85) : base;
    }

    private void fireBasicBolt(Player p, double damage) {
        // Apply Arcane Attunement (+10%)
        if (hasUpgrade(p, SLOT_ARCANE_ATTUNEMENT)) damage *= 1.10;
        // Apply Glass Cannon (+30%)
        if (hasUpgrade(p, SLOT_GLASS_CANNON))      damage *= 1.30;

        Snowball bolt = p.launchProjectile(Snowball.class, p.getLocation().getDirection().multiply(2.5));
        bolt.setMetadata(META_BOLT, new FixedMetadataValue(plugin, damage));

        p.getWorld().spawnParticle(Particle.WITCH, p.getEyeLocation(), 4, 0.1, 0.1, 0.1, 0.05);
        p.playSound(p.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 0.6f, 1.6f);
    }

    private void fireHeavyBolt(Player p, double damage, boolean fullCharge) {
        // Apply Arcane Attunement (+10%)
        if (hasUpgrade(p, SLOT_ARCANE_ATTUNEMENT)) damage *= 1.10;
        // Apply Glass Cannon (+30%)
        if (hasUpgrade(p, SLOT_GLASS_CANNON))      damage *= 1.30;

        // Use SmallFireball for visual distinction on heavy bolts
        SmallFireball bolt = p.launchProjectile(SmallFireball.class, p.getLocation().getDirection().multiply(2.0));
        bolt.setMetadata(META_HEAVY_BOLT, new FixedMetadataValue(plugin, damage));
        bolt.setMetadata("cs_full_charge", new FixedMetadataValue(plugin, fullCharge));
        bolt.setIsIncendiary(false);
        bolt.setYield(0f);

        p.getWorld().spawnParticle(Particle.ENCHANT, p.getEyeLocation(), 12, 0.2, 0.2, 0.2, 0.1);
        p.playSound(p.getLocation(), Sound.ENTITY_BLAZE_SHOOT, 1f, 0.8f);
        if (fullCharge) {
            p.playSound(p.getLocation(), Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.4f, 1.8f);
            p.sendMessage("§5⚡ Full-charge Overload released!");
        }
    }

    // Instant half-damage spell (for Spellblade proc) — no projectile
    private void fireInstantSpell(Player caster, LivingEntity target, double damageMult) {
        double damage = BOLT_BASE_DAMAGE * damageMult;
        if (hasUpgrade(caster, SLOT_ARCANE_ATTUNEMENT)) damage *= 1.10;
        if (hasUpgrade(caster, SLOT_GLASS_CANNON))      damage *= 1.30;

        damageAsSpell(caster, target, damage);
        applySpellEffects(caster, target, false);

        target.getWorld().spawnParticle(Particle.WITCH,
                target.getLocation().add(0, 1, 0), 6, 0.2, 0.3, 0.2, 0.05);
        target.getWorld().playSound(target.getLocation(), Sound.ENTITY_BLAZE_HURT, 0.6f, 1.4f);
    }

    // ────────────────────────────────────────────────────────────────────────
    // ProjectileHitEvent — bolt impacts
    // ────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onProjectileHit(ProjectileHitEvent e) {
        if (!(e.getEntity() instanceof Projectile proj)) return;
        if (!(proj.getShooter() instanceof Player p)) return;
        if (!plugin.isWorldAllowed(p)) return;
        if (!isMagic(p)) return;

        boolean isBasic = proj.hasMetadata(META_BOLT);
        boolean isHeavy = proj.hasMetadata(META_HEAVY_BOLT);
        if (!isBasic && !isHeavy) return;

        LivingEntity victim = null;
        if (e.getHitEntity() instanceof LivingEntity le) victim = le;

        if (victim == null) {
            // Missed bolts do not create Gravity Well; that spell is a deliberate block click.
            return;
        }

        double damage = isBasic
                ? proj.getMetadata(META_BOLT).get(0).asDouble()
                : proj.getMetadata(META_HEAVY_BOLT).get(0).asDouble();

        // ── Volatile Casting: 15% chance for 1.5x damage ────────────────────
        boolean isCrit = false;
        if (hasUpgrade(p, SLOT_VOLATILE_CASTING) && Math.random() < 0.15) {
            damage *= 1.5;
            isCrit = true;
            p.sendMessage("§c⚡ Volatile Casting proc!");
            victim.getWorld().spawnParticle(Particle.CRIT,
                    victim.getLocation().add(0, 1, 0), 8, 0.2, 0.2, 0.2, 0.1);
        }

        // ── Chain Reaction: crit → deal half damage to nearest entity ────────
        if (isCrit && hasUpgrade(p, SLOT_CHAIN_REACTION)) {
            LivingEntity chainTarget = findNearestEntity(victim.getLocation(), 5.0, victim, p);
            if (chainTarget != null) {
                damageAsSpell(p, chainTarget, damage * 0.5);
                chainTarget.getWorld().spawnParticle(Particle.WITCH,
                        chainTarget.getLocation().add(0, 1, 0), 5, 0.1, 0.1, 0.1, 0.05);
                p.sendMessage("§c⛓ Chain Reaction!");
            }
        }

        // Apply damage
        damageAsSpell(p, victim, damage);

        // ── Apply on-hit effects ─────────────────────────────────────────────
        boolean isFullCharge = isHeavy && proj.hasMetadata("cs_full_charge")
                && proj.getMetadata("cs_full_charge").get(0).asBoolean();
        applySpellEffects(p, victim, isFullCharge);

        // ── Overload knockback (full charge) ─────────────────────────────────
        if (isFullCharge) {
            Vector kb = victim.getLocation().toVector()
                    .subtract(p.getLocation().toVector()).normalize().multiply(2.0);
            kb.setY(0.3);
            victim.setVelocity(kb);
        }

        // ── Pyroclasm: kill by spell → explosion ─────────────────────────────
        // handled in EntityDeathEvent

        // ── Singularity: Arcanist full-charge hit pulls nearby entities ──────
        if (isFullCharge && hasUpgrade(p, SLOT_ARCANIST_SUB)) {
            UUID id = p.getUniqueId();
            if (!onCooldown(singularityCooldown, id, CD_SINGULARITY_MS)) {
                singularityCooldown.put(id, now());
                doSingularity(p, victim.getLocation());
                p.sendMessage("§5✦ Singularity!");
            }
        }

        // Cancel the projectile damage (we applied it manually)
        // Actually for snowball we need EntityDamageByEntityEvent — see below.
        // SmallFireball default damage is overridden there too.
    }

    /**
     * Apply on-hit spell effects: Slow Field, Arcane Shackles,
     * Temporal Grasp debuff tracking, Arcane Embodiment Glowing.
     */
    private void applySpellEffects(Player caster, LivingEntity target, boolean isCrit) {
        // ── Slow Field: Slowness II for 2s ──────────────────────────────────
        if (hasUpgrade(caster, SLOT_SLOW_FIELD)) {
            target.addPotionEffect(new PotionEffect(
                    PotionEffectType.SLOWNESS, 40, 1, false, true, true));
            markDebuffed(target);
        }

        // ── Arcane Shackles: 20% chance to freeze 1.5s ──────────────────────
        if (hasArcaneShackles(caster) && Math.random() < 0.20) {
            // Simulate freeze: Slowness 255 + Mining Fatigue 255
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,    30, 255, false, false, false));
            target.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, 30, 255, false, false, false));
            markDebuffed(target);
            caster.sendMessage("§b❄ Arcane Shackles — target frozen!");
        }

        // ── Arcane Embodiment (Battlemage): crit → Glowing on target 3s ─────
        if (isCrit && hasUpgrade(caster, SLOT_BATTLEMAGE_SUB)) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING, 60, 0, false, false, true));
        }
    }

    private void markDebuffed(LivingEntity target) {
        debuffedTargets.add(target.getUniqueId());
        target.setMetadata(META_DEBUFFED, new FixedMetadataValue(plugin, now() + 3000L));
    }

    // ────────────────────────────────────────────────────────────────────────
    // EntityDamageByEntityEvent — snowball/fireball damage override
    // ────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onProjectileDamage(EntityDamageByEntityEvent e) {
        if (e.isCancelled()) return;

        Entity damager = e.getDamager();
        if (!(damager instanceof Projectile proj)) return;
        if (!(proj.getShooter() instanceof Player p)) return;
        if (!isMagic(p)) return;
        if (!plugin.isWorldAllowed(p)) return;
        boolean isBasic = damager.hasMetadata(META_BOLT);
        boolean isHeavy = damager.hasMetadata(META_HEAVY_BOLT);
        if (!isBasic && !isHeavy) return;

        // We already handled damage in ProjectileHitEvent via entity.damage()
        // Cancel the default projectile damage to avoid double-hitting
        e.setCancelled(true);
    }

    // ────────────────────────────────────────────────────────────────────────
    // EntityDamageByEntityEvent — incoming damage (Glass Cannon, Phantasm)
    // ────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerTakeDamage(EntityDamageByEntityEvent e) {
        if (e.isCancelled()) return;
        if (!(e.getEntity() instanceof Player victim)) return;
        if (!isMagic(victim)) return;
        if (!plugin.isWorldAllowed(victim)) return;

        double dmg = e.getDamage();

        // ── Glass Cannon: incoming damage +20% ───────────────────────────────
        if (hasUpgrade(victim, SLOT_GLASS_CANNON)) {
            dmg *= 1.20;
        }

        // ── Phantasm: 30% chance on taking damage to teleport + blind attacker
        if (hasUpgrade(victim, SLOT_PHANTASM) && Math.random() < 0.30) {
            doPhantasm(victim, e.getDamager());
        }

        e.setDamage(dmg);
    }

    // ────────────────────────────────────────────────────────────────────────
    // EntityDamageEvent — Temporal Grasp damage bonus
    // ────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onDebuffedEntityDamage(EntityDamageEvent e) {
        if (!(e.getEntity() instanceof LivingEntity le)) return;
        if (!debuffedTargets.contains(le.getUniqueId())) return;

        // Check Temporal Grasp is active on the damaging player
        // We boost all damage to debuffed targets of any magic player with the upgrade.
        // This is a broad check — fine for PvE; for PvP it benefits any attacker.
        if (!(e instanceof EntityDamageByEntityEvent edbe)) return;
        Entity attacker = edbe.getDamager();
        Player p = null;
        if (attacker instanceof Player pl) p = pl;
        else if (attacker instanceof Projectile proj && proj.getShooter() instanceof Player pl) p = pl;
        if (p == null || !isMagic(p)) return;

        if (hasUpgrade(p, SLOT_TEMPORAL_GRASP)) {
            e.setDamage(e.getDamage() * 1.20);
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // EntityDamageByEntityEvent — melee hits with staff (Spellblade)
    // ────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.NORMAL)
    public void onMeleeWithStaff(EntityDamageByEntityEvent e) {
        if (e.isCancelled()) return;
        if (!(e.getDamager() instanceof Player p)) return;
        if (!isMagic(p)) return;
        if (!plugin.isWorldAllowed(p)) return;
        if (!holdingStaff(p)) return;
        if (!(e.getEntity() instanceof LivingEntity target)) return;

        // ── Spellblade: 25% chance on melee hit → instant half-damage spell ──
        if (shouldRollSpellbladeProc(target.hasMetadata(META_SPELL_DAMAGE_EVENT),
                hasUpgrade(p, SLOT_SPELLBLADE), Math.random())) {
            fireInstantSpell(p, target, 0.5);
            p.sendMessage("§c⚔✦ Spellblade proc!");
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // EntityDeathEvent — Pyroclasm kill explosion
    // ────────────────────────────────────────────────────────────────────────

    @EventHandler
    public void onEntityDeath(EntityDeathEvent e) {
        LivingEntity dead = e.getEntity();
        Player killer = dead.getKiller();
        if (killer == null || !isMagic(killer)) return;
        if (!plugin.isWorldAllowed(killer)) return;

        if (hasUpgrade(killer, SLOT_PYROCLASM) && wasRecentlySpellDamagedBy(dead, killer)) {
            Location loc = dead.getLocation();
            loc.getWorld().createExplosion(loc, 2.0f, false, false);
            loc.getWorld().spawnParticle(Particle.EXPLOSION, loc, 2);
            killer.sendMessage("§c💥 Pyroclasm!");
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Gravity Well — deliberate block-click spell, place marker, pull entities
    // ────────────────────────────────────────────────────────────────────────

    /**
     * Triggered from onInteract when the player intentionally right-clicks a block.
     * Looking at terrain while casting a normal bolt should not steal the input.
     */
    private void activateGravityWell(Player p, Location target) {
        UUID id = p.getUniqueId();
        long rem = cdRemaining(gravityWellCooldown, id, CD_GRAVITY_WELL_MS);
        if (rem > 0) {
            sendCdMsg(p, "Gravity Well", rem);
            return;
        }
        if (!spendMana(p, 30.0)) return;
        gravityWellCooldown.put(id, now());

        p.sendMessage("§b⬇ Gravity Well placed!");
        p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 0.5f);

        final Location anchor = target.clone();
        // Particle marker
        anchor.getWorld().spawnParticle(Particle.PORTAL, anchor, 20, 0.3, 0.3, 0.3, 0.5);

        // Repeating task for 3 seconds pulling entities
        new BukkitRunnable() {
            int ticks = 0;
            @Override public void run() {
                if (ticks >= 60) { cancel(); return; } // 3 seconds
                ticks += 5;
                anchor.getWorld().spawnParticle(Particle.PORTAL, anchor, 8, 0.3, 0.3, 0.3, 0.2);
                for (Entity e : anchor.getWorld().getNearbyEntities(anchor, 5, 5, 5)) {
                    if (e.equals(p) || !(e instanceof LivingEntity)) continue;
                    Vector pull = anchor.toVector().subtract(e.getLocation().toVector()).normalize().multiply(0.35);
                    e.setVelocity(pull);
                }
            }
        }.runTaskTimer(plugin, 0L, 5L);
    }

    private void markSpellDamage(Player caster, LivingEntity target) {
        target.setMetadata(META_SPELL_CASTER, new FixedMetadataValue(plugin, caster.getUniqueId().toString()));
        target.setMetadata(META_SPELL_TIME, new FixedMetadataValue(plugin, now()));
    }

    private void damageAsSpell(Player caster, LivingEntity target, double damage) {
        markSpellDamage(caster, target);
        target.setMetadata(META_SPELL_DAMAGE_EVENT, new FixedMetadataValue(plugin, true));
        try {
            target.damage(damage, caster);
        } finally {
            target.removeMetadata(META_SPELL_DAMAGE_EVENT, plugin);
        }
    }

    private boolean wasRecentlySpellDamagedBy(LivingEntity target, Player caster) {
        if (!target.hasMetadata(META_SPELL_CASTER) || !target.hasMetadata(META_SPELL_TIME)) return false;
        String owner = target.getMetadata(META_SPELL_CASTER).get(0).asString();
        long spellTime = target.getMetadata(META_SPELL_TIME).get(0).asLong();
        return owner.equals(caster.getUniqueId().toString()) && isRecentSpellDamage(spellTime, now());
    }

    // ────────────────────────────────────────────────────────────────────────
    // Phase Step — teleport 6 blocks forward (Riftwalker)
    // ────────────────────────────────────────────────────────────────────────

    private void doPhaseStep(Player p) {
        Vector dir = p.getLocation().getDirection().setY(0).normalize();
        Location safe   = p.getLocation().clone();

        // Raycast 6 blocks — stop before solid block
        for (double d = 0.5; d <= 6.0; d += 0.5) {
            Location test = p.getLocation().clone().add(dir.clone().multiply(d));
            if (test.getBlock().isPassable()) {
                safe = test;
            } else {
                break;
            }
        }
        safe.setYaw(p.getLocation().getYaw());
        safe.setPitch(p.getLocation().getPitch());
        safe.setY(Math.floor(safe.getY())); // snap to ground

        p.teleport(safe);
        p.getWorld().spawnParticle(Particle.PORTAL, p.getLocation(), 15, 0.3, 0.5, 0.3, 0.5);
        p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 0.7f, 1.4f);
        p.sendMessage("§3⬡ Phase Step!");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Rift Anchor — set and recall (Riftwalker milestone)
    // ────────────────────────────────────────────────────────────────────────

    // Rift Anchor is activated via a second right-click (no staff charge).
    // We use a separate slot check — the milestone slot 4.
    // First right-click → set anchor. Second right-click within 45s → recall + explosion.
    // This is handled in onInteract — we route after Phase Step check.

    private void handleRiftAnchor(Player p) {
        UUID id = p.getUniqueId();

        // Check post-recall cooldown first
        if (onCooldown(riftAnchorCooldown, id, CD_RIFT_ANCHOR_POST_MS)) {
            sendCdMsg(p, "Rift Anchor", cdRemaining(riftAnchorCooldown, id, CD_RIFT_ANCHOR_POST_MS));
            return;
        }

        Location existing = riftAnchorLoc.get(id);
        Long anchorSet    = riftAnchorTime.get(id);

        if (existing != null && anchorSet != null && (now() - anchorSet) < CD_RIFT_ANCHOR_MS) {
            // Recall
            p.teleport(existing);
            p.getWorld().createExplosion(existing, 2.0f, false, false);
            p.getWorld().spawnParticle(Particle.EXPLOSION, existing, 3);
            p.playSound(existing, Sound.ENTITY_LIGHTNING_BOLT_THUNDER, 0.5f, 1.6f);
            p.sendMessage("§3⬡ Rift Anchor — recalled!");
            riftAnchorLoc.remove(id);
            riftAnchorTime.remove(id);
            riftAnchorCooldown.put(id, now()); // start post-recall cooldown
        } else {
            // Set anchor — snap Y to the highest solid block beneath the player
            // to prevent iterative upward drift from rapid set/recall cycles
            Location feet = p.getLocation().clone();
            feet.setY(feet.getWorld().getHighestBlockYAt(feet) + 1);
            feet.setYaw(p.getLocation().getYaw());
            feet.setPitch(p.getLocation().getPitch());

            riftAnchorLoc.put(id, feet);
            riftAnchorTime.put(id, now());
            p.getWorld().spawnParticle(Particle.PORTAL, p.getLocation(), 10, 0.2, 0.5, 0.2, 0.3);
            p.playSound(p.getLocation(), Sound.BLOCK_PORTAL_TRIGGER, 0.4f, 1.5f);
            p.sendMessage("§3⬡ Rift Anchor set! Left-click again within 45s to recall.");
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Phantasm — teleport + blind attacker
    // ────────────────────────────────────────────────────────────────────────

    private void doPhantasm(Player p, Entity attacker) {
        // Teleport 4 blocks in look direction
        Vector dir  = p.getLocation().getDirection().setY(0).normalize();
        Location safe = p.getLocation().clone();
        for (double d = 0.5; d <= 4.0; d += 0.5) {
            Location test = p.getLocation().clone().add(dir.clone().multiply(d));
            if (test.getBlock().isPassable()) safe = test;
            else break;
        }
        safe.setYaw(p.getLocation().getYaw());
        safe.setPitch(p.getLocation().getPitch());
        p.teleport(safe);
        p.getWorld().spawnParticle(Particle.WITCH, safe, 8, 0.2, 0.5, 0.2, 0.05);
        p.playSound(safe, Sound.ENTITY_ENDERMAN_TELEPORT, 0.5f, 1.6f);
        p.sendMessage("§b✦ Phantasm — vanished!");

        // Blind attacker for 2s
        if (attacker instanceof LivingEntity le) {
            le.addPotionEffect(new PotionEffect(PotionEffectType.BLINDNESS, 40, 0, false, true, true));
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // Singularity — pull all entities within 8 blocks toward target
    // ────────────────────────────────────────────────────────────────────────

    private void doSingularity(Player p, Location impact) {
        p.getWorld().spawnParticle(Particle.PORTAL, impact, 30, 0.5, 0.5, 0.5, 0.5);
        p.playSound(impact, Sound.ENTITY_ENDERMAN_STARE, 0.8f, 0.6f);

        for (Entity e : impact.getWorld().getNearbyEntities(impact, 8, 8, 8)) {
            if (e.equals(p) || !(e instanceof LivingEntity)) continue;
            Vector pull = impact.toVector()
                    .subtract(e.getLocation().toVector()).normalize().multiply(2.5);
            pull.setY(Math.max(0, pull.getY()) + 0.3);
            e.setVelocity(pull);
        }
        p.sendMessage("§5✦ Singularity — entities pulled! (60s cooldown)");
    }

    // ────────────────────────────────────────────────────────────────────────
    // Awakening milestone — persistent particle aura
    // ────────────────────────────────────────────────────────────────────────

    // Handled in periodic task (see startPeriodicTasks)

    // ────────────────────────────────────────────────────────────────────────
    // Utility
    // ────────────────────────────────────────────────────────────────────────

    private LivingEntity findNearestEntity(Location origin, double radius, Entity exclude, Player excludePlayer) {
        LivingEntity nearest = null;
        double minDist = radius + 1;
        for (Entity e : origin.getWorld().getNearbyEntities(origin, radius, radius, radius)) {
            if (e.equals(exclude) || e.equals(excludePlayer)) continue;
            if (!(e instanceof LivingEntity le)) continue;
            double d = e.getLocation().distance(origin);
            if (d < minDist) { minDist = d; nearest = le; }
        }
        return nearest;
    }

    // ────────────────────────────────────────────────────────────────────────
    // Periodic tasks: mana regen, aura, debuff cleanup
    // ────────────────────────────────────────────────────────────────────────

    private void startPeriodicTasks() {
        // Mana regeneration: 5 mana/s
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!isMagic(p)) continue;
                    double max  = getMaxMana(p);
                    double curr = getMana(p);
                    if (curr < max) {
                        double regen = MANA_REGEN_PASSIVE;
                        pdm.setMana(p, Math.min(max, curr + regen));
                        // Only show mana bar if it's already visible (task active)
                        if (manaBarTaskId.containsKey(p.getUniqueId())) {
                            sendManaBarPacket(p, getMana(p), max);
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L); // every second

        // Awakening aura: purple particles around awakened mages
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!shouldSpawnAwakeningAura(isMagic(p), hasUpgrade(p, SLOT_AWAKENING), plugin.isWorldAllowed(p))) continue;
                    p.getWorld().spawnParticle(Particle.WITCH,
                            p.getLocation().add(0, 1, 0), 2, 0.3, 0.5, 0.3, 0.01);
                }
            }
        }.runTaskTimer(plugin, 10L, 10L); // every 0.5s

        // Debuff expiry cleanup
        new BukkitRunnable() {
            @Override public void run() {
                debuffedTargets.removeIf(uuid -> {
                    Entity e = Bukkit.getEntity(uuid);
                    if (e == null || !(e instanceof LivingEntity le)) return true;
                    if (!le.hasMetadata(META_DEBUFFED)) return true;
                    long expiry = le.getMetadata(META_DEBUFFED).get(0).asLong();
                    if (now() > expiry) {
                        le.removeMetadata(META_DEBUFFED, plugin);
                        return true;
                    }
                    return false;
                });
            }
        }.runTaskTimer(plugin, 40L, 40L); // every 2s

        // Gravity Well routing lives in onInteract because it depends on the clicked block.
        // No separate task needed.
    }
}
