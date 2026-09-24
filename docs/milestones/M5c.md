# M5c: Make trading pay (markets that move)

Why: `sim trader` (M5a) showed the Trading Floor is a better place to sell but not a place to make money. Trading
lost $15-18 a day on $1,000: Order Slip fees ($0.58 per order) ate the whole edge. Prices also held no risk or
opportunity, because fair value snapped back to a fixed catalog value within days. James (2026-09-24): make
trading and investing a real alternative to "gather, sell, repeat". Implement the fixes, and rework the snap-back
so that time, supply and demand matter.

This changes the design doc's Economy section (fair value is no longer "a mean-reverting random walk about a fixed
value"). `docs/design.md` is updated with this milestone.

## Decisions (James asked for these to be built; defaults chosen by Claude, open to change)

1. **Fair value no longer snaps back.** Per base pool, log fair value = catalog value + a *level* that moves as:
   - a **random walk with trends**: a daily shock (2%) plus a slowly changing trend (persists about 1-2 weeks),
     so prices wander and can trend for days;
   - a **weak long-run anchor** (half-life 60 in-game days) so a long save can't drift to absurd prices: 95% of
     the time within about +-43% of the catalog value; a typical month moves 10%, one month in ten over 24%;
   - **supply and demand from players**: every item sold to the Dealer lowers the level a little *for good*
     (1% per depth's worth sold, e.g. 256 wheat), and buying raises it. The Dealer's short-term inventory recovery
     (tau = 2 days) stays: that is its liquidity. So part of a dump's price drop recovers and part doesn't; a
     huge single-item farm slowly depresses its own market.
   - **world events** (below).
   "Normal" at the Basic Exchange now means *today's* fair value, which moves.
2. **Time value of money.** Goods pay no interest; money in the vault earns 0.3% a day. So holding goods to sell
   later is a bet that they will rise by more than the vault pays. The guides say so.
3. **World events** from `events.csv`, deterministic from the world seed and the day (no save state): e.g. a
   bumper harvest (farm goods -20%), drought (crops +30%), mine collapse (ores +25%), new vein, building boom,
   diamond rush, redstone craze. Each has a **transient** shock that builds over the first few hours after dawn
   and decays (half-life of a few days), plus a smaller **permanent** part. About one event every few days
   across the catalog. Headlines appear on the **Trading Floor screen** and in chat for players who own the
   Trading Floor node (the Floor is where news travels; the Tier 4 Newspaper Stand will add forecasts later).
   Someone who reads the news at dawn can trade before the price has fully moved.
4. **Two Books really has two books.** Floor books for compressed items (iron block) get their own supply and
   demand: a *basis* around 9 x the ingot price that wanders (about 4%, half-life 1 day). Crafting links the
   books, so the gap closes, but most days it opens far enough to profit.
5. **Cheaper trading.** One Ledger Paper makes **32 Order Slips** (was 8), about $0.14 each. An open order can be
   **repriced** without a new slip (the Floor screen's reprice button moves it to the price you've entered).
   Day orders still expire at dawn.
6. **More books** so everyday gatherers benefit: potato, beef, copper ingot, cobblestone (14 books).

## Targets (`sim trader`, `sim floor`, `sim farm`)

- An active trader with $1,000 (4 Floor visits a day) averages 3-5x the vault's $3/day, and can still lose money
  over some weeks.
- The iron block vs ingot strategy is profitable on average.
- Selling on the Floor lifts an early-survival gatherer's income by at least 20%.
- `sim floor` targets still hold (liquid books, 2-6% spreads, no profitable Floor-Dealer loop).
- Report the new wheat farm income and Tier timing; update `docs/design.md`, the guides and pinned tests.

## Done when

- [x] Price model: trend walk, weak anchor, permanent supply impact; save format v2 reads v1 saves; unit tests.
- [x] World events: catalog, deterministic schedule, transient + permanent effects; headlines on the Floor
      screen and in chat; unit tests + GameTest.
- [ ] Floor basis for iron block; unit tests.
- [ ] 32 Order Slips per Ledger Paper; reprice an open order without a slip (core + menu + GameTest).
- [ ] Four new books.
- [ ] Sims meet the targets; design doc, guides and pinned numbers updated.
- [ ] James's in-game check: news on the Floor, repricing, prices that move.
- [ ] `./scripts/dev.sh check` green.
