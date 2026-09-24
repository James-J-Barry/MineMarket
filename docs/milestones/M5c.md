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
   diamond rush, redstone craze. Each has a **transient** shock, scaled 0.5-1.5x per event, plus a smaller
   **permanent** part. About one event every few days across the catalog.
   **News is a purchase (James, 2026-09-24).** A Tier 3 **Newsstand** ($1,000; blueprint: 6 planks, 2 Ledger Paper,
   1 Ink Bottle) opens a board, the Overworld Gazette: the last three days' stories, each with the goods to expect
   higher or lower. (A first version handed out a Newspaper item a day; James found the tooltip unintuitive and the
   papers cluttered inventories.) No news on the Floor screen or in chat.
   **A head start, felt not stated (James).** The rest of the market hears each story a third to two thirds of a
   day after dawn, different per story; only then does the transient build and fade. The permanent part lands the
   next dawn. The guide says only that news takes time to spread and the sooner you act the better.
   **Frequency (James: a 12-day wait was too long).** 25 event types: rare big group shocks plus common small
   single-market stories, about 1.1 a day, so two days in three bring news and silence rarely passes a week.
   Company headlines (Tier 4) come later from an **Electronic Newsfeed**, which replaces the design doc's Newspaper
   Stand. The Floor's NPCs quote around the last trade but never more than 8% from fair value, so once the market
   hears, the Floor follows within hours.
4. **Two Books really has two books.** Floor books for compressed items (iron block) get their own supply and
   demand: a *basis* around 9 x the ingot price that wanders (about 4%, half-life 1 day). Crafting links the
   books, so the gap closes, but most days it opens far enough to profit.
5. **Cheaper trading.** One Ledger Paper makes **32 Order Slips** (was 8), about $0.14 each. An open order can be
   **repriced** without a new slip (the Floor screen's reprice button moves it to the price you've entered).
   Day orders still expire at dawn.
7. **Normal is a day old (James, 2026-09-24).** The Basic Exchange and Price Board show Normal as the Dealer's
   fair value at yesterday's close. The live value still drives prices and the Floor's NPCs. Before this, buying
   under Normal on the Floor never had a losing week, because Normal was exact and public.
6. **More books** so everyday gatherers benefit: potato, beef, copper ingot, bone (14 books). Not cobblestone: at
   10 cents a whole-cent quote can't be tighter than 9/11 cents, a 20% spread.

## Targets (`sim trader`, `sim floor`, `sim farm`)

- An active trader with $1,000 (4 Floor visits a day) averages 3-5x the vault's $3/day, and can still lose money
  over some weeks.
- The iron block vs ingot strategy is profitable on average.
- Selling on the Floor lifts an early-survival gatherer's income by at least 20%.
- `sim floor` targets still hold (liquid books, 2-6% spreads, no profitable Floor-Dealer loop).
- Report the new wheat farm income and Tier timing; update `docs/design.md`, the guides and pinned tests.

## Done when

- [x] Price model: trend walk, weak anchor, permanent supply impact; save format v2 reads v1 saves; unit tests.
- [x] World events: catalog, deterministic schedule, transient + permanent effects; unit tests + GameTest.
      (Headlines first went on the Floor screen and in chat; moved to the Newsstand below.)
- [x] Floor basis for iron block; unit tests.
- [x] 32 Order Slips per Ledger Paper; reprice an open order without a slip (core + menu + GameTest).
- [x] Four new books. Potato, beef, copper ingot, bone; 14 books, so the Floor screen is 212 px wide with a 7x2 grid.
      `sim floor`: all trade in 62-90% of auctions at 3.3-5.3% spreads; every Floor-Dealer loop loses. With world
      events the Floor lags fair value by 1-7% on average (potato 6.7%, redstone 5.1%), so that target is now 8%.
- [x] Sims meet the targets; design doc, guides and pinned numbers updated.
      `sim trader` (30 days, 8 worlds, 4 visits a day, slips $0.14; vault = $3/day on $1,000), after the Newsstand:

      | strategy | $/day | worst world | worst week | orders/day |
      | --- | --- | --- | --- | --- |
      | quote inside the market maker | +$6.81 | +$0.82 | -$99 | 66 |
      | buy under Normal, sell over it (Normal a day old) | +$6.51 | -$4.54 | -$197 | 62 |
      | trade the Newsstand's news | +$11.95 | +$4.41 | -$51 | 2.8 |
      | iron block vs ingots (Two Books) | +$13.99 | +$4.27 | -$143 | 2.4 |
      | buy and hold (benchmark) | -$2.05 | -$4.35 | -$108 | 0.4 |

      Gatherers: Floor-first lifts income +20% (early survival) and +63% (farm-heavy); payback 90 and 27 days.
      Before M5c the same traders lost $15-18 a day. Misses to note: the market-making strategy earns 2x the vault
      rather than 3-5x. (Before decision 7, buying under Normal made $12.58/day and never had a losing week.)
      `sim farm`: wheat now peaks at about $19.50/day (was $21) since sales lower V for good.
      `sim progression`: Tier 1 2.0 h, Tier 2 10 h (unchanged), Tier 3 with the Newsstand 19-20 h (was 16); crate
      uplift +16% / +41%.
      Guides: Money & the Dealer, Recovery (part of a drop never comes back; waiting has a cost vs the vault),
      Diversification, Reading a Quote, Limit and Market Orders (repricing) updated.
- [x] Newsstand board (varying head start, 25 event types, menu + screen, guide News and Markets, GameTest).
- [x] Normal shown as yesterday's close (Dealer.normalValue, save v3, Basic Exchange, Price Board, Patience Pays).
- [x] James's in-game check: the Newsstand board and prices moving with the news (seen on the Ticker Tape). Works
      (2026-09-24). Repricing and the day-old Normal are covered by GameTests.
- [x] `./scripts/dev.sh check` green. 166 unit tests, 46 GameTests.
