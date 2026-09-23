# Realistic Markets

A Minecraft (Fabric 26.1.x) mod with a realistic securities market. Players trade items, and later
fictional companies and derivatives, on a **frequent batch auction** exchange: orders collect for one
second, then cross at a single uniform price.

## Layout

```
exchange-core/   pure-Java exchange: order book, batch auction, escrow ledger (+ tests)
sim/             headless simulation runner, writes CSV price paths
mod/             Fabric mod: /mkt commands, 1-second auction tick, GameTests
scripts/dev.sh   one entry point for every dev loop (see CLAUDE.md)
scripts/rcon.py  stdlib RCON client for driving the dev server from scripts/agents
```

## Setup (one time)

1. **JDK 25.** For hotswapping, use the JetBrains Runtime 25 (IntelliJ: *Project Structure → SDK →
   Download JDK → JetBrains Runtime*). Gradle needs 9.x for Java 25; generate the wrapper once:
   ```bash
   gradle wrapper --gradle-version 9.4.0
   ```
2. **Check versions.** Open https://fabricmc.net/develop, pick Minecraft 26.1.2, and update the four
   values in `gradle.properties` if newer ones are listed.
3. **Verify the fast loop works (no Minecraft needed):**
   ```bash
   ./scripts/dev.sh test
   ./scripts/dev.sh sim 1000 7
   ```
4. **Build the mod and run the GameTests:**
   ```bash
   ./scripts/dev.sh build
   ./scripts/dev.sh gametest
   ```
   If Loom names the GameTest task differently in your version, find it with
   `./gradlew :mod:tasks --all | grep -i gametest` and update `dev.sh`.
5. **Play it:** open the project in IntelliJ, run the *Minecraft Client* config in **Debug** with VM
   option `-XX:+AllowEnhancedClassRedefinition`, create a world with cheats on, then:
   ```
   /mkt dev fund 10000
   /mkt dev give DIAMOND 50
   /mkt sell DIAMOND 10 100
   /mkt buy DIAMOND 5 110
   /mkt dev auction          (or wait up to 1 second)
   /mkt account
   /mkt book DIAMOND
   ```
   Buying and selling from one account is fine for a smoke test; open a LAN world with a second
   client (or use a GameTest) to see two parties trade.

## Status

- `exchange-core`: working and tested (uniform-price clearing, price/time priority, IOC/GTC,
  escrow, cancel, randomized conservation invariants).
- `mod`: command + tick wiring written against Fabric 26.1 / Mojang names; positions are virtual
  and in-memory for now. Next up: real item custody and persistence (see roadmap in CLAUDE.md).
