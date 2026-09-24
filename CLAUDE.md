# CLAUDE.md — Realistic Markets

A Fabric mod (Minecraft 26.1.x, Java 25) adding a realistic securities market: a frequent batch
auction exchange for Minecraft items and, later, fictional companies and derivatives.

## Module boundaries (the most important rule)

| Module | Contains | Minecraft imports? |
|---|---|---|
| `exchange-core/` | order book, batch auction, accounts/escrow, pricing, bots, JSON snapshots | **Never** |
| `sim/` | headless simulations over exchange-core | Never |
| `mod/` | Fabric glue: commands, ticking, blocks, GUIs, custody, persistence | Yes |

If logic can live in `exchange-core`, it must. The mod layer stays thin: translate Minecraft
events into exchange calls and exchange state into Minecraft output.

## Which loop to use

1. **Market logic** → write/modify code in `exchange-core`, add unit tests, run `./scripts/dev.sh test`.
2. **Bot/pricing behaviour** → iterate in `sim`, run `./scripts/dev.sh sim farm wheat 60`, inspect `build/sim/*.csv`.
3. **Balance numbers** → edit `mod/run/config/realisticmarkets/dealer_catalog.csv` or `dealer_params.properties`
   while the game runs, then `/mkt dealer reload`. Copy good values back into
   `exchange-core/src/main/resources/realisticmarkets/` (the defaults) and update `DealerTest`.
4. **Mod wiring** (commands, ticks, items) → add a server GameTest in `mod/src/gametest`, run `./scripts/dev.sh gametest`.
5. **Live poking** → `./scripts/dev.sh server start`, then `./scripts/dev.sh cmd "mkt dealer quote wheat 64"`,
   `./scripts/dev.sh cmd "mkt dealer state"`, `./scripts/dev.sh logs`.
   RCON runs as console, so player-only commands need `execute as <player> run mkt ...`.

A change is not done until `./scripts/dev.sh check` passes (unit tests + GameTests).

## Invariants — never break these

- Money and items are only created by explicit faucets (`deposit`, `depositPosition`) and only
  destroyed by explicit sinks. Settlement must conserve totals. `ExchangeInvariantsTest` enforces this.
- Every order is fully collateralised at submission (escrow). Settlement can never fail.
- Prices and quantities are `long` integer ticks. No `double` in anything that touches balances.
- `Exchange` is single-threaded; the mod calls it only from the server thread.
- Auctions are deterministic given the order set: price priority, then time priority (order id).

## Conventions

- Minecraft 26.1 is unobfuscated: use Mojang names (`ServerPlayer`, `Component`, `CommandSourceStack`,
  `Identifier`). No mappings. Dependencies use `implementation`, not `modImplementation`.
- Dev-only commands live under `/mkt dev` and are registered only when
  `FabricLoader.isDevelopmentEnvironment()` is true.
- New debug output should be JSON (see `Snapshots`) so scripts and agents can parse it.

## Design doc

The game design lives in the "Realistic Markets — Game Design Document" (Claude Docs artifact in the
RealisticMarkets project). Numbers in `DealerTest` are pinned to it; change both together.

## Current milestone: M1 — Dollars and the Dealer

Done when:
- [x] `exchange-core` `money` + `dealer` packages; `DealerTest` pins the doc's numbers ($25.48 / $72.82 / $113.09, $115.20 ceiling)
- [x] `./scripts/dev.sh sim farm` shows wheat income peaking near $21/day at ~128 units/day
- [x] `./scripts/dev.sh gametest` passes `DealerGameTests` (64 Wheat -> $25.40 as 2x$10, 5x$1, 4 dimes)
- [x] In game: craft a Basic Exchange, open it, sell wheat, collect bills
- [x] M1b: Basic Exchange screen: input slot, live quote, Sell button, denomination payout slots
- [x] M1b: Buy tab (villager-style list; Normal / Market / Sells prices; x1/x16/x64)
- [x] M1b: Dealer state persists in `<world>/realisticmarkets/dealer_state.txt` (`DealerStateIO`)
- [x] Creative tab (currency + Basic Exchange); 40-item catalog in four groups + 14 compressed forms

## Minecraft 26.1 API assumptions to verify first

Verified 2026-09-23: the mod compiles against 26.1.2 / Fabric API 0.150.0 and all 5 GameTests pass.
These are the API choices it relies on, useful when porting to 26.2+:

| Where | Assumption |
|---|---|
| `RealisticMarkets.id` | `net.minecraft.resources.Identifier.fromNamespaceAndPath` (was `ResourceLocation`) |
| `ModItems.register`, `ModBlocks` | `Item.Properties#setId(ResourceKey)`, `BlockBehaviour.Properties#setId`, `useBlockDescriptionPrefix()` |
| `BasicExchangeBlock` | `useItemOn(...)` / `useWithoutItem(...)` return `InteractionResult`; `InteractionResult.TRY_WITH_EMPTY_HAND` |
| `ServerPlayer` messages | `sendOverlayMessage(Component)` (action bar) and `sendSystemMessage(Component)` |
| `DealerCommands.buy` | `BuiltInRegistries.ITEM.getValue(Identifier)`, `Item#getDefaultMaxStackSize()` |
| `Wallet.give` | `Inventory#placeItemBackInInventory(ItemStack)` |
| GameTests | `net.fabricmc.fabric.api.gametest.v1.GameTest` on methods taking `GameTestHelper`; Loom task `runGameTest` |
| Assets | 1.21.4+ layout: `assets/<ns>/items/*.json` item model definitions; recipe keys as plain ids / `#tag` |

## Roadmap (current focus first)

2. M2: Almanac Lectern (upgrades, guides, quests), per-player progression persistence.
3. M3: Tier 1 content (Bill Clip, Price Board, Merchant License, Trade Route Crate).
4. M4: Banking and item collateral (Bank Vault, Passbook, CD, Loan Note, margin calls).
5. M5+: Trading Floor on the existing batch auction, equities, bonds, futures, options, modern finance, multiplayer.

## GUI notes (Minecraft 26.1)

Screen drawing was renamed in 26.1: `GuiGraphics` -> `GuiGraphicsExtractor`, `render`/`renderBg`/`renderLabels`
-> `extractRenderState`/`extractBackground`/`extractLabels`, `drawString` -> `text`. Text colors need an
opaque alpha (`0xFF404040`). See `client/BasicExchangeScreen.java`.
