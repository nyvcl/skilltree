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
- Pick each class once on fresh test users. Expected: starter item is granted and base LuckPerms group is added.
- Run `/skilltree`. Expected: selected class tree opens.
- Try buying without money. Expected: clear insufficient funds message and no progress saved.
- Add money and buy trunk panes. Expected: money withdraws and panes turn green.
- Buy trunk milestone. Expected: branches appear.
- Buy one branch milestone. Expected: opposite branch is locked past its milestone.
- Buy a subclass. Expected: subclass is stored, LuckPerms group is added, and active marker appears.
- Switch to an already purchased subclass. Expected: prior subclass group is removed and new active subclass group is applied.
- Run `/resetclass`, cancel, then confirm. Expected: first command requires confirmation; confirm clears PDC progress and LuckPerms groups.

## Ability Flow

- Melee: test Hardened Strikes, Combat Rhythm, War Cry, Riposte, Footwork, Blood Price, Death Pact.
- Ranged: test Volley Training, Hunter's Mark, Bullseye, Explosive Payload, Tangle Shot, Ghost Round.
- Magic: test staff tagging, mana spend/regen, basic bolt, charged bolt, Gravity Well, Phase Step, Rift Anchor.
- Summoner: test summon staff, minion cap, target command, Soul Bond, minion death cleanup, reset cleanup.

## Acceptance

- All four class trees can be fully purchased with enough money.
- All four subclasses per class are reachable through the intended branch.
- Branch lockout cannot be bypassed by scrolling or clicking hidden slots.
- Rank-style LuckPerms groups are granted on purchase and removed on reset.
- No console errors appear during a 30-minute playtest.
