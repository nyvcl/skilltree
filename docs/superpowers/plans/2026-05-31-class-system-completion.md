# Class System Completion Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Bring the Minecraft class/skill-tree plugin from playtest prototype to a complete, verifiable system matching the owner's design.

**Architecture:** Keep the current config-driven tree model, PDC-backed player progress, Vault economy purchases, and per-class ability listeners. Complete the missing production wiring around LuckPerms rewards, add regression tests for parser/progression rules, then validate combat behavior with a manual Paper-server playtest matrix.

**Tech Stack:** Java 21, Paper API 1.21.1, Maven 3.9.16, Vault API, LuckPerms API, Bukkit PersistentDataContainer.

---

## Current Baseline

- Maven is installed at `C:\tmp\apache-maven-3.9.16`.
- `C:\tmp\apache-maven-3.9.16\bin\mvn.cmd -q test` passes.
- The repo has no meaningful automated test suite yet.
- LuckPerms group grant/removal calls are intentionally commented out in purchase/reset flows.
- The screenshot `C:\Users\LENOVO\Downloads\Screenshot_2026-05-31_at_1.22.55_AM.png` matches the implemented `SkillTreeGui` layout.

## File Structure

- Modify `pom.xml`: add test dependencies and keep compiler/shade settings stable.
- Modify `src/main/java/com/classystem/gui/ClassSelectGui.java`: restore base class LP group grant after selecting a class.
- Modify `src/main/java/com/classystem/gui/SkillTreeClickHandler.java`: restore LP group grants for milestones, subclass unlock/switch rules, and general upgrades.
- Modify `src/main/java/com/classystem/command/ResetClassCommand.java`: restore LP cleanup on reset and remove selected-class items where required.
- Modify `src/main/java/com/classystem/data/ClassRegistry.java`: harden config parsing and fail loudly for malformed tree nodes.
- Modify `src/main/java/com/classystem/gui/SkillTreeGui.java`: fix visual clarity and slot/lore consistency for branch lockouts, costs, and scrollable general upgrades.
- Modify `src/main/java/com/classystem/listener/MeleeAbilityListener.java`: playtest and fix melee ability conflicts.
- Modify `src/main/java/com/classystem/listener/RangedAbilityListener.java`: playtest and fix ranged projectile behavior.
- Modify `src/main/java/com/classystem/listener/MagicAbilityListener.java`: playtest and fix staff/mana/spell behavior.
- Modify `src/main/java/com/classystem/listener/SummonerAbilityListener.java`: playtest and fix minion lifecycle, targeting, and reset cleanup.
- Create `src/test/java/com/classystem/data/ClassRegistryTest.java`: config parser coverage.
- Create `src/test/java/com/classystem/gui/SkillTreeProgressionTest.java`: tree purchase gate coverage with fakes or isolated helper extraction.
- Create `docs/playtest/class-system-playtest-checklist.md`: manual verification matrix for owner/server testing.

---

### Task 1: Add a Real Test Harness

**Files:**
- Modify: `pom.xml`
- Create: `src/test/java/com/classystem/data/ClassRegistryTest.java`

- [ ] **Step 1: Add JUnit 5 to Maven**

Add these dependencies to `pom.xml` under `<dependencies>`:

```xml
<dependency>
    <groupId>org.junit.jupiter</groupId>
    <artifactId>junit-jupiter</artifactId>
    <version>5.11.4</version>
    <scope>test</scope>
</dependency>
```

Add Surefire under `<plugins>`:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <version>3.5.2</version>
    <configuration>
        <useModulePath>false</useModulePath>
    </configuration>
</plugin>
```

- [ ] **Step 2: Verify the empty test harness**

Run:

```powershell
C:\tmp\apache-maven-3.9.16\bin\mvn.cmd test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 3: Add parser smoke tests**

Create `src/test/java/com/classystem/data/ClassRegistryTest.java`:

```java
package com.classystem.data;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

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
}
```

- [ ] **Step 4: Run tests**

Run:

```powershell
C:\tmp\apache-maven-3.9.16\bin\mvn.cmd test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```powershell
git add pom.xml src/test/java/com/classystem/data/ClassRegistryTest.java
git commit -m "test: add class system parser smoke tests"
```

---

### Task 2: Restore LuckPerms Reward Wiring

**Files:**
- Modify: `src/main/java/com/classystem/gui/ClassSelectGui.java`
- Modify: `src/main/java/com/classystem/gui/SkillTreeClickHandler.java`
- Modify: `src/main/java/com/classystem/command/ResetClassCommand.java`

- [ ] **Step 1: Restore base class grant**

In `ClassSelectGui.confirmSelection`, replace:

```java
// [DEBUG] LP group switching disabled for testing — restore before production
// lp.addGroup(player, def.lpGroup());
```

with:

```java
lp.addGroup(player, def.lpGroup());
```

- [ ] **Step 2: Restore milestone and general upgrade grants**

In `SkillTreeClickHandler.handleGenUpgrade`, replace:

```java
// [DEBUG] LP group switching disabled for testing — restore before production
// lp.addGroup(player, upg.lpGroup());
```

with:

```java
lp.addGroup(player, upg.lpGroup());
```

In `SkillTreeClickHandler.buyMilestone`, replace:

```java
// [DEBUG] LP group switching disabled for testing — restore before production
// lp.addGroup(player, lpGroup);
```

with:

```java
lp.addGroup(player, lpGroup);
```

- [ ] **Step 3: Fix subclass switching group behavior**

When switching to an already purchased subclass, remove other subclass groups in the same class tree before adding the selected subclass group. Add a private helper in `SkillTreeClickHandler`:

```java
private void switchSubclassGroup(Player player, ClassDef classDef, SubclassDef activeSub) {
    for (BranchDef branch : List.of(classDef.tree().branchA(), classDef.tree().branchB())) {
        if (branch == null) continue;
        for (SubclassDef sub : List.of(branch.subclassA(), branch.subclassB())) {
            if (sub == null) continue;
            if (!sub.id().equals(activeSub.id())) {
                lp.removeGroup(player, sub.lpGroup());
            }
        }
    }
    lp.addGroup(player, activeSub.lpGroup());
}
```

Call it after `pdm.setSubclass(player, sub.id())` in both subclass switch and first-purchase paths.

- [ ] **Step 4: Restore reset cleanup**

In `ResetClassCommand.executeReset`, uncomment each intended `lp.removeGroup(...)` call:

```java
lp.removeGroup(player, classDef.lpGroup());
lp.removeGroup(player, tm.lpGroup());
lp.removeGroup(player, bm.lpGroup());
lp.removeGroup(player, sub.lpGroup());
lp.removeGroup(player, upg.lpGroup());
```

- [ ] **Step 5: Run build**

Run:

```powershell
C:\tmp\apache-maven-3.9.16\bin\mvn.cmd test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 6: Manual server verification**

On a Paper server with Vault, an economy provider, and LuckPerms:

```text
/selectclass
/skilltree
/lp user <player> info
/resetclass confirm
/lp user <player> info
```

Expected: class/group rewards appear after selection/purchase and are removed after reset.

- [ ] **Step 7: Commit**

```powershell
git add src/main/java/com/classystem/gui/ClassSelectGui.java src/main/java/com/classystem/gui/SkillTreeClickHandler.java src/main/java/com/classystem/command/ResetClassCommand.java
git commit -m "fix: restore LuckPerms class reward wiring"
```

---

### Task 3: Harden Purchase Progression

**Files:**
- Modify: `src/main/java/com/classystem/gui/SkillTreeClickHandler.java`
- Modify: `src/main/java/com/classystem/gui/SkillTreeGui.java`
- Create: `src/test/java/com/classystem/gui/SkillTreeProgressionTest.java`

- [ ] **Step 1: Add progression tests for branch commitment**

Create `SkillTreeProgressionTest.java`:

```java
package com.classystem.gui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SkillTreeProgressionTest {

    @Test
    void committedBranchRejectsOtherBranchPastMilestone() {
        String lockedSide = "branch_a";
        String clickedSide = "branch_b";

        boolean lockedOut = lockedSide != null
                && !lockedSide.isEmpty()
                && !lockedSide.equals(clickedSide);

        assertTrue(lockedOut);
    }

    @Test
    void sameBranchIsNotLockedOut() {
        String lockedSide = "branch_a";
        String clickedSide = "branch_a";

        boolean lockedOut = lockedSide != null
                && !lockedSide.isEmpty()
                && !lockedSide.equals(clickedSide);

        assertFalse(lockedOut);
    }
}
```

- [ ] **Step 2: Extract branch lock decision**

In `SkillTreeClickHandler`, add:

```java
static boolean isLockedOutOfBranch(String lockedSide, String branchKey) {
    return lockedSide != null && !lockedSide.isEmpty() && !lockedSide.equals(branchKey);
}
```

Replace the inline lock check in `handleBranchClick` with:

```java
if (isLockedOutOfBranch(lockedSide, branchKey)) {
    player.sendMessage("§cYou have committed to the other branch. Reset your class to switch.");
    return true;
}
```

- [ ] **Step 3: Update the tests to call the extracted method**

Replace the boolean construction in the test with:

```java
boolean lockedOut = SkillTreeClickHandler.isLockedOutOfBranch(lockedSide, clickedSide);
```

- [ ] **Step 4: Verify**

Run:

```powershell
C:\tmp\apache-maven-3.9.16\bin\mvn.cmd test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/classystem/gui/SkillTreeClickHandler.java src/test/java/com/classystem/gui/SkillTreeProgressionTest.java
git commit -m "test: cover branch lock progression rules"
```

---

### Task 4: Polish the Skill Tree GUI for Playtesting

**Files:**
- Modify: `src/main/java/com/classystem/gui/SkillTreeGui.java`
- Modify: `src/main/java/com/classystem/gui/ItemBuilder.java`
- Create: `docs/playtest/class-system-playtest-checklist.md`

- [ ] **Step 1: Add cost to purchasable milestones**

In `SkillTreeGui.buildMilestoneLore`, when `panesDone >= panesTotal`, include:

```java
lore.add("§eClick to purchase!");
```

Keep this line and add cost in the caller before building the item:

```java
lore.add("§eCost: §f$" + formatCost(tm.moneyCost()));
```

Apply the same pattern for branch milestones and subclass milestones.

- [ ] **Step 2: Add explicit locked branch names**

In `renderBranch`, change the barrier display name from:

```java
"§cBranch Locked"
```

to:

```java
"§cLocked Branch"
```

Keep the lore:

```java
lore.add("§cLocked — you committed to the other branch.");
```

- [ ] **Step 3: Add playtest checklist**

Create `docs/playtest/class-system-playtest-checklist.md`:

```markdown
# Class System Playtest Checklist

## Server Requirements

- Paper 1.21.1
- Java 21
- Vault
- Economy provider
- LuckPerms
- ClassSystem jar from `target/ClassSystem.jar`

## Core Flow

- Run `/selectclass` before choosing a class. Expected: class GUI opens.
- Pick each class once on fresh test users. Expected: starter item is granted and base LP group is added.
- Run `/skilltree`. Expected: selected class tree opens.
- Try buying without money. Expected: clear insufficient funds message and no progress saved.
- Add money and buy trunk panes. Expected: money withdraws and panes turn green.
- Buy trunk milestone. Expected: branches appear.
- Buy one branch milestone. Expected: opposite branch is locked past its milestone.
- Buy a subclass. Expected: subclass is stored, LP group added, and active marker appears.
- Run `/resetclass`, cancel, then confirm. Expected: first command requires confirmation; confirm clears PDC progress and LP groups.

## Ability Flow

- Melee: test Hardened Strikes, War Cry, Riposte, Footwork, Blood Price.
- Ranged: test Volley Training, Hunter's Mark, Bullseye, Explosive Payload, Tangle Shot.
- Magic: test staff tagging, mana spend/regen, basic bolt, charged bolt, Gravity Well, Phase Step.
- Summoner: test summon staff, minion cap, target command, minion death cleanup, reset cleanup.
```

- [ ] **Step 4: Verify**

Run:

```powershell
C:\tmp\apache-maven-3.9.16\bin\mvn.cmd test
```

Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```powershell
git add src/main/java/com/classystem/gui/SkillTreeGui.java src/main/java/com/classystem/gui/ItemBuilder.java docs/playtest/class-system-playtest-checklist.md
git commit -m "docs: add class system playtest checklist"
```

---

### Task 5: Verify and Fix Each Combat Class

**Files:**
- Modify: `src/main/java/com/classystem/listener/MeleeAbilityListener.java`
- Modify: `src/main/java/com/classystem/listener/RangedAbilityListener.java`
- Modify: `src/main/java/com/classystem/listener/MagicAbilityListener.java`
- Modify: `src/main/java/com/classystem/listener/SummonerAbilityListener.java`
- Modify: `docs/playtest/class-system-playtest-checklist.md`

- [ ] **Step 1: Build the plugin jar**

Run:

```powershell
C:\tmp\apache-maven-3.9.16\bin\mvn.cmd clean package
```

Expected: `target/ClassSystem.jar` exists.

- [ ] **Step 2: Install jar on a local Paper server**

Copy:

```text
target/ClassSystem.jar
```

to:

```text
<paper-server>/plugins/ClassSystem.jar
```

Start the server and confirm:

```text
[ClassSystem] ClassSystem enabled successfully.
```

- [ ] **Step 3: Test melee**

Use a melee test user and unlock every melee node. Verify:

```text
Hardened Strikes increases melee damage.
Combat Rhythm reduces cooldowns after hits.
War Cry applies Slowness and Glowing nearby.
Riposte triggers only on correctly timed shield block.
Footwork triggers on sneak + right-click sword.
Blood Price consumes health and buffs only the next hit.
Reset clears active class state.
```

Fix only observed failures in `MeleeAbilityListener.java`, then run:

```powershell
C:\tmp\apache-maven-3.9.16\bin\mvn.cmd test
```

- [ ] **Step 4: Test ranged**

Use a ranged test user and unlock every ranged node. Verify:

```text
Volley Training fires spread arrows and consumes arrows.
Hunter's Mark applies first-hit mark and subsequent-hit bonus.
Bullseye headshot uses top 20 percent hit location.
Explosive Payload does no block damage.
Tangle Shot creates and consumes snares.
Ghost Round does not recurse forever.
```

Fix only observed failures in `RangedAbilityListener.java`, then run:

```powershell
C:\tmp\apache-maven-3.9.16\bin\mvn.cmd test
```

- [ ] **Step 5: Test magic**

Use a magic test user and unlock every magic node. Verify:

```text
Starter staff has `cs_arcane_staff`.
Mana starts at max and regenerates.
Basic bolt spends mana and respects cooldown.
Charged bolt fires on sneak release.
Gravity Well does not steal every normal right-click.
Phase Step and Rift Anchor do not conflict with charged cast.
Glass Cannon increases outgoing and incoming damage.
```

Fix only observed failures in `MagicAbilityListener.java`, then run:

```powershell
C:\tmp\apache-maven-3.9.16\bin\mvn.cmd test
```

- [ ] **Step 6: Test summoner**

Use a summoner test user and unlock every summoner node. Verify:

```text
Starter staff has `cs_summon_staff`.
Minion cap updates by unlocked nodes.
Minions never target their owner.
Rite of Command retargets minions.
Soul Bond respawns exactly one bonded minion.
Reset removes all owned minions.
Server restart does not leave broken metadata state.
```

Fix only observed failures in `SummonerAbilityListener.java`, then run:

```powershell
C:\tmp\apache-maven-3.9.16\bin\mvn.cmd test
```

- [ ] **Step 7: Commit combat fixes**

```powershell
git add src/main/java/com/classystem/listener docs/playtest/class-system-playtest-checklist.md
git commit -m "fix: stabilize class ability playtest behavior"
```

---

### Task 6: Final Production Pass

**Files:**
- Modify: `src/main/resources/plugin.yml`
- Modify: `src/main/resources/config.yml`
- Modify: `README.md`

- [ ] **Step 1: Decide reload command spelling**

Current command is:

```yaml
classystemreload:
```

If keeping backward compatibility, add an alias:

```yaml
  classystemreload:
    description: Reload the ClassSystem config without restarting.
    usage: /classystemreload
    aliases: [classsystemreload]
    permission: classsystem.reload
```

- [ ] **Step 2: Add README**

Create `README.md`:

```markdown
# ClassSystem

Paper 1.21.1 class and skill-tree plugin for melee, ranged, magic, and summoner progression.

## Requirements

- Java 21
- Paper 1.21.1
- Vault
- Economy provider
- LuckPerms

## Build

```powershell
C:\tmp\apache-maven-3.9.16\bin\mvn.cmd clean package
```

The plugin jar is written to `target/ClassSystem.jar`.

## Commands

- `/selectclass`
- `/skilltree`
- `/resetclass`
- `/classystemreload`
- `/classsystemreload`
```

- [ ] **Step 3: Build release jar**

Run:

```powershell
C:\tmp\apache-maven-3.9.16\bin\mvn.cmd clean package
```

Expected: `target/ClassSystem.jar`.

- [ ] **Step 4: Final manual acceptance**

On the playtest server, verify:

```text
New player can select each class.
All four class trees can be fully purchased with enough money.
Branch lockout works.
All four subclasses per class are reachable through the intended branch.
All rank-style LP groups are granted and removed.
No console errors appear during a 30-minute playtest.
```

- [ ] **Step 5: Commit**

```powershell
git add src/main/resources/plugin.yml README.md
git commit -m "docs: document class system release process"
```

---

## Self-Review

**Spec coverage:** The plan covers the owner's described base class selection, money-based unlocks, deep skill tree, branch/subclass identity, combat abilities, rank-style rewards, and playtest readiness.

**Remaining risk:** The ability listeners are too dependent on live Bukkit combat behavior for unit tests alone. The plan explicitly requires live Paper-server verification for each class.

**No unresolved placeholders:** Every task names concrete files, commands, and expected outcomes.
