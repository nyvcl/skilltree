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
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.persistence.PersistentDataType;
import com.classystem.ClassSystemPlugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.*;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.*;

/**
 * Summoner Class — Full Redesign
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  STARTER WEAPON
 *  Bone tagged cs_summon_staff
 *  Right-click               → summon a zombie minion (up to cap, 2s cooldown)
 *  Right-click on mob        → mark target (Rite of Command, if unlocked)
 *  Shift+right-click         → active ability (context-sensitive) OR dismiss all
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  GENERAL BRANCH (shared foundation)
 *  slot 50  SOUL_BOND         — passively summon 1 zombie; respawns 30s after death
 *  slot 41  GRAVE_PACT        — minions +25% dmg, but owner takes 3 hearts when one dies
 *  slot 32  RITE_OF_COMMAND   — milestone: right-click mob with bone to mark it; all minions focus it
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  BRANCH A — TITAN (Quality / Control)
 *  slot 33  IRON_FLESH        — minion +100% max HP, knockback resistance, iron-armour skin
 *  slot 34  BLOOD_LINK        — branch milestone: heal 1 heart per 5s per living Titan minion
 *  slot 25  COLOSSUS_STRIKE   — minion attacks apply Slowness II 2s; 20% chance knockback
 *  slot 24  UNDYING_TITAN     — on death, minion enters 10s downed state (can't attack, won't die)
 *
 *  Subclass A1 — THE GUARDIAN  (milestone slot 6)
 *  slot 16  SENTINEL_PROTOCOL — taunt all enemies to target minion; owner gets 20% dmg reduction within 10 blocks
 *  slot 15  FORTRESS          — Stomp: Slowness III + Weakness in 6 blocks for 5s; brief owner invuln; 60s CD
 *
 *  Subclass A2 — THE REVENANT  (milestone slot 8)
 *  slot 17  SOUL_HARVEST      — kill → Soul Charge (max 5); each charge = +5% dmg; charges decay 15s with no kills
 *  slot 26  DEATH_MARK        — spend all charges; minion teleports to marked target and deals 3x dmg on next hit
 *
 * ══════════════════════════════════════════════════════════════════════════
 *  BRANCH B — SWARM (Quantity / Chaos)
 *  slot 31  RAISE_DEAD        — max minions +2 (becomes 3 total), -40% HP, respawn in 15s
 *  slot 30  PLAGUE_BEARER     — branch milestone: minion hits apply Poison I 3s; poisoned take +15% all dmg
 *  slot 21  TIDE_OF_FLESH     — max minions +2 more (becomes 5 total); 3+ hitting same target = Stun 1.5s
 *  slot 20  EXPENDABLE        — minion death explodes for 4 hearts in 3-block radius; Shift+right = manual detonate
 *
 *  Subclass C — THE PLAGUEMASTER  (milestone slot 2)
 *  slot 11  VIRULENT_SWARM    — minions spread Poison in 3-block aura every 2s (no attack needed), 6s duration
 *  slot 22  PANDEMIC          — all poisoned targets take instant dmg = remaining ticks + spread poison; 90s CD
 *
 *  Subclass D — THE WARCHANTER  (milestone slot 4)
 *  slot 13  BLOOD_FRENZY      — minion death = surviving minions +15% atk speed and +10% dmg for 10s, stacks
 *  slot 14  APOCALYPSE_WAVE   — sacrifice all minions, summon double for 20s at full frenzy stacks; 2 min CD
 */
public class SummonerAbilityListener implements Listener {

    // ─── NBT / metadata keys ─────────────────────────────────────────────────
    private static final String META_STAFF       = "cs_summon_staff";
    private static final String META_MINION      = "cs_minion";          // value = owner UUID string
    private static final String META_TEMP        = "cs_temp_minion";     // Apocalypse Wave temp minions
    private static final String META_SOUL_BOND   = "cs_soul_bond";       // Soul Bond permanent slot
    private static final String META_DOWNED      = "cs_downed";          // Undying Titan downed flag
    private static final String META_MARK_TARGET = "cs_mark_target";     // Rite of Command target flag
    private static final String META_DEATH_HIT   = "cs_death_mark_hit";  // Death Mark 3x flag

    // ─── Slot constants — General ─────────────────────────────────────────────
    private static final int SLOT_SOUL_BOND       = 50;
    private static final int SLOT_GRAVE_PACT      = 41;
    private static final int SLOT_RITE_OF_COMMAND = 32;

    // ─── Branch B — Titan ────────────────────────────────────────────────────
    private static final int SLOT_IRON_FLESH      = 31;
    private static final int SLOT_BLOOD_LINK      = 30;
    private static final int SLOT_COLOSSUS_STRIKE = 21;
    private static final int SLOT_UNDYING_TITAN   = 12;

    // Subclass B-A — Guardian (milestone slot 2)
    private static final int SLOT_GUARDIAN_MS        =  4;
    private static final int SLOT_SENTINEL_PROTOCOL  = 13;
    private static final int SLOT_FORTRESS           =  4;

    // Subclass B-B — Revenant (milestone slot 4)
    private static final int SLOT_REVENANT_MS        =  2;
    private static final int SLOT_SOUL_HARVEST       = 11;
    private static final int SLOT_DEATH_MARK         =  2;

    // ─── Branch A — Swarm ────────────────────────────────────────────────────
    private static final int SLOT_RAISE_DEAD      = 33;
    private static final int SLOT_PLAGUE_BEARER   = 34;
    private static final int SLOT_TIDE_OF_FLESH   = 25;
    private static final int SLOT_EXPENDABLE      = 16;

    // Subclass A-A — Plaguemaster (milestone slot 6)
    private static final int SLOT_PLAGUE_MS          =  6;
    private static final int SLOT_VIRULENT_SWARM     = 15;
    private static final int SLOT_PANDEMIC           =  6;

    // Subclass A-B — Warchanter (milestone slot 8)
    private static final int SLOT_WARCHANTER_MS      =  8;
    private static final int SLOT_BLOOD_FRENZY       = 17;
    private static final int SLOT_APOCALYPSE_WAVE    =  8;

    // ─── Minion caps ─────────────────────────────────────────────────────────
    private static final int BASE_CAP = 1; // +2 with Raise Dead, +2 with Tide of Flesh = 5 max

    // ─── Cooldowns (ms) ──────────────────────────────────────────────────────
    private static final long CD_SUMMON_MS         =  2_000L;
    private static final long CD_COMMAND_MS        = 10_000L;
    private static final long CD_FORTRESS_MS       = 60_000L;
    private static final long CD_PANDEMIC_MS       = 90_000L;
    private static final long CD_APOCALYPSE_MS     = 120_000L;
    private static final long CD_DETONATE_MS       =  3_000L;

    // ─── State maps ───────────────────────────────────────────────────────────
    private final Map<UUID, List<UUID>> playerMinions    = new HashMap<>();
    private final Map<UUID, UUID>       soulBondMinion   = new HashMap<>();
    private final Map<UUID, Long>       soulBondRespawnAt= new HashMap<>();

    private final Map<UUID, Long> summonCooldown         = new HashMap<>();
    private final Map<UUID, Long> commandCooldown        = new HashMap<>();
    private final Map<UUID, Long> fortressCooldown       = new HashMap<>();
    private final Map<UUID, Long> pandemicCooldown       = new HashMap<>();
    private final Map<UUID, Long> apocalypseCooldown     = new HashMap<>();
    private final Map<UUID, Long> detonateCooldown       = new HashMap<>();
    private final Map<UUID, Long> tideStunCooldown       = new HashMap<>(); // keyed by target UUID

    // Death Mark
    private final Map<UUID, UUID>    commandTarget        = new HashMap<>(); // player → marked entity
    private final Map<UUID, Integer> soulCharges          = new HashMap<>();
    private final Map<UUID, Long>    lastKillTime         = new HashMap<>();

    // Warchanter
    private final Map<UUID, Integer> frenzyStacks         = new HashMap<>();
    private final Map<UUID, Long>    frenzyLastDeathTime  = new HashMap<>();

    // Undying Titan
    private final Set<UUID> downedMinions                 = new HashSet<>();

    // Guardian invulnerability (brief, from Fortress)
    private final Map<UUID, Long> guardianInvulExpiry     = new HashMap<>();

    private final PlayerDataManager pdm;
    private final ClassSystemPlugin plugin;

    public SummonerAbilityListener(PlayerDataManager pdm, ClassSystemPlugin plugin) {
        this.pdm    = pdm;
        this.plugin = plugin;
        startPeriodicTasks();
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private boolean isSummoner(Player p) {
        return "summoner".equals(pdm.getClass(p));
    }

    private boolean isActiveSummoner(Player p) {
        return isSummoner(p) && plugin.isWorldAllowed(p);
    }

    private boolean holdingStaff(Player p) {
        ItemStack hand = p.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() != Material.BONE || !hand.hasItemMeta()) return false;
        return hand.getItemMeta().getPersistentDataContainer()
                   .has(new NamespacedKey(plugin, META_STAFF), PersistentDataType.BYTE);
    }

    private boolean hasUpgrade(Player p, int slot) {
        return pdm.getTreePanes(p).contains(slot) || pdm.getTreeItems(p).contains(slot);
    }

    static boolean isSoulBondRespawnDue(Long respawnAt, long currentTime) {
        return respawnAt == null || currentTime >= respawnAt;
    }

    static double bloodLinkOwnerDamageFromMinionAttack(double minionAttackDamage) {
        return 0.0;
    }

    private String subclass(Player p) { return pdm.getSubclass(p); }

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

    private void sendCd(Player p, String ability, long remMs) {
        p.sendActionBar("§c" + ability + " — " + String.format("%.1f", remMs / 1000.0) + "s");
    }

    private int minionCap(Player p) {
        int cap = BASE_CAP;
        if (hasUpgrade(p, SLOT_RAISE_DEAD))    cap += 2;
        if (hasUpgrade(p, SLOT_TIDE_OF_FLESH)) cap += 2;
        return cap;
    }

    /** Return tracked live minions, pruning stale entries. */
    private List<UUID> getMinions(Player p) {
        List<UUID> list = playerMinions.computeIfAbsent(p.getUniqueId(), k -> new ArrayList<>());
        list.removeIf(uid -> {
            Entity e = Bukkit.getEntity(uid);
            return e == null || !e.isValid() || e.isDead();
        });
        return list;
    }

    private boolean isMinion(Entity e) {
        return e.hasMetadata(META_MINION) || e.hasMetadata(META_TEMP);
    }

    private boolean isOwnerOf(Player p, Entity e) {
        if (!e.hasMetadata(META_MINION)) return false;
        return p.getUniqueId().toString().equals(e.getMetadata(META_MINION).get(0).asString());
    }

    private Player ownerOf(Entity e) {
        if (!e.hasMetadata(META_MINION)) return null;
        try {
            return Bukkit.getPlayer(UUID.fromString(e.getMetadata(META_MINION).get(0).asString()));
        } catch (Exception ex) { return null; }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Staff item factory
    // ─────────────────────────────────────────────────────────────────────────

    public static ItemStack createSummonStaff(JavaPlugin plugin) {
        ItemStack staff = new ItemStack(Material.BONE);
        ItemMeta meta   = staff.getItemMeta();
        assert meta != null;
        meta.setDisplayName("§dSummon Staff");
        meta.setLore(Arrays.asList(
                "§7A conduit for the restless dead.",
                "§dRight-click §7— Summon a minion",
                "§dRight-click mob §7— Mark target (Rite of Command)",
                "§dShift+Right §7— Active ability",
                "§dShift+Left §7— Dismiss all minions",
                "§8This staff binds souls to your will."
        ));
        meta.getPersistentDataContainer().set(
                new NamespacedKey(plugin, META_STAFF), PersistentDataType.BYTE, (byte) 1);
        staff.setItemMeta(meta);
        return staff;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Join / Respawn — staff distribution & Soul Bond init
    // ─────────────────────────────────────────────────────────────────────────

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        if (!isActiveSummoner(p)) return;
        ensureHasStaff(p);
        new BukkitRunnable() {
            @Override public void run() { startSoulBondIfNeeded(p); }
        }.runTaskLater(plugin, 20L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        Player p = e.getPlayer();
        if (!isSummoner(p)) return;
        new BukkitRunnable() {
            @Override public void run() {
                if (!p.isOnline()) return;
                removeTrackedMinions(p);
                if (!plugin.isWorldAllowed(p)) return;
                ensureHasStaff(p);
                soulCharges.remove(p.getUniqueId());
                frenzyStacks.remove(p.getUniqueId());
                startSoulBondIfNeeded(p);
            }
        }.runTaskLater(plugin, 2L);
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        Player p = e.getPlayer();
        if (!isSummoner(p)) return;
        if (!plugin.isWorldAllowed(p)) {
            removeTrackedMinions(p);
            return;
        }
        ensureHasStaff(p);
        startSoulBondIfNeeded(p);
    }

    private void ensureHasStaff(Player p) {
        for (ItemStack item : p.getInventory().getContents()) {
            if (isStaffItem(item)) return;
        }
        p.getInventory().addItem(createSummonStaff(plugin));
    }

    private boolean isStaffItem(ItemStack item) {
        if (item == null || item.getType() != Material.BONE || !item.hasItemMeta()) return false;
        return item.getItemMeta().getPersistentDataContainer()
                   .has(new NamespacedKey(plugin, META_STAFF), PersistentDataType.BYTE);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Soul Bond — passive permanent minion
    // ─────────────────────────────────────────────────────────────────────────

    private void startSoulBondIfNeeded(Player p) {
        if (!isActiveSummoner(p) || !hasUpgrade(p, SLOT_SOUL_BOND)) return;
        if (!isSoulBondRespawnDue(soulBondRespawnAt.get(p.getUniqueId()), now())) return;
        UUID cur = soulBondMinion.get(p.getUniqueId());
        if (cur != null) {
            Entity e = Bukkit.getEntity(cur);
            if (e != null && e.isValid() && !e.isDead()) return;
        }
        spawnSoulBondMinion(p);
    }

    private void spawnSoulBondMinion(Player p) {
        if (!isActiveSummoner(p)) return;
        Location loc = p.getLocation().add(p.getLocation().getDirection().multiply(2));
        loc.setY(p.getLocation().getY());
        Zombie z = buildMinion(p, loc);
        z.setMetadata(META_SOUL_BOND, new FixedMetadataValue(plugin, "true"));
        soulBondRespawnAt.remove(p.getUniqueId());
        soulBondMinion.put(p.getUniqueId(), z.getUniqueId());
        getMinions(p).add(z.getUniqueId());
        p.getWorld().spawnParticle(Particle.SOUL, loc.clone().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.05);
        p.sendMessage("§d[Soul Bond] Your bound minion has risen.");
    }

    private void scheduleSoulBondRespawn(Player owner) {
        soulBondMinion.remove(owner.getUniqueId());
        soulBondRespawnAt.put(owner.getUniqueId(), now() + 30L * 20L * 50L);
        new BukkitRunnable() {
            @Override public void run() {
                if (!owner.isOnline() || !isActiveSummoner(owner) || !hasUpgrade(owner, SLOT_SOUL_BOND)) return;
                startSoulBondIfNeeded(owner);
            }
        }.runTaskLater(plugin, 30L * 20L);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Main interaction handler
    // ─────────────────────────────────────────────────────────────────────────

    @EventHandler
    public void onSwing(PlayerAnimationEvent e) {
        Player p = e.getPlayer();
        if (!isActiveSummoner(p) || !holdingStaff(p)) return;
        if (!p.isSneaking()) return;
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;

        dismissAll(p);
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent e) {
        Player p = e.getPlayer();
        if (!isActiveSummoner(p) || !holdingStaff(p)) return;

        Action action = e.getAction();
        boolean isRight = action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK;
        boolean isLeft  = action == Action.LEFT_CLICK_AIR  || action == Action.LEFT_CLICK_BLOCK;

        if (!isRight && !isLeft) return;
        e.setCancelled(true);

        // Shift+right → active ability, or dismiss when no active ability applies.
        if (p.isSneaking() && isRight) {
            if (!handleActiveAbility(p)) {
                dismissAll(p);
            }
            return;
        }

        // Right-click near a mob → Rite of Command mark
        if (isRight && hasUpgrade(p, SLOT_RITE_OF_COMMAND)) {
            Entity targeted = getTargetEntity(p, 6);
            if (targeted != null) {
                handleMarkTarget(p, targeted);
                return;
            }
        }

        // Right-click (no mob targeted) → summon
        if (isRight) {
            UUID id = p.getUniqueId();
            long rem = cdRemaining(summonCooldown, id, CD_SUMMON_MS);
            if (rem > 0) { sendCd(p, "Summon", rem); return; }

            List<UUID> minions = getMinions(p);
            int cap = minionCap(p);
            if (minions.size() >= cap) {
                p.sendMessage("§dMinion cap (§f" + cap + "§d) reached. §8Shift+left-click to dismiss all.");
                return;
            }

            summonCooldown.put(id, now());
            spawnManualMinion(p);
        }
    }

    /** Soft entity lookahead — find a LivingEntity the player is looking toward within range. */
    private Entity getTargetEntity(Player p, double range) {
        var dir = p.getLocation().getDirection().normalize();
        for (Entity e : p.getWorld().getNearbyEntities(p.getLocation(), range, range, range)) {
            if (!(e instanceof LivingEntity) || e instanceof Player || isMinion(e)) continue;
            var toEnt = e.getLocation().subtract(p.getEyeLocation()).toVector().normalize();
            if (dir.dot(toEnt) > 0.75) return e;
        }
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Rite of Command — mark a target
    // ─────────────────────────────────────────────────────────────────────────

    private void handleMarkTarget(Player p, Entity target) {
        UUID id = p.getUniqueId();
        long rem = cdRemaining(commandCooldown, id, CD_COMMAND_MS);
        if (rem > 0) { sendCd(p, "Mark", rem); return; }
        commandCooldown.put(id, now());

        // Clear old mark
        UUID old = commandTarget.get(id);
        if (old != null) {
            Entity oldEnt = Bukkit.getEntity(old);
            if (oldEnt != null) oldEnt.removeMetadata(META_MARK_TARGET, plugin);
        }
        target.setMetadata(META_MARK_TARGET, new FixedMetadataValue(plugin, id.toString()));
        commandTarget.put(id, target.getUniqueId());

        // Focus all minions
        for (UUID uid : getMinions(p)) {
            Entity minion = Bukkit.getEntity(uid);
            if (minion instanceof Mob mob && target instanceof LivingEntity le) mob.setTarget(le);
        }

        target.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0, 1, 0), 14, 0.3, 0.5, 0.3, 0.05);
        p.playSound(p.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.8f, 1.3f);
        p.sendMessage("§d⚑ Target marked — all minions will converge!");
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Active ability dispatcher (Shift+right-click)
    // ─────────────────────────────────────────────────────────────────────────

    private boolean handleActiveAbility(Player p) {
        UUID id = p.getUniqueId();
        String sub = subclass(p);

        // Guardian — Fortress Stomp
        if ("guardian".equals(sub) && hasUpgrade(p, SLOT_FORTRESS)) {
            long rem = cdRemaining(fortressCooldown, id, CD_FORTRESS_MS);
            if (rem > 0) {
                p.sendActionBar("§cFortress — " + String.format("%.1f", rem / 1000.0) + "s §8(no charges — Shift+right to dismiss)");
                return true; // warn only; don't dismiss
            } else {
                fortressCooldown.put(id, now());
                doFortressStomp(p);
                return true;
            }
        }

        // Revenant — Death Mark
        else if ("revenant".equals(sub) && hasUpgrade(p, SLOT_DEATH_MARK)) {
            int charges = soulCharges.getOrDefault(id, 0);
            if (charges > 0) {
                doDeathMark(p);
                return true;
            }
            // No charges — warn and return; active subclass input remains reserved for Death Mark.
            p.sendActionBar("§cDeath Mark — no Soul Charges §8(no charges — Shift+right to dismiss)");
            return true;
        }

        // Plaguemaster — Pandemic
        else if ("plaguemaster".equals(sub) && hasUpgrade(p, SLOT_PANDEMIC)) {
            long rem = cdRemaining(pandemicCooldown, id, CD_PANDEMIC_MS);
            if (rem > 0) {
                p.sendActionBar("§cPandemic — " + String.format("%.1f", rem / 1000.0) + "s §8(Shift+right to dismiss)");
                return true; // warn only; don't dismiss
            } else {
                pandemicCooldown.put(id, now());
                doPandemic(p);
                return true;
            }
        }

        // Warchanter — Apocalypse Wave
        else if ("warchanter".equals(sub) && hasUpgrade(p, SLOT_APOCALYPSE_WAVE)) {
            long rem = cdRemaining(apocalypseCooldown, id, CD_APOCALYPSE_MS);
            if (rem > 0) {
                p.sendActionBar("§cApocalypse Wave — " + String.format("%.1f", rem / 1000.0) + "s §8(Shift+right to dismiss)");
                return true; // warn only; don't dismiss
            } else {
                apocalypseCooldown.put(id, now());
                doApocalypseWave(p);
                return true;
            }
        }

        // Expendable manual detonate is available after a subclass choice; earlier players
        // use the same input to dismiss their minions.
        else if (hasUpgrade(p, SLOT_EXPENDABLE) && !getMinions(p).isEmpty()
                && sub != null && !sub.isEmpty()) {
            long rem = cdRemaining(detonateCooldown, id, CD_DETONATE_MS);
            if (rem > 0) {
                p.sendActionBar("§cDetonate — " + String.format("%.1f", rem / 1000.0) + "s");
                return true; // warn only; don't dismiss
            } else {
                detonateCooldown.put(id, now());
                detonateNearestMinion(p);
                return true;
            }
        }

        return false;
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Minion summoning
    // ─────────────────────────────────────────────────────────────────────────

    /** Core zombie factory — applies all passive stat upgrades. */
    // ── Scoreboard team management ────────────────────────────────────────────
    private static final String TEAM_NAME = "cs_minions";

    /**
     * Adds both the owner (by name) and the zombie (by UUID string) to a shared
     * scoreboard team with friendly-fire disabled.  This prevents the zombie from
     * attacking the player and prevents the player from dealing direct damage to
     * the zombie via the normal attack path.
     */
    private void ensureMinionTeam(Player owner, Zombie z) {
        var sb = Bukkit.getScoreboardManager().getMainScoreboard();
        var team = sb.getTeam(TEAM_NAME);
        if (team == null) {
            team = sb.registerNewTeam(TEAM_NAME);
            // COLLISION_RULE stops minions from physically shoving the owner around.
            // Damage protection (minion↔owner) is handled in EntityDamageByEntityEvent.
            team.setOption(org.bukkit.scoreboard.Team.Option.COLLISION_RULE,
                           org.bukkit.scoreboard.Team.OptionStatus.NEVER);
        }
        team.addEntry(owner.getName());
        team.addEntry(z.getUniqueId().toString());
    }

    /** Remove a minion entity's UUID from the shared team when it dies/is removed. */
    private void removeMinionFromTeam(Entity z) {
        var sb = Bukkit.getScoreboardManager().getMainScoreboard();
        var team = sb.getTeam(TEAM_NAME);
        if (team != null) team.removeEntry(z.getUniqueId().toString());
    }

    private Zombie buildMinion(Player owner, Location loc) {
        Zombie z = (Zombie) owner.getWorld().spawnEntity(loc, EntityType.ZOMBIE);
        z.setCustomName("§d" + owner.getName() + "'s Minion");
        z.setCustomNameVisible(true);
        z.setRemoveWhenFarAway(false);
        z.setMetadata(META_MINION, new FixedMetadataValue(plugin, owner.getUniqueId().toString()));

        // ── Prevent burning in sunlight ───────────────────────────────────────
        z.addPotionEffect(new PotionEffect(PotionEffectType.FIRE_RESISTANCE,
                Integer.MAX_VALUE, 0, false, false, false));
        z.setVisualFire(false);

        // ── Scoreboard team (player-side only) ────────────────────────────────
        // Team friendly-fire only suppresses player<->player damage in Bukkit.
        // Minion-to-owner and owner-to-minion protection is handled exclusively
        // through EntityTargetLivingEntityEvent and EntityDamageByEntityEvent.
        ensureMinionTeam(owner, z);

        // ── Max HP ────────────────────────────────────────────────────────────
        var hpAttr = z.getAttribute(Attribute.GENERIC_MAX_HEALTH);
        if (hpAttr != null) {
            double hp = 20.0;
            if (hasUpgrade(owner, SLOT_IRON_FLESH)) hp *= 2.0;  // +100% HP
            if (hasUpgrade(owner, SLOT_RAISE_DEAD))  hp *= 0.60; // -40% HP
            hpAttr.setBaseValue(hp);
            z.setHealth(hp);
        }

        // ── Iron Flesh: knockback immunity + cosmetic armor ───────────────────
        if (hasUpgrade(owner, SLOT_IRON_FLESH)) {
            var kb = z.getAttribute(Attribute.GENERIC_KNOCKBACK_RESISTANCE);
            if (kb != null) kb.setBaseValue(1.0);
            if (z.getEquipment() != null) {
                z.getEquipment().setHelmet(new ItemStack(Material.IRON_HELMET));
                z.getEquipment().setChestplate(new ItemStack(Material.IRON_CHESTPLATE));
                z.getEquipment().setLeggings(new ItemStack(Material.IRON_LEGGINGS));
                z.getEquipment().setBoots(new ItemStack(Material.IRON_BOOTS));
            }
        }

        // ── Find initial target: marked entity first, then nearest hostile ────
        UUID markedUID = commandTarget.get(owner.getUniqueId());
        LivingEntity initialTarget = null;
        if (markedUID != null) {
            Entity markedEnt = Bukkit.getEntity(markedUID);
            if (markedEnt instanceof LivingEntity le && !le.isDead()) initialTarget = le;
        }
        if (initialTarget == null) {
            initialTarget = findNearestHostile(z, owner, 20);
        }
        if (initialTarget != null) z.setTarget(initialTarget);

        return z;
    }

    /**
     * Finds the nearest hostile mob within range blocks of origin
     * that is not a minion and not the owner.
     */
    private LivingEntity findNearestHostile(Entity origin, Player owner, double range) {
        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (Entity e : origin.getWorld().getNearbyEntities(origin.getLocation(), range, range, range)) {
            if (!(e instanceof Monster) || isMinion(e) || e.equals(owner)) continue;
            double d = e.getLocation().distanceSquared(origin.getLocation());
            if (d < bestDist) { bestDist = d; best = (LivingEntity) e; }
        }
        return best;
    }

    private void spawnManualMinion(Player p) {
        Location loc = p.getLocation().add(p.getLocation().getDirection().multiply(2));
        loc.setY(p.getLocation().getY());

        Zombie z = buildMinion(p, loc);
        getMinions(p).add(z.getUniqueId());

        p.getWorld().spawnParticle(Particle.SOUL, loc.clone().add(0, 1, 0), 10, 0.3, 0.5, 0.3, 0.05);
        p.getWorld().playSound(loc, Sound.ENTITY_ZOMBIE_AMBIENT, 0.8f, 0.7f);
        p.sendMessage("§dMinion summoned! §8(" + getMinions(p).size() + "/" + minionCap(p) + ")");
    }

    private void dismissAll(Player p) {
        for (UUID uid : new ArrayList<>(getMinions(p))) {
            Entity e = Bukkit.getEntity(uid);
            if (e != null) {
                e.getWorld().spawnParticle(Particle.SOUL, e.getLocation().add(0, 1, 0), 6, 0.2, 0.3, 0.2, 0.05);
                removeMinionFromTeam(e); // clean up scoreboard entry
                e.remove();
            }
        }
        playerMinions.remove(p.getUniqueId());
        soulBondMinion.remove(p.getUniqueId());
        soulBondRespawnAt.remove(p.getUniqueId());
        p.sendMessage("§dAll minions dismissed.");
        p.playSound(p.getLocation(), Sound.ENTITY_ZOMBIE_DEATH, 0.6f, 1.4f);

        if (hasUpgrade(p, SLOT_SOUL_BOND)) {
            scheduleSoulBondRespawn(p);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Active ability implementations
    // ─────────────────────────────────────────────────────────────────────────

    /** Guardian — Fortress Stomp: AoE Slowness III + Weakness; brief owner invuln. */
    private void doFortressStomp(Player p) {
        List<UUID> minions = getMinions(p);
        if (minions.isEmpty()) {
            p.sendMessage("§cFortress needs at least one active Titan minion!");
            fortressCooldown.remove(p.getUniqueId());
            return;
        }

        // Stomp from the nearest minion's position
        Location stompLoc = minions.stream()
                .map(Bukkit::getEntity).filter(Objects::nonNull)
                .min(Comparator.comparingDouble(e -> e.getLocation().distance(p.getLocation())))
                .map(Entity::getLocation).orElse(p.getLocation());

        for (Entity e : stompLoc.getWorld().getNearbyEntities(stompLoc, 6, 6, 6)) {
            if (!(e instanceof LivingEntity le) || e instanceof Player || isMinion(e)) continue;
            le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 5 * 20, 2, false, true, true));
            le.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 5 * 20, 0, false, true, true));
            e.getWorld().spawnParticle(Particle.CLOUD, e.getLocation().add(0, 1, 0), 6, 0.2, 0.3, 0.2, 0.02);
        }

        // 300ms invuln for the owner
        guardianInvulExpiry.put(p.getUniqueId(), now() + 300L);

        stompLoc.getWorld().spawnParticle(Particle.EXPLOSION, stompLoc.clone().add(0, 0.5, 0), 4, 0.5, 0.2, 0.5, 0.02);
        stompLoc.getWorld().playSound(stompLoc, Sound.ENTITY_IRON_GOLEM_ATTACK, 1.0f, 0.8f);
        p.sendMessage("§b⛨ FORTRESS! The ground shakes — enemies slowed and weakened!");
    }

    /** Revenant — Death Mark: spend all charges; minion teleports for 3× next hit. */
    private void doDeathMark(Player p) {
        UUID pid = p.getUniqueId();
        UUID targetUID = commandTarget.get(pid);

        if (targetUID == null) {
            p.sendMessage("§cNo marked target. Right-click a mob with your staff first.");
            return;
        }
        Entity target = Bukkit.getEntity(targetUID);
        if (target == null || target.isDead()) {
            commandTarget.remove(pid);
            p.sendMessage("§cYour marked target is dead. Mark a new one.");
            return;
        }

        int charges = soulCharges.getOrDefault(pid, 0);
        soulCharges.put(pid, 0);
        updateChargesActionBar(p, 0);

        List<UUID> minions = getMinions(p);
        if (minions.isEmpty()) {
            p.sendMessage("§cNo minions to execute Death Mark!");
            return;
        }

        // Teleport closest minion
        Entity closest = minions.stream().map(Bukkit::getEntity).filter(Objects::nonNull)
                .min(Comparator.comparingDouble(e -> e.getLocation().distance(target.getLocation())))
                .orElse(null);
        if (closest == null) return;

        closest.teleport(target.getLocation().clone().add(0.5, 0, 0.5));
        closest.setMetadata(META_DEATH_HIT, new FixedMetadataValue(plugin, charges));
        if (closest instanceof Mob mob && target instanceof LivingEntity le) mob.setTarget(le);

        target.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0, 1, 0), 20, 0.5, 1, 0.5, 0.1);
        closest.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, closest.getLocation().add(0, 1, 0), 10, 0.2, 0.4, 0.2, 0.05);
        p.sendMessage("§5💀 DEATH MARK! " + charges + " charges spent — executioner teleported!");
        p.playSound(p.getLocation(), Sound.ENTITY_WITHER_SHOOT, 0.6f, 1.4f);
    }

    /** Plaguemaster — Pandemic: detonate all active poison stacks; spread to nearby. */
    private void doPandemic(Player p) {
        int detonated = 0;
        for (Entity e : p.getWorld().getEntities()) {
            if (!(e instanceof LivingEntity le) || e instanceof Player || isMinion(e)) continue;
            PotionEffect poison = le.getPotionEffect(PotionEffectType.POISON);
            if (poison == null) continue;

            // Instant damage proportional to remaining ticks
            double instDmg = Math.min(poison.getDuration() * 0.025, 16.0); // cap at 8 hearts
            le.damage(instDmg);
            le.removePotionEffect(PotionEffectType.POISON);

            // Spread to nearby non-poisoned
            for (Entity nearby : le.getWorld().getNearbyEntities(le.getLocation(), 5, 5, 5)) {
                if (!(nearby instanceof LivingEntity nle) || nearby instanceof Player
                        || isMinion(nearby) || nle.hasPotionEffect(PotionEffectType.POISON)) continue;
                nle.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 6 * 20, 0, false, true, true));
                nearby.getWorld().spawnParticle(Particle.ITEM_SLIME, nearby.getLocation().add(0, 1, 0), 6, 0.2, 0.3, 0.2, 0.02);
            }
            le.getWorld().spawnParticle(Particle.ENTITY_EFFECT, le.getLocation().add(0, 1, 0), 14, 0.3, 0.6, 0.3, 0.05);
            detonated++;
        }

        if (detonated == 0) {
            p.sendMessage("§cNo poisoned targets — position your minions first!");
            pandemicCooldown.remove(p.getUniqueId());
            return;
        }
        p.sendMessage("§2☣ PANDEMIC! " + detonated + " target(s) detonated and plague spreads!");
        p.playSound(p.getLocation(), Sound.ENTITY_WITCH_CELEBRATE, 1f, 0.6f);
    }

    /** Warchanter — Apocalypse Wave: sacrifice all, summon double for 20s at full frenzy. */
    private void doApocalypseWave(Player p) {
        UUID pid = p.getUniqueId();
        int existingCount = getMinions(p).size();
        int waveCount = Math.max(existingCount * 2, 4); // at minimum 4

        // Dismiss all current with flair
        for (UUID uid : new ArrayList<>(getMinions(p))) {
            Entity e = Bukkit.getEntity(uid);
            if (e != null) {
                e.getWorld().spawnParticle(Particle.SOUL_FIRE_FLAME, e.getLocation().add(0, 1, 0), 10, 0.3, 0.5, 0.3, 0.05);
                e.remove();
            }
        }
        playerMinions.remove(pid);
        soulBondMinion.remove(pid);
        soulBondRespawnAt.remove(pid);

        // Max frenzy stacks
        frenzyStacks.put(pid, waveCount);
        frenzyLastDeathTime.put(pid, now());

        // Spawn wave minions (capped at 10)
        int spawnCount = Math.min(waveCount, 10);
        for (int i = 0; i < spawnCount; i++) {
            double angle = (2 * Math.PI / spawnCount) * i;
            Location loc = p.getLocation().clone().add(Math.cos(angle) * 2.5, 0, Math.sin(angle) * 2.5);
            Zombie z = buildMinion(p, loc);
            z.setMetadata(META_TEMP, new FixedMetadataValue(plugin, "true"));
            getMinions(p).add(z.getUniqueId());
            loc.getWorld().spawnParticle(Particle.SOUL, loc.clone().add(0, 1, 0), 8, 0.2, 0.3, 0.2, 0.05);
        }

        p.sendMessage("§c☠ APOCALYPSE WAVE! " + spawnCount + " risen — brimming with frenzy!");
        p.getWorld().playSound(p.getLocation(), Sound.ENTITY_WITHER_SPAWN, 0.7f, 1.2f);
        p.getWorld().spawnParticle(Particle.EXPLOSION_EMITTER, p.getLocation(), 2);

        // Auto-dismiss after 20s
        new BukkitRunnable() {
            @Override public void run() {
                for (UUID uid : new ArrayList<>(getMinions(p))) {
                    Entity e = Bukkit.getEntity(uid);
                    if (e != null && e.hasMetadata(META_TEMP) && isOwnerOf(p, e)) {
                        e.getWorld().spawnParticle(Particle.SOUL, e.getLocation().add(0, 1, 0), 6, 0.2, 0.3, 0.2, 0.05);
                        e.remove();
                    }
                }
                playerMinions.getOrDefault(pid, new ArrayList<>())
                        .removeIf(uid -> {
                            Entity e = Bukkit.getEntity(uid);
                            return e == null || e.hasMetadata(META_TEMP);
                        });
                if (p.isOnline()) p.sendMessage("§8The Apocalypse Wave fades.");
            }
        }.runTaskLater(plugin, 20 * 20L);
    }

    /** Expendable — detonate the nearest minion manually. */
    private void detonateNearestMinion(Player p) {
        Entity nearest = getMinions(p).stream().map(Bukkit::getEntity).filter(Objects::nonNull)
                .min(Comparator.comparingDouble(e -> e.getLocation().distance(p.getLocation())))
                .orElse(null);
        if (nearest == null) { p.sendMessage("§cNo minion to detonate!"); return; }
        explodeMinionAt(nearest.getLocation());
        nearest.remove();
        getMinions(p).remove(nearest.getUniqueId());
        p.sendMessage("§c💥 Minion detonated!");
        p.playSound(p.getLocation(), Sound.ENTITY_GENERIC_EXPLODE, 0.6f, 1.2f);
    }

    private void explodeMinionAt(Location loc) {
        loc.getWorld().createExplosion(loc, 2.0f, false, false);
        loc.getWorld().spawnParticle(Particle.SOUL, loc.clone().add(0, 1, 0), 10, 0.5, 0.5, 0.5, 0.05);
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Combat events
    // ─────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent e) {
        if (e.isCancelled()) return;
        Entity damager = e.getDamager();
        Entity victim  = e.getEntity();

        // ── Guard: player cannot damage their own minion ──────────────────────
        if (damager instanceof Player p && isMinion(victim) && isOwnerOf(p, victim)) {
            e.setCancelled(true);
            return;
        }

        // ── Guard: minion cannot damage its own owner ─────────────────────────
        if (isMinion(damager) && victim instanceof Player p && isOwnerOf(p, damager)) {
            e.setCancelled(true);
            return;
        }

        // ── A) Minion hits something ──────────────────────────────────────────
        if (isMinion(damager) && !(victim instanceof Player)) {
            Player owner = ownerOf(damager);
            if (owner == null) return;

            double dmg = e.getDamage();

            // Death Mark 3× hit
            if (damager.hasMetadata(META_DEATH_HIT)) {
                dmg *= 3.0;
                damager.removeMetadata(META_DEATH_HIT, plugin);
                victim.getWorld().spawnParticle(Particle.CRIT, victim.getLocation().add(0, 1, 0), 16, 0.3, 0.5, 0.3, 0.05);
                owner.sendMessage("§5💀 Death Mark struck — 3× damage!");
            }

            // Grave Pact: +25% minion damage
            if (hasUpgrade(owner, SLOT_GRAVE_PACT)) dmg *= 1.25;

            // Soul Harvest damage per charge
            if (hasUpgrade(owner, SLOT_SOUL_HARVEST)) {
                int charges = soulCharges.getOrDefault(owner.getUniqueId(), 0);
                if (charges > 0) dmg *= (1.0 + charges * 0.05);
            }

            // Blood Frenzy damage per stack
            if (hasUpgrade(owner, SLOT_BLOOD_FRENZY)) {
                int stacks = frenzyStacks.getOrDefault(owner.getUniqueId(), 0);
                if (stacks > 0) dmg *= (1.0 + stacks * 0.10);
            }

            // Plague Bearer: poisoned targets take +15% from all sources
            if (hasUpgrade(owner, SLOT_PLAGUE_BEARER) && victim instanceof LivingEntity lvic
                    && lvic.hasPotionEffect(PotionEffectType.POISON)) {
                dmg *= 1.15;
            }

            // Apply status effects
            if (victim instanceof LivingEntity le) {
                // Plague Bearer: Poison I 3s
                if (hasUpgrade(owner, SLOT_PLAGUE_BEARER))
                    le.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 3 * 20, 0, false, true, true));

                // Colossus Strike: Slowness II 2s + 20% knockback
                if (hasUpgrade(owner, SLOT_COLOSSUS_STRIKE)) {
                    le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 2 * 20, 1, false, true, true));
                    if (Math.random() < 0.20) {
                        var knockDir = victim.getLocation().subtract(damager.getLocation()).toVector().normalize().multiply(0.8).setY(0.3);
                        victim.setVelocity(knockDir);
                    }
                }

                // Tide of Flesh stun check
                if (hasUpgrade(owner, SLOT_TIDE_OF_FLESH)) checkTideStun(owner, le);
            }

            e.setDamage(dmg);
        }

        // ── B) Owner takes damage ─────────────────────────────────────────────
        if (victim instanceof Player p && isActiveSummoner(p)) {
            // Guardian brief invuln
            Long invul = guardianInvulExpiry.get(p.getUniqueId());
            if (invul != null && now() < invul) { e.setCancelled(true); return; }

            // Sentinel Protocol: 20% dmg reduction near minion
            if ("guardian".equals(subclass(p)) && hasUpgrade(p, SLOT_SENTINEL_PROTOCOL)) {
                boolean near = getMinions(p).stream().anyMatch(uid -> {
                    Entity me = Bukkit.getEntity(uid);
                    return me != null && me.getLocation().distance(p.getLocation()) <= 10;
                });
                if (near) e.setDamage(e.getDamage() * 0.80);
            }
        }

        // ── C) A minion takes damage ───────────────────────────────────────────
        if (isMinion(victim)) {
            Player owner = ownerOf(victim);
            if (owner == null) return;

            // Undying Titan: intercept lethal damage → downed state
            if (hasUpgrade(owner, SLOT_UNDYING_TITAN)
                    && !downedMinions.contains(victim.getUniqueId())
                    && victim instanceof LivingEntity le) {
                if (le.getHealth() - e.getFinalDamage() <= 0) {
                    e.setCancelled(true);
                    enterDownedState(owner, victim, le);
                }
            }
        }
    }

    private void enterDownedState(Player owner, Entity minion, LivingEntity le) {
        downedMinions.add(minion.getUniqueId());
        le.setHealth(0.5);
        le.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 10 * 20, 255, false, false, false));
        le.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, 10 * 20, 255, false, false, false));
        minion.getWorld().spawnParticle(Particle.SOUL, minion.getLocation().add(0, 1, 0), 8, 0.3, 0.5, 0.3, 0.05);
        owner.sendMessage("§6⚠ Your Titan is downed! 10s to save it — heal it now!");

        new BukkitRunnable() {
            @Override public void run() {
                downedMinions.remove(minion.getUniqueId());
                if (!minion.isValid() || minion.isDead()) return;
                minion.remove();
                if (owner.isOnline()) owner.sendMessage("§8Your downed Titan collapsed.");
            }
        }.runTaskLater(plugin, 10 * 20L);
    }

    private void checkTideStun(Player owner, LivingEntity target) {
        if (onCooldown(tideStunCooldown, target.getUniqueId(), 3_000L)) return;
        long nearCount = getMinions(owner).stream().filter(uid -> {
            Entity me = Bukkit.getEntity(uid);
            return me != null && me.getLocation().distance(target.getLocation()) <= 5;
        }).count();
        if (nearCount >= 3) {
            tideStunCooldown.put(target.getUniqueId(), now());
            target.addPotionEffect(new PotionEffect(PotionEffectType.MINING_FATIGUE, (int)(1.5 * 20), 2, false, true, true));
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, (int)(1.5 * 20), 2, false, true, true));
            target.getWorld().spawnParticle(Particle.CRIT, target.getLocation().add(0, 1, 0), 12, 0.3, 0.5, 0.3, 0.05);
            owner.sendMessage("§e⚡ Tide of Flesh — target stunned by the swarm!");
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Entity targeting — Sentinel Protocol taunt
    // ─────────────────────────────────────────────────────────────────────────

    @EventHandler(priority = EventPriority.HIGH)
    public void onEntityTargetLiving(EntityTargetLivingEntityEvent e) {
        Entity attacker = e.getEntity();
        LivingEntity target = e.getTarget();

        // ── Case A: a minion is trying to target its owner → cancel and redirect ─
        if (isMinion(attacker) && target instanceof Player p && isOwnerOf(p, attacker)) {
            LivingEntity hostile = findNearestHostile(attacker, p, 20);
            if (hostile != null) {
                e.setTarget(hostile);
            } else {
                e.setCancelled(true);
            }
            return;
        }

        // ── Case B: a hostile mob is targeting a summoner player ───────────────
        if (!(attacker instanceof Mob mob) || isMinion(attacker)) return;
        if (!(target instanceof Player p) || !isActiveSummoner(p)) return;

        List<UUID> minions = getMinions(p);
        if (minions.isEmpty()) return;

        // Guardian Sentinel Protocol: always redirect aggro to closest minion
        if ("guardian".equals(subclass(p)) && hasUpgrade(p, SLOT_SENTINEL_PROTOCOL)) {
            LivingEntity closest = minions.stream().map(Bukkit::getEntity).filter(Objects::nonNull)
                    .filter(en -> en instanceof LivingEntity)
                    .min(Comparator.comparingDouble(en -> en.getLocation().distance(mob.getLocation())))
                    .map(en -> (LivingEntity) en)
                    .orElse(null);
            if (closest != null) { e.setTarget(closest); return; }
        }

        // All summoners: 60% chance hostile targets a minion instead of the player
        if (Math.random() < 0.60) {
            LivingEntity closest = minions.stream().map(Bukkit::getEntity).filter(Objects::nonNull)
                    .filter(en -> en instanceof LivingEntity)
                    .min(Comparator.comparingDouble(en -> en.getLocation().distance(mob.getLocation())))
                    .map(en -> (LivingEntity) en)
                    .orElse(null);
            if (closest != null) e.setTarget(closest);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Entity death
    // ─────────────────────────────────────────────────────────────────────────

    @EventHandler
    public void onEntityDeath(EntityDeathEvent e) {
        LivingEntity dead = e.getEntity();

        // Who killed it?
        Entity killer = null;
        if (dead.getLastDamageCause() instanceof EntityDamageByEntityEvent edbe) killer = edbe.getDamager();

        // ── Minion scored kill → Soul Harvest charge ──────────────────────────
        if (killer != null && isMinion(killer)) {
            Player owner = ownerOf(killer);
            if (owner != null && isActiveSummoner(owner) && hasUpgrade(owner, SLOT_SOUL_HARVEST)) awardSoulCharge(owner);
        }

        // ── Player scored kill → Soul Harvest charge ──────────────────────────
        if (dead.getKiller() instanceof Player pk && isActiveSummoner(pk)
                && hasUpgrade(pk, SLOT_SOUL_HARVEST)) {
            awardSoulCharge(pk);
        }

        // ── A minion died ─────────────────────────────────────────────────────
        if (isMinion(dead)) {
            Player owner = ownerOf(dead);
            downedMinions.remove(dead.getUniqueId());

            // Clean up scoreboard team entry
            removeMinionFromTeam(dead);

            if (owner != null && !dead.hasMetadata(META_TEMP)) {
                getMinions(owner).remove(dead.getUniqueId());
                owner.sendMessage("§8A minion has fallen. §d(" + getMinions(owner).size() + "/" + minionCap(owner) + ")");

                // Grave Pact: owner takes 3 hearts (6 HP)
                if (hasUpgrade(owner, SLOT_GRAVE_PACT) && !owner.isDead()) {
                    owner.damage(6.0);
                    owner.sendMessage("§4[Grave Pact] A soul lost — you pay the price!");
                }

                // Expendable: explode
                if (hasUpgrade(owner, SLOT_EXPENDABLE)) {
                    explodeMinionAt(dead.getLocation());
                    owner.sendMessage("§c💥 Expendable — minion exploded!");
                }

                // Blood Frenzy (Warchanter): surviving minions gain a stack
                if (hasUpgrade(owner, SLOT_BLOOD_FRENZY)) {
                    int cur = Math.min(frenzyStacks.getOrDefault(owner.getUniqueId(), 0) + 1, 10);
                    frenzyStacks.put(owner.getUniqueId(), cur);
                    frenzyLastDeathTime.put(owner.getUniqueId(), now());
                    // Speed buff on survivors
                    for (UUID uid : getMinions(owner)) {
                        Entity s = Bukkit.getEntity(uid);
                        if (s instanceof LivingEntity sle)
                            sle.addPotionEffect(new PotionEffect(PotionEffectType.HASTE, 10 * 20, 0, false, true, true));
                    }
                    owner.sendMessage("§c🔥 Blood Frenzy stack " + cur + " — survivors gain power!");
                }

                // Soul Bond respawn
                if (dead.hasMetadata(META_SOUL_BOND) && hasUpgrade(owner, SLOT_SOUL_BOND))
                    scheduleSoulBondRespawn(owner);
            }

            // Temp (Apocalypse Wave) minion death explosion
            if (dead.hasMetadata(META_TEMP)) {
                dead.getWorld().createExplosion(dead.getLocation(), 2.0f, false, false);
                dead.getWorld().spawnParticle(Particle.SOUL, dead.getLocation(), 8, 0.3, 0.5, 0.3, 0.05);
                Player tempOwner = ownerOf(dead);
                if (tempOwner != null) tempOwner.sendMessage("§8💀 Wave minion exploded!");
            }
        }

        // ── Clear Rite of Command mark if the target dies ─────────────────────
        if (dead.hasMetadata(META_MARK_TARGET)) {
            String ownerStr = dead.getMetadata(META_MARK_TARGET).get(0).asString();
            try {
                UUID oid = UUID.fromString(ownerStr);
                commandTarget.remove(oid);
                Player owner = Bukkit.getPlayer(oid);
                if (owner != null) owner.sendMessage("§5Your marked target has been slain.");
            } catch (Exception ignored) {}
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Soul Harvest helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void awardSoulCharge(Player p) {
        UUID pid = p.getUniqueId();
        // Decay if stale
        Long last = lastKillTime.get(pid);
        if (last != null && (now() - last) > 15_000L) soulCharges.put(pid, 0);

        int cur = Math.min(soulCharges.getOrDefault(pid, 0) + 1, 5);
        soulCharges.put(pid, cur);
        lastKillTime.put(pid, now());
        updateChargesActionBar(p, cur);
    }

    private void updateChargesActionBar(Player p, int charges) {
        StringBuilder sb = new StringBuilder("§5Soul Charges §f[");
        for (int i = 0; i < 5; i++) sb.append(i < charges ? "§d●" : "§8○");
        sb.append("§f] §7(").append(charges).append("/5 — ").append((int)(charges * 5)).append("% dmg)");
        p.sendActionBar(sb.toString());
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Periodic background tasks
    // ─────────────────────────────────────────────────────────────────────────

    public void dismissAllMinionsOnReset(Player p) {
        removeTrackedMinions(p);
        soulCharges.remove(p.getUniqueId());
        frenzyStacks.remove(p.getUniqueId());
        frenzyLastDeathTime.remove(p.getUniqueId());
        commandTarget.remove(p.getUniqueId());
        downedMinions.removeIf(uid -> {
            Entity e = Bukkit.getEntity(uid);
            return e == null || ownerOf(e) == null || ownerOf(e).equals(p);
        });
    }

    private void removeTrackedMinions(Player p) {
        for (UUID uid : new ArrayList<>(getMinions(p))) {
            Entity e = Bukkit.getEntity(uid);
            if (e != null) {
                removeMinionFromTeam(e);
                e.remove();
            }
        }
        playerMinions.remove(p.getUniqueId());
        soulBondMinion.remove(p.getUniqueId());
        soulBondRespawnAt.remove(p.getUniqueId());
    }

    private void startPeriodicTasks() {

        // Leash — teleport stray minions back every second
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!isActiveSummoner(p)) continue;
                    double leash = 20.0;
                    for (UUID uid : getMinions(p)) {
                        Entity e = Bukkit.getEntity(uid);
                        if (!(e instanceof Mob mob)) continue;
                        if (mob.getLocation().distance(p.getLocation()) > leash) {
                            Location recall = p.getLocation().clone().add(
                                    p.getLocation().getDirection().multiply(-1.5));
                            mob.teleport(recall);
                            mob.getWorld().spawnParticle(Particle.PORTAL, recall.clone().add(0, 1, 0), 6, 0.2, 0.3, 0.2, 0.2);
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);

        // Blood Link heal — 1 heart per 5s per living Titan minion
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!isActiveSummoner(p) || !hasUpgrade(p, SLOT_BLOOD_LINK) || p.isDead()) continue;
                    int alive = getMinions(p).size();
                    if (alive > 0) {
                        double maxHp = p.getAttribute(Attribute.GENERIC_MAX_HEALTH).getValue();
                        p.setHealth(Math.min(maxHp, p.getHealth() + alive * 2.0));
                    }
                }
            }
        }.runTaskTimer(plugin, 100L, 100L);

        // Soul Harvest decay — check every 5s
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!isActiveSummoner(p) || !hasUpgrade(p, SLOT_SOUL_HARVEST)) continue;
                    Long last = lastKillTime.get(p.getUniqueId());
                    if (last != null && (now() - last) > 15_000L && soulCharges.getOrDefault(p.getUniqueId(), 0) > 0) {
                        soulCharges.put(p.getUniqueId(), 0);
                        p.sendMessage("§8Soul Charges faded — no kills for 15s.");
                    }
                }
            }
        }.runTaskTimer(plugin, 100L, 100L);

        // Virulent Swarm aura — apply Poison in 3-block radius every 2s
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!isActiveSummoner(p) || !"plaguemaster".equals(subclass(p))
                            || !hasUpgrade(p, SLOT_VIRULENT_SWARM)) continue;
                    for (UUID uid : getMinions(p)) {
                        Entity minion = Bukkit.getEntity(uid);
                        if (minion == null) continue;
                        for (Entity nearby : minion.getWorld().getNearbyEntities(minion.getLocation(), 3, 3, 3)) {
                            if (!(nearby instanceof LivingEntity le) || nearby instanceof Player || isMinion(nearby)) continue;
                            le.addPotionEffect(new PotionEffect(PotionEffectType.POISON, 6 * 20, 0, false, true, true));
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 10L, 40L);

        // Soul Bond integrity check — every 3s
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!isSummoner(p)) continue;
                    if (!plugin.isWorldAllowed(p)) {
                        removeTrackedMinions(p);
                        continue;
                    }
                    if (!hasUpgrade(p, SLOT_SOUL_BOND)) continue;
                    if (!isSoulBondRespawnDue(soulBondRespawnAt.get(p.getUniqueId()), now())) continue;
                    UUID cur = soulBondMinion.get(p.getUniqueId());
                    if (cur == null) { startSoulBondIfNeeded(p); continue; }
                    Entity e = Bukkit.getEntity(cur);
                    if (e == null || e.isDead()) soulBondMinion.remove(p.getUniqueId());
                }
            }
        }.runTaskTimer(plugin, 60L, 60L);

        // Rite of Command re-targeting — every second
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!isActiveSummoner(p) || !hasUpgrade(p, SLOT_RITE_OF_COMMAND)) continue;
                    UUID markedUID = commandTarget.get(p.getUniqueId());
                    if (markedUID == null) continue;
                    Entity target = Bukkit.getEntity(markedUID);
                    if (target == null || target.isDead()) { commandTarget.remove(p.getUniqueId()); continue; }
                    for (UUID uid : getMinions(p)) {
                        Entity minion = Bukkit.getEntity(uid);
                        if (minion instanceof Mob mob && target instanceof LivingEntity le)
                            if (mob.getTarget() == null || mob.getTarget().isDead()) mob.setTarget(le);
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);

        // Warchanter frenzy stack decay — 10s after last minion death
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!isActiveSummoner(p) || !hasUpgrade(p, SLOT_BLOOD_FRENZY)) continue;
                    Long last = frenzyLastDeathTime.get(p.getUniqueId());
                    if (last != null && (now() - last) > 10_000L) {
                        if (frenzyStacks.getOrDefault(p.getUniqueId(), 0) > 0) {
                            frenzyStacks.put(p.getUniqueId(), 0);
                            frenzyLastDeathTime.remove(p.getUniqueId());
                            p.sendMessage("§8Frenzy stacks expired.");
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 40L, 40L);

        // Idle re-targeting — every 2s: if a minion has no target (or its target died/is owner),
        // find the nearest hostile mob and assign it.  This is the primary mechanism
        // that makes minions proactively fight nearby enemies rather than standing idle.
        new BukkitRunnable() {
            @Override public void run() {
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (!isActiveSummoner(p)) continue;
                    UUID markedUID = commandTarget.get(p.getUniqueId());
                    for (UUID uid : new ArrayList<>(getMinions(p))) {
                        Entity minion = Bukkit.getEntity(uid);
                        if (!(minion instanceof Mob mob)) continue;

                        // If there is a Rite of Command mark, always honour it
                        if (markedUID != null) {
                            Entity marked = Bukkit.getEntity(markedUID);
                            if (marked instanceof LivingEntity le && !le.isDead()) {
                                mob.setTarget(le);
                                continue;
                            }
                        }

                        // Re-target if idle, dead-target, or accidentally targeting the owner
                        LivingEntity cur = mob.getTarget();
                        if (cur == null || cur.isDead() || cur.equals(p)) {
                            LivingEntity hostile = findNearestHostile(mob, p, 20);
                            mob.setTarget(hostile); // null clears target — that is fine
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 40L);
    }
}
