# Realistic Markets

A Minecraft (Fabric 26.1.x) mod that teaches how financial markets work. Players start in plain
survival, sell goods to a Dealer for physical dollars, and buy their way up a tree of financial
tools: banking, exchanges, stocks, bonds, futures, options. The full design is in the
"Realistic Markets — Game Design Document" in the RealisticMarkets project.

## Layout

```
exchange-core/   pure Java: money, the Dealer, batch-auction exchange (+ tests). No Minecraft.
sim/             headless simulations (farm income, auction) writing build/sim/*.csv
mod/             Fabric mod: currency items, Basic Exchange block, /mkt commands, GameTests
scripts/dev.sh   one entry point for every dev loop (see CLAUDE.md)
scripts/rcon.py  RCON client for driving a dev server from scripts or Claude Code
```

## One-time setup

1. **Install JDK 25.** Easiest: IntelliJ IDEA (2025.3+) → *File → Project Structure → SDK → Download JDK*
   → vendor **JetBrains Runtime**, version 25. JBR gives you enhanced hotswap. Point `JAVA_HOME` at it
   for the terminal too.
2. **Check versions.** Open <https://fabricmc.net/develop>, choose Minecraft 26.1.2, and copy the
   Loader, Fabric API and Loom versions into `gradle.properties` if they differ.
3. **Open the folder in IntelliJ** as a Gradle project and let it sync. Loom generates the
   *Minecraft Client* and *Minecraft Server* run configurations.
4. **Enable hotswap.** Edit the *Minecraft Client* run config → *VM options* → add
   `-XX:+AllowEnhancedClassRedefinition`.

## First build (expect a few fixes)

```bash
./scripts/dev.sh test          # exchange-core unit tests: should pass immediately
./scripts/dev.sh sim farm      # prints wheat income by farm size; best ≈ $21/day at 128/day
./scripts/dev.sh build         # compiles the mod against Minecraft 26.1.2
./scripts/dev.sh gametest      # runs server GameTests headlessly
```

The mod module was written without access to the 26.1 jars, so `build` may fail on a few API
names. `CLAUDE.md` lists every assumption. Fastest fix: run Claude Code in the repo and ask it to
"make `./scripts/dev.sh build` and `./scripts/dev.sh gametest` pass." If Loom names the GameTest
task differently, find it with `./gradlew :mod:tasks --all | grep -i gametest`.

## Play-testing M1

Run *Minecraft Client* in **Debug**, create a Creative or Survival world with cheats on, then:

```
/give @s realisticmarkets:basic_exchange        (or craft it, or grab it from the Realistic Markets creative tab)
/give @s minecraft:wheat 256
```

- Place the Basic Exchange and **right-click** it to open its screen.
- Put wheat in the **left slot**: the screen shows what the Dealer will pay. Click **Sell**.
  64 wheat pays **$25.40**, which appears in the payout slots on the right
  (4 dimes, five $1s, two $10s). Shift-click or drag the money into your inventory.
- Sell another 64 and watch the quote fall. Then `/mkt dealer timeshift 2` and the quote
  recovers while the screen is open.
- Every price is shown per item: **Normal** (the fair value the price recovers to), **Market**
  (the Dealer's current mid price) and **Pays** / **Sells** (what actually changes hands).
- Click the **Buy** tab: scroll the list, pick an item, and buy x1, x16 or x64. You pay with the
  bills and coins in your inventory (and the payout slots); change comes back to your inventory.
- `/mkt dealer quote wheat 64` and `/mkt dealer state` print JSON; `/mkt dealer buy wheat 10`
  buys with your bills; `/mkt dealer cash 100` gives you money (dev only).

## Tuning without restarting

On first start the game copies the defaults to `mod/run/config/realisticmarkets/`:

- `dealer_catalog.csv`: every tradeable item's fair value, depth and group, plus compressed forms.
- `dealer_params.properties`: spread, price-impact steepness, recovery time, fair-value drift.

Edit either file while the game runs, then `/mkt dealer reload`. When you like the numbers, copy
them into `exchange-core/src/main/resources/realisticmarkets/` and update `DealerTest`.

## Fast iteration loops

| You changed | Do this | Time |
|---|---|---|
| Pricing math or money logic | `./scripts/dev.sh test` (or run `DealerTest` in IntelliJ) | seconds |
| Balance numbers | Edit config CSV/properties → `/mkt dealer reload` | seconds |
| Mod code inside methods | Build (Ctrl/Cmd+F9) while debugging → hotswaps into the running game | ~5 s |
| New items, blocks, textures, recipes | Textures: rebuild + F3+T. Recipes/loot: rebuild + `/reload`. New registrations: restart | 5–60 s |
| Anything before calling it done | `./scripts/dev.sh check` | ~1 min |

## Working with Claude Code

Run `claude` in the repo root. `CLAUDE.md` gives it the rules, invariants, module map and the verified
Minecraft 26.1 API notes; `docs/design.md` is the game design; `docs/milestones/` has the current spec.

- `/next`: continue the current milestone (implement, test, tick the checklist, commit).
- `/check`: run unit tests + GameTests and fix failures.
- `/api <what you need>`: look up real 26.1 / Fabric class and method names from your local jars
  (same as `./scripts/dev.sh api ...`). Use it whenever code fails on a renamed API.

`.claude/settings.json` pre-approves the dev commands (build, tests, sim, api, git status/diff/commit) so
Claude Code doesn't ask every time. Pushing stays manual.

## Status

- `exchange-core`: exchange, money and Dealer implemented; 35 tests passing.
- `mod`: M1 complete: currency items and creative tab, Basic Exchange with Sell and Buy tabs,
  a 40-item Dealer catalog in four groups (plus 14 compressed forms), prices that persist in
  `<world>/realisticmarkets/dealer_state.txt`, Dealer commands, config reload, 8 GameTests.
