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
2. **Bot/pricing behaviour** → iterate in `sim`, run `./scripts/dev.sh sim 2000 7`, inspect `build/sim/*.csv`.
3. **Mod wiring** (commands, ticks, items) → add a server GameTest in `mod/src/gametest`, run `./scripts/dev.sh gametest`.
4. **Live poking** → `./scripts/dev.sh server start`, then `./scripts/dev.sh cmd "mkt book DIAMOND"`, `./scripts/dev.sh logs`.
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

## Roadmap (current focus first)

1. Real item custody: Trading Terminal block with deposit/withdraw of actual ItemStacks.
2. Persistence via SavedData so markets survive restarts.
3. Market-maker bot (Avellaneda–Stoikov) + noise traders running on a worker thread.
4. Recipe-linked arbitrage (e.g. 9 IRON ⇄ 1 IRON_BLOCK creation/redemption).
5. Fictional companies with fundamentals, earnings events, dividends.
6. Options chain with Black-Scholes quoting and an implied-volatility surface.
