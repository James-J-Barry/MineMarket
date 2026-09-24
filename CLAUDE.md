# CLAUDE.md — Realistic Markets (MineMarket)

A Fabric mod for Minecraft **26.1.2** (Java 25) that teaches financial markets through purchased
progression: survival → sell goods to a Dealer for physical dollars → buy each new financial tool with
those dollars → use it to earn more. Single player first; multiplayer later.

- **Design doc (source of truth for gameplay):** `docs/design.md`, exported from the "Realistic Markets —
  Game Design Document" Claude Doc. If a request conflicts with it, ask before building.
- **Current milestone:** M3, Tier 1 content. M2 (Almanac Lectern and Drafting Table) is done; spec in
  `docs/milestones/M2.md`. M3 spec: `docs/milestones/M3.md` (draft: its "Decisions for James" must be
  answered before building).
- **Owner:** James. He play-tests in the IntelliJ dev client; keep him in the loop on anything that
  changes gameplay feel.

## Commands (all via `./scripts/dev.sh`)

| Command | What it does | Time |
|---|---|---|
| `test` | exchange-core unit tests, prints `N tests, M failed` | ~5 s |
| `sim farm [item] [days]` | Dealer balance: income vs farm size → `build/sim/*.csv` | ~5 s |
| `sim progression [profile]` | Play time to own and craft all of Tier 1, from a gathering profile in `sim/src/main/resources/profiles/` | ~5 s |
| `build` | compile everything **and run the GameTests** (Loom's `check` includes them) | ~15 s |
| `gametest` | server GameTests only, in a headless Minecraft server | ~10 s |
| `check` | `test` + `gametest`. **A change is not done until this passes.** | ~20 s |
| `api <class> [names]` | real member names from the 26.1 jar, e.g. `api net.minecraft.world.inventory.Slot isActive` | 1 s |
| `api --find <text> [--fabric]` | find classes by name; `--fabric` also searches Fabric API jars | 2 s |
| `server start` / `cmd "<cmd>"` / `logs` | dev server + RCON (commands run as console) | |

**Always run `api` before writing Minecraft code you haven't used in this repo yet.** 26.1 renamed a lot and
most tutorials online are for older versions. Record anything surprising in the API table below.

## Module map

```
exchange-core/   Pure Java. NO Minecraft imports, ever. Unit-tested.
  money/         Denomination (dime,$1,$10,$100), Money (cents, dime rounding, change-making)
  dealer/        Dealer (pricing, impact, recovery, drift), DealerCatalog (CSV), DealerParams,
                 MarketSpec, DealerStateIO (save format)
  exchange/      Batch-auction order book (for Tier 3 Trading Floor; not used by gameplay yet)
  resources/realisticmarkets/  dealer_catalog.csv, dealer_params.properties (defaults)
sim/             Headless balance runs (FarmSim, AuctionSim)
mod/             Fabric layer. Thin: translate Minecraft events <-> core calls.
  RealisticMarkets        ModInitializer: registries, commands, tick + lifecycle hooks
  registry/               ModItems (currency), ModBlocks, ModMenus, ModCreativeTab
  block/BasicExchangeBlock  opens the menu
  menu/BasicExchangeMenu    Sell + Buy tabs, server logic, ContainerData sync
  client/                   BasicExchangeScreen, RealisticMarketsClient (client entrypoint)
  dealer/                   DealerService (owns Dealer, config, persistence), Wallet, DealerCommands
  MarketService/MarketCommands  scaffold for the batch-auction exchange (/mkt buy|sell|book), Tier 3
  gametest/               DealerGameTests, MarketGameTests
scripts/         dev.sh, mcapi.py (jar inspector), rcon.py
docs/            design.md, milestones/
```

## Invariants — never break these

- Money and items enter only through explicit faucets (Dealer purchases from players, payouts) and leave
  through explicit sinks. Settlement conserves totals (`ExchangeInvariantsTest`).
- **Dollars are never craftable**, never in loot tables, never sold by villagers. Creative tab is the only
  other source.
- **No vanilla recipes for mod items** except the three Tier 0 blocks (Basic Exchange, Almanac Lectern,
  Drafting Table). Everything else is a per-player blueprint crafted at the Drafting Table.
- **Components** (Ledger Paper, Brass Fittings, … Computer Chip) are buy-only from the Dealer: no recipes,
  no loot, no villager trades. Shallow depth, ~40% spread. Shown in the Buy tab only after the player unlocks
  a blueprint that uses them.
- **Unlocks are never items.** Buying a node is permanent and keyed by account: blueprints for blocks/tools,
  perks for licenses. Losing a crafted item costs materials, never the unlock.
- All amounts are `long` cents (prices synced to the client in mills = 1/1000 $). No `double` in balances.
- Payouts round down to the dime, purchases round up (Dealer's favor).
- Every counterparty reacts to volume; nothing prints unlimited money. Check with `sim farm`.
- Dealer time is **in-game days** (`gameTime / 24000 + dayOffset`), never wall clock.
- Server-authoritative. Clients only display synced state. Key per-player state by UUID (multiplayer-ready).
- Bearer papers (future securities) belong to whoever holds the item; the server only tracks whether a serial
  is genuine and unredeemed. Accounts/obligations (vault balances, unlocks, debts) are keyed by account.
- Anything the player sees uses physical items and vanilla-style container screens. Better information
  views are *purchased* upgrades (design doc: "Information as progression").
- `DealerTest` pins the design doc's numbers ($25.48 / $72.82 / $113.09, $115.20 ceiling, wheat ≈ $21/day).
  If you change balance, update the doc, the defaults and the tests together.

## How things work (read before extending)

- **Dealer pricing:** `m(I) = V·e^(−kI/L)`; bid/ask = m·(1∓s/2); sale proceeds integrate the price walk;
  inventory decays `I·e^(−t/τ)`; fair value V drifts (seeded AR(1) on log V). Compressed items (iron block)
  trade through their base pool × units.
- **Config:** `mod/run/config/realisticmarkets/dealer_catalog.csv` + `dealer_params.properties` are copied
  from the defaults on first launch; `/mkt dealer reload` re-reads them live. If you change the defaults,
  delete the copies in `mod/run/config/` (or they shadow your change).
- **Persistence:** `<world>/realisticmarkets/dealer_state.txt`, written atomically every 5 min and on
  `SERVER_STOPPING`. Plain text on purpose (see "Why not SavedData" below).
- **Menu ↔ screen sync:** `ContainerData` only. Values travel as **shorts**, so every number is split into two
  15-bit halves (`setPair`/`pair`, max 2^30). Server refreshes every 10 ticks and immediately when the input
  slot changes (`Slot.setChanged` override). Buttons use `handleInventoryButtonClick` → `clickMenuButton`.
- **Hiding slots per tab:** override `Slot.isActive()`.
- **Dev client username** is pinned to `James` in `mod/build.gradle` (`runs.client.programArgs`). Offline UUIDs
  come from the name, and the default random `Player###` would make per-player saves look lost on every launch.
  Loom never overwrites an existing `.idea/runConfigurations/*.xml`, so add `--username James` by hand there.
- **Dev-only commands** live under `/mkt dealer sell|buy|timeshift|cash` and `/mkt dev ...`, registered only
  when `FabricLoader.isDevelopmentEnvironment()`.

## Minecraft 26.1 API notes (verified against the jar)

| Topic | 26.1 reality |
|---|---|
| Resource ids | `net.minecraft.resources.Identifier.fromNamespaceAndPath` / `Identifier.parse` (was ResourceLocation) |
| Registration | `Item.Properties#setId(ResourceKey)`, `BlockBehaviour.Properties#setId`, `useBlockDescriptionPrefix()`; `Registry.register(BuiltInRegistries.X, key, value)` |
| Block use | `useItemOn(...)` / `useWithoutItem(...)` return `InteractionResult` (`SUCCESS`, `TRY_WITH_EMPTY_HAND`); `getMenuProvider` override + `player.openMenu` |
| Messages | `ServerPlayer#sendOverlayMessage(Component)` (action bar), `sendSystemMessage` |
| Menus | `new MenuType<>(Ctor::new, FeatureFlags.VANILLA_SET)`; `addStandardInventorySlots(inv, x, y)`; `ContainerLevelAccess.create/NULL` |
| Screens | `GuiGraphics` → **`GuiGraphicsExtractor`**; `renderBg` → **`extractBackground(g, mx, my, pt)`**; `renderLabels` → **`extractLabels(g, mx, my)`** (panel-relative coords); `drawString` → **`text(font, str, x, y, color, shadow)`**; `blit(RenderPipelines.GUI_TEXTURED, id, x, y, u, v, w, h, texW, texH)`; `item(stack, x, y)`; `fill(x1,y1,x2,y2,argb)` |
| Text color | Must include alpha: `0xFF404040`, or text is invisible |
| Input | `mouseClicked(MouseButtonEvent e, boolean doubleClick)` with `e.x()/e.y()/e.button()`; `mouseScrolled(mx, my, sx, sy)` |
| Screens registration | `MenuScreens.register(type, Screen::new)` in a `ClientModInitializer` (`"client"` entrypoint) |
| Saved data | `DimensionDataStorage` → `SavedDataStorage`; `SavedDataType(Identifier, Supplier, Codec, DataFixTypes)` |
| World files | `server.getWorldPath(LevelResource.ROOT)` |
| Creative tabs | `CreativeModeTab.builder(Row, col)...build()` registered in `BuiltInRegistries.CREATIVE_MODE_TAB`; Fabric pages modded tabs (page 2) |
| GameTests | `@net.fabricmc.fabric.api.gametest.v1.GameTest` on `method(GameTestHelper)`; fail by throwing; `helper.makeMockServerPlayerInLevel()` works (deprecated) |

**Why not SavedData for the Dealer?** `SavedDataType` needs a `DataFixTypes`; whether `null` is safe in 26.1
wasn't verifiable, so the Dealer uses its own atomic text file. For M2 per-player data, prefer Fabric data
attachments (check the real API with `api --find Attachment --fabric`) or the same text-file approach.

## Workflow rules for Claude Code

1. Logic goes in `exchange-core` with unit tests first; the mod layer stays thin.
2. Before using an unfamiliar Minecraft/Fabric API: `./scripts/dev.sh api ...`. Don't guess from tutorials.
3. Every mod-layer behavior gets a GameTest in `mod/src/gametest` (build menus directly with
   `helper.makeMockServerPlayerInLevel()` and `DealerService.forTest(seed)`).
4. Finish with `./scripts/dev.sh check`. Report the test counts.
5. GUI changes can't be verified headlessly: say so, and give James a 3-line in-game test script.
6. New registrations (items, blocks, menus, data layout changes) need a client restart, not hotswap.
7. Balance changes: run `sim`, update `docs/design.md` numbers and `DealerTest` together.
8. Commit in small, working steps with descriptive messages. James pushes.

## Milestones

- [x] **M1: Dollars and the Dealer.** Currency, Basic Exchange (Sell + Buy tabs, grouped buy grid),
  Dealer pricing + persistence, 40-item catalog, creative tab. 35 unit tests, 8 GameTests.
- [x] **M2: Almanac Lectern + Drafting Table.** Upgrades tree, Guides, Quests; per-player blueprints and perks;
  Tier 1 components in the Buy tab. 68 unit tests, 19 GameTests, `sim progression` = 2.0 h. → `docs/milestones/M2.md`
- [ ] **M3: Tier 1 content** (Bill Clip, Price Board, Merchant License copy, Trade Route Crate + the Capital).
  → `docs/milestones/M3.md` (draft)
- [ ] M4: Banking and item collateral (Bank Vault, Passbook, CD, Loan Note, margin calls)
- [ ] M5: Trading Floor on the batch auction (order slips, NPC traders, Ticker Tape)
- [ ] M6–M10: equities, bonds, futures, options, modern finance (ATM, Brokerage)
- [ ] M11: multiplayer

## Known gaps / ideas parked

- Buy grid has no hover tooltips (prices show in the detail panel).
- Guides quote the design doc's wheat numbers, not the player's own sales (needs per-player sale stats).
- `sim progression` rests on assumed gathering rates (`sim/src/main/resources/profiles/early_survival.csv`).
- Merchant License reissue (physical copy for a small fee) deferred to M3.
- Bill Clip, Price Board, Trade Route Crate are placeholder items until M3 gives them behavior.
- Floating price above the Basic Exchange (design doc) skipped; the screen covers it.
- Placeholder art: currency, block and GUI textures are generated; James may repaint.
- `MarketCommands`/`MarketService` (batch auction) are scaffold for M5; the DIAMOND/IRON/WHEAT tickers there
  are not the Dealer.
