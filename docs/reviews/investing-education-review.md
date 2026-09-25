# Does a player learn to invest? An end-to-end review of Realistic Markets

Reviewer: Claude (evaluation only; no code or content changed). Date: 2026-09-25. Commit reviewed: `b4ee1ad`.

Evidence comes from reading `docs/design.md`, every milestone spec, the Almanac content (`guides.txt`, `quests.csv`,
`nodes.csv`), the core models under `exchange-core/src/main/java/com/realisticmarkets/**`, the main mod services, and
running `sim equities`, `sim hedge`, `sim options`, `sim trader`, `sim floor` and `sim progression`. I also wrote two
throwaway probes under `build/review/probe/`, `FuturesProbe.java` and `BondProbe.java`, which run against the real core
classes. Sim outputs are saved in `build/review/*.txt`.

---

## 1. Executive summary

The early and middle game teaches well. The spread, price impact, liquidity recovery, diversification of selling,
fixed vs variable costs, arbitrage against freight, compounding, term vs liquidity, collateral haircuts, order books,
limit vs market orders, and dividends and valuation are each introduced right after the player *feels* them. The
numbers in the guides are accurate: I checked the wheat table, compounding (a double at day 232), the CD figures,
the bond coupon ($2.11 a quarter, $108.44) and duration (8-quarter −2.6%). The guides are also unusually honest about
uncertainty: "Normal is a day old", "headlines say which way, not how far", "no pattern is a promise".

The late game has problems that would teach the **wrong** lessons, and three of them are serious:

1. **Riskless, unlimited-size profits in futures and options from reading the paper.** A futures price is the Dealer's
   live fair value (`ClearingHouse.price`), and inside a day the only things that move fair value are (a) news the
   player has already read that dawn, which the market "hears" 0.3-0.7 days later, and (b) the deterministic fading of
   old shocks. The random step happens only at dawn (`Dealer.advance`). So the whole intraday path of every futures and
   option price is known in advance. My probe (8 worlds × 140 days, 20 lots, open at dawn+0.05, close at dawn+0.95)
   made **2,607 trades with 0 losing trades**: +$1,682 on average per news-day trade and +$239 per "fade" trade, about
   **$1,344 per day per world on roughly $7,700 of margin, about 17% a day**, with no risk at all. The vault pays 0.3%.
   That breaks "nothing risk-free should pay more than the vault" by a factor of 50 or more, and it teaches that
   derivatives plus a newspaper are a money machine.
2. **Bond rate news is riskless too, with no size limit.** Bond prices use `CentralBank.marketRate`, which lags the
   decision by 0.3-0.7 days, but the Newsstand reports it at dawn and the desk issues new bonds at par with a coupon
   based on the *old* rate. Buying 8-quarter Treasuries at the dawn of a cut and selling them that evening earns
   **+2.37% risk-free** (BondProbe: about 11 cuts per 40 reviews). The desk has no size cap
   (`BondService.buy`/`sell`).
3. **A 21-day CD pays 0.60% a day risk-free, more than a diversified stock portfolio (0.55%) and nearly double an
   8-quarter Treasury (0.33%).** `BankParams.term` keeps the CD's +0.30%/day premium over the vault at every central
   rate. The optimal saver never buys shares. That contradicts the Risk and Return guide ("Shares should earn more than
   the vault, because owners take the risk") and the yield curve the Bond Desk teaches.

There are two structural issues on top of those:

- **Most players will never reach the second half of the curriculum.** `sim progression` puts Tier 5 (bonds) at
  88-129 h and Tier 7 (options) at 311-426 h, against design targets of 22 h and 40 h. The lessons on bonds,
  hedging, options and custody are effectively locked away, and the realistic fastest path to them is exploit #1.
- **The quests reward trading behaviour, not investing behaviour.** "Beat the Market" (sell shares at a gain),
  "Speculator" (futures profit), "Long Shot" (options for 3×), "Premium Collector" and "Rate Watcher" all reward
  short-term trades. No quest rewards holding a diversified portfolio through a drawdown, buying regularly
  (dollar-cost averaging), keeping an emergency reserve, or comparing a result with a benchmark.

The personal-finance side is also thin. Emergency funds, budgeting, the cost of consumer debt, fees compounding over
time, dollar-cost averaging, index or passive investing, and behavioural biases (panic selling, FOMO, overconfidence,
the disposition effect) are either missing or mentioned only in passing.

If only three things get fixed, they should be: (1) make intraday futures, option and bond prices follow what the
market *will* do, not only what it has heard, or cut the Newsstand's lead to the price; (2) shrink the CD term premium
to a sane level; (3) add "investor behaviour" quests and a benchmark line in the Records Terminal. Section 9 has the
full list.

---

## 2. The learning journey, tier by tier

### Tier 0: the Dealer (spread, price impact, recovery)

**What they learn (well):**
- *Meet the Spread* makes the player lose money on a round trip before explaining it. That is textbook "learn by
  consequence".
- *Flooding the Market* → the Price Impact guide shows proceeds that are concave in size, with correct numbers
  ($25.48 / $72.82 / $113.09, pinned by `DealerTest`).
- The Recovery guide gives a sustainable pace (L/τ) and explicitly notes the opportunity cost of waiting: "goods in a
  chest earn nothing … Normal may move against you". That is an early, gentle introduction to the time value of money.
- The rounding cost ("Across hundreds of trades it adds up") is the first hint that small fees compound.

**What they might mislearn:**
- The Diversification guide uses "diversification" for *spreading sales across markets to avoid your own price impact*.
  That is really about liquidity and market impact. The risk-reduction meaning (uncorrelated returns) is only a
  secondary paragraph. When shares arrive, the player may think diversification is about "not moving the price"
  rather than "not having all your wealth ride on one outcome". The Risk and Return guide later states the risk
  meaning, but no quest ties the two together.
- *Patience Pays* can read as "prices always come back". The guide hedges this ("Not all of the drop comes back"), but
  the quest mechanic rewards waiting for a rebound. Since M5c's non-reverting fair value, that is a partial truth.
  "Buy the dip, it always recovers" is one of the most expensive retail misconceptions, so be careful.

### Tier 1: Merchant (quotes, transaction costs, arbitrage)

**Strong:**
- The Merchant License guide is one of the best in the game. It explains fixed versus variable cost with a break-even
  figure (about $3,500 of sales) and says "a casual trader may never get there". That maps directly onto real
  decisions such as flat-fee brokerage versus per-trade fees.
- Two Markets explains why arbitrage exists only when the gap beats freight and spreads, and shows that the naive loop
  loses ($40.00 in, $32.30 back). *Two Markets* the quest makes the player verify a real gap.

**Gaps:**
- *Save It* (hold $500) is the only "saving" quest, and its reward is a discount, so saving is framed as a way to
  spend. That is fine for a game, but no quest ever asks the player to keep a *reserve*.

### Tier 2: Banking (interest, CDs, loans)

**Strong:**
- The compounding guide has accurate numbers, the rule-of-72 style doubling, and puts interest in perspective against
  income ("$1,000 earns about $3 a day, while a good farm earns $20"). That is exactly right early in life: grow your
  income, and the savings rate matters more than the interest rate.
- The collateral guide is excellent and realistic. Liquidation value, haircuts, the oak-log paradox and "margin calls
  force sales at the worst moment" all appear.

**Mislearning risks:**
- **The CD premium is absurd** (finding I-3). A CD for 21 days pays 0.6% a day, or +13.4% over the term, against the
  vault's 6.5%. Early redemption returns the principal, so the worst case for a CD is "earned nothing", which is the
  same as a chest. The player learns that *locking money up doubles your return*. Real term premia for short deposits
  are a small fraction of the base rate. The game's own Treasury curve adds only 0.004%/day per quarter.
- *Leverage* the quest (open and fully repay a loan) can be completed by borrowing and repaying on the same day
  for $0 of interest (`Loan.accrueTo` charges only whole days). It is $25 for touching a button, and it doesn't make
  the player experience a leveraged outcome.
- Loans can only be secured by goods and cash (`CollateralValuer`), and the cheapest rate is 0.5%/day. There is
  nothing useful to *do* with borrowed money until Tier 3. The lesson "leverage amplifies gains and losses" is told,
  not felt.

### Tier 3: Trading Floor, Newsstand, Ticker Tape

**Strong:**
- The auction worked example is clear and correct. The limit vs market guide explains the 50% collar and why
  professionals prefer limits.
- The market maker guide ("liquidity vanishes when people need it most") is a real, non-obvious lesson.
- The Ticker Tape guide warns against pattern-reading: "Use a chart to judge how risky a trade is, not to promise
  yourself a profit."

**Mislearning risks:**
- **News trading is sold as the headline way to make money.** `sim trader` shows news trading at +$11.69/day on
  $1,000 (3.9× the vault), and the *worst* of 8 seeds is still +$4.59/day. Buy-and-hold of goods loses (−$0.24/day).
  The real-world lesson is the opposite: public news is priced within seconds, and retail traders who trade on
  headlines underperform. The game frames buying information as legitimate. That is historically defensible, and the
  guide cites the telegraph. But nothing tells the player that in modern markets *they* are the slow reader.
  Consider an in-game arc where the head start shrinks as the world "modernises" (the Electronic Newsfeed era), so the
  player *experiences* markets becoming more efficient.
- *Name Your Price*, *Beat the Dealer* and *Two Books* are fine: they teach mechanics, not speculation.

### Tier 4: Equities

**Strong:**
- The valuation guide covers operating leverage (iron −20% → earnings −37%), P/E, dividend yield, Graham's margin of
  safety and "voting machine / weighing machine".
- The dividend guide debunks dividend capture ("Buying just before and selling just after gains you nothing"). That is
  a common misconception, correctly handled, and the model backs it (fair value drops by the cash paid out).
- The Risk and Return guide is the single most important personal-finance page in the game: vault for near-term money,
  a spread of shares for money you won't touch, and "diversifying gives up little of the return and removes much of
  the risk". `sim equities` backs this up: an even portfolio of the six companies earns 0.55%/day with a −9.5% worst
  week, against −20% to −30% for single names.
- *Diversified* (hold 3 companies) is the best quest in the game for real-world transfer.

**Mislearning risks:**
- *Beat the Market* is misnamed and mis-aimed. It completes when you "sell shares … for more than you paid". That is
  not beating the market; it is realizing a gain. It nudges toward the **disposition effect** (selling winners early)
  and teaches "profit = selling". Rename it (for example "Take a Profit") or, better, make "beat the market" mean
  beating a benchmark: an even portfolio of all six, or the vault.
- *Buy the Business* (hold 100 shares of one company) rewards concentration right before *Diversified* asks for
  three. Consider requiring 100 shares *across* companies.
- ENCH (0.57%) out-earned RSD (0.50%) in `sim equities` even though RSD has the highest required return and 2-3× the
  volatility. With 8 worlds that is noise, but a player comparing single stocks can conclude "risk isn't rewarded".
  This is realistic, but the guides never say "you only earn the risk premium *on average, across many outcomes*".
- Retained earnings compound at the company's **required return with no risk** (`Equities.close`:
  `s.cash = s.cash * (1 + c.quarterReturn()) * inflation + earn - div`). In a no-payout company such as RSD, a growing
  share of the fair value becomes a riskless cash pile growing at 0.55% + inflation a day. Over a long world, RSD
  drifts toward "a riskless bond paying twice the vault". Retained cash should earn about the risk-free rate (the
  central rate), or be deployed into output growth.

### Tier 5: Bonds and the Records Terminal

**Strong:**
- The bond guides are accurate. Price moves opposite to rates, the duration intuition holds (8-quarter −2.6% vs
  2-quarter −0.7% for a 0.05% step), holding to maturity removes price risk, and the 2022 bond crash is the
  real-world anchor.
- The credit risk guide ("A high yield is often a warning, not a gift") is exactly right. `sim equities` shows
  corporates +0.36% vs Treasuries +0.30%, with a −9.2% worst four quarters.
- The Records Terminal's net-worth guide teaches cost basis ("without a cost you can't tell a gain from a loss") and
  income by source. That is real bookkeeping literacy.

**Mislearning risks:**
- **Riskless rate-news front-running** (finding I-2) teaches that bond trading around central-bank meetings is free
  money.
- **The yield curve ignores expected rate moves.** `CentralBank.yield = marketRate + TERM_PREMIUM × quarters`. The rate
  mean-reverts toward 0.3% (`pull`), so at 0.5% the market "knows" rates will likely fall, yet long bonds still yield
  0.5% + 0.032%. This is a predictable edge (buy long bonds when rates are high). It also means the curve never
  inverts, so the most famous piece of yield-curve literacy (inversion signals expected cuts) can't be taught.
- The CD at 0.6%/day beats every bond. A player comparing yields will rationally ignore the Bond Desk.

### Tier 6: Forwards and futures

**Strong:**
- The forward guide explains hedging clearly ("you trade away the chance of a better price to be rid of the chance of
  a worse one … you weren't betting"), and it includes the anti-gaming rule (split forwards count as one).
- The futures guide's "Hedge what the harvest will fetch, not how much of it there is" is an advanced and correct
  point.
- The margin-call guide and the `sim hedge` speculator (6 of 8 worlds bust at 4× leverage) land the lesson on
  leverage.

**Mislearning risks:**
- **The futures hedge makes weekly income *more* volatile, not less.** `sim hedge`: income standard deviation is $23.93
  unhedged and **$26.73 with futures**; the worst week is $75.20 unhedged and **$65.50 with futures**. Only the
  narrower "surprise vs a week earlier" metric improves ($19.64 → $7.39). In the crash test, futures *gain* $7.89
  when the unhedged farmer loses $21.31, so one lot over-hedges. A player who hedges and watches their Records Income
  tab will conclude hedging doesn't work. The cause is basis risk: futures settle at *fair value*, while the harvest
  sells into the Dealer's *impacted* price. That is a genuinely valuable real-world lesson, but no guide names basis
  risk.
- **Exploit I-1** dominates everything else at this tier.
- *Speculator* (close futures at a profit, $80) pays a reward for speculation. It is easy to complete with exploit
  I-1.

### Tier 7: Options

**Strong:**
- The Black-76 pricing is correct. The Greeks page explains delta, theta and vega with the game's numbers, and the
  covered/naked guide explains "the premium isn't free money".
- `sim options` shows at-the-money calls at +1% and puts at −5% on money spent: "insurance or a bet, not a free
  lunch". The insured farmer keeps ≥83% of the harvest's value vs 56% uninsured. That is the right lesson.

**Mislearning risks:**
- The option desk's spot is the Dealer fair value (`OptionsService.spotCents`), so exploit I-1 applies with even more
  leverage. Buy calls at dawn on a drought and sell before dusk.
- The forward in Black-76 is treated as a martingale, but the game's fair value has a *predictable* drift after every
  shock (the fade). After a spike, puts are systematically cheap. The smile does not fix a directional bias.
- *Long Shot* (options for 3× their cost, $150, the largest Tier 7 quest reward) celebrates lottery-ticket buying.
  Real retail options trading is dominated by exactly this behaviour and it loses money on average. If it stays, pair
  it with a guide page on why most such bets lose. `sim options` already has the numbers: 51% of calls expire
  worthless.
- *Premium Collector* (a written option expired worthless) rewards the "picking up pennies in front of a steamroller"
  pattern, and it can be done naked.

### Tier 8: ATM and Brokerage

These are fine conceptually. The electronic money guide mentions bank runs and deposit insurance, which is good. The
Paperless quest is $300 for a click, with little learning.

---

## 3. Curriculum gaps

| Concept | Status | Notes |
|---|---|---|
| Compounding | ✅ strong | Vault guide; doubling at day 232 |
| Time value of money | ◑ partial | Implied in Recovery and Risk and Return; no explicit present-value page, even though bonds and valuation rely on it |
| Inflation | ◑ partial | Stated (0.1%/day), but the player never *sees* purchasing power fall. No "real vs nominal" line anywhere in the UI |
| Liquidity | ✅ strong | Spread, depth, recovery, market maker, CD early redemption |
| Costs and fees compounding | ◑ weak | Rounding and the license math; no page shows how a 1% annual fee eats long-term returns |
| Diversification (risk) | ◑ | Stated in Risk and Return; *Diversified* quest; but the Tier 0 guide's meaning differs |
| Risk vs return | ✅ | Guide plus `sim equities`, but CDs contradict it (I-3) |
| Leverage | ✅ told, ◑ felt | Guides good; loans have no productive use until Tier 3 |
| Hedging | ◑ | Forward good; futures hedge increases income variance and basis risk is unnamed |
| Bonds and rates | ✅ | Accurate; the curve ignores expected rate moves; no inversion |
| Valuation | ✅ | P/E, yield, operating leverage, Graham |
| Options | ✅ | Good guides; quests reward lotto behaviour |
| Custody | ✅ | Bearer risk felt through death drops |
| Net worth and bookkeeping | ✅ | Records Terminal, cost basis |
| **Emergency fund / reserves** | ❌ missing | Death drops cash; margin calls punish players who lack a reserve. The pieces exist, but the lesson isn't named |
| **Budgeting / spending vs investing** | ❌ missing | Almanac purchases are the only spending; no guide on "pay yourself first" or saving rate |
| **Dollar-cost averaging** | ❌ missing | Easy to add as a quest (buy shares on N separate weeks) |
| **Index / passive investing** | ❌ missing | `sim equities`' "even 6" is literally an index fund, but the game never offers or names one. A one-certificate "Overworld Index" would teach it directly |
| **Benchmarking** | ❌ missing | No view compares the player's return with the vault or an even portfolio. Without it, players can't tell skill from luck |
| **Behavioural biases** | ❌ missing | Panic selling, FOMO, overconfidence, the disposition effect, recency bias. The game creates the situations (crashes, jumps, news) but never names them. Quests actively reward some biases (§2) |
| **Market timing is hard** | ❌ contradicted | News and exploits reward timing; nothing shows the cost of missing the best days |
| **Consumer debt cost** | ◑ | Loans exist, but only as collateralized trading leverage. 0.4-1.4%/day (about 150-500% APR equivalent) is never framed as "this is how credit-card debt feels" |
| **Taxes** | ❌ | Reasonable to omit. At most, mention it in the real-world lines |

---

## 4. Incentive and exploit analysis

### I-1 (critical). Intraday futures and option prices are deterministic once you have read the paper

- **Mechanism.** `Dealer.advance` applies the random step and permanent shocks only when the whole day number
  changes. Between dawns, `fair()` changes only through `shocks.fading(...)`, which is a deterministic function of
  events announced at dawn (`WorldEvents.transientEffect`, with delay 0.3-0.7 days, a 0.1-day ramp and a known
  half-life) and through the supply impact of trades. `ClearingHouse.price` = `dealer.fairValue × inflation to
  expiry`, and `OptionsService.spotCents` uses the same fair value.
- **Probe** (`build/review/probe/FuturesProbe.java`): for each product and day, compare the price at dawn+0.05 and
  dawn+0.95. If the difference is over 1%, trade 20 lots in that direction and close before dawn.
  ```
  news-day trades: 611, avg P&L $1681.72, losing 0, worst $0.00
  fade-only trades: 1996, avg P&L $239.23, losing 0, worst $0.00
  per world per day: $1344
  ```
  The margin for 20 lots of all six products is about $7,700 at catalog prices. The round-trip cost (0.25% half spread
  plus 0.1%/lot skew) is well under the move. Position limits (20 lots per contract, two expiries) cap the size but not
  the edge.
- **Why it matters.** The trade is risk-free, repeatable every day, scales to about $1,300/day, and needs only the
  Tier 3 Newsstand. It collapses Tier 6-8 pacing and teaches the opposite of efficient markets.
- **Fix options** (you can combine them):
  1. Make derivatives price off an *expected* fair value that includes the known future fade and pending news once
     the market has heard (a real forward prices expected moves). Or let NPC arbitrage pull the futures price toward
     the Floor's book price, which already carries noise.
  2. Add intraday noise to fair value (for example split the daily step into 4-8 sub-steps), so holding a position
     from dawn to dusk carries risk.
  3. Cap the news advantage on leveraged products: futures and option markets "hear" news at dawn (they are the fast
     money), and only the Dealer and the Floor lag. That is also realistic, because derivatives markets lead spot.
  4. Add a `sim` check: "no strategy that trades only within one day has zero losing trades".

### I-2 (high). Bond desk front-running on rate decisions

- `BondDesk.yield` uses `central.marketRate(day)`, which lags a decision by 0.3-0.7 days. The Newsstand shows the
  decision at dawn. `issue()` sets the coupon from the lagged yield and sells at par +0.25%.
- **Probe** (`BondProbe.java`): buying new 8-quarter Treasuries at the dawn of a cut and selling at dawn+0.95 gives
  **+2.37% risk-free per cut**, about 11 cuts per 40 reviews. There is no size limit. On a raise, a holder sells before
  the market hears and avoids the loss.
- **Fix:** issue new bonds at the *announced* rate (the Treasury knows its own decision) and let the market lag only in
  secondary prices. Also, the desk should not buy back at a stale price. Use the true rate for the desk's bid, or
  widen the spread on decision days.

### I-3 (high). CD term premium dominates all risky assets

- `bank_params.properties`: `cd_terms=7:0.0045,21:0.006`; `BankParams.term` preserves the +0.15%/+0.30% premium over
  the central rate at any rate.
- 21-day CD: **0.60%/day**, versus the even stock portfolio at 0.55% (`sim equities`), corporates at 0.36% and
  8-quarter Treasuries at 0.33%. Early redemption means no downside beyond forgone interest. The Bond Desk's own curve
  prices 3 quarters (21 days) at about vault + 0.012%.
- **Fix:** price CDs off the Treasury curve (7 days ≈ vault + 0.01%, 21 days ≈ vault + 0.03-0.05%), or at most
  0.35%/0.40%. Then update the Term and Liquidity guide numbers and `sim equities` (add a "CD ladder" row so the
  ordering vault < CDs ≈ Treasuries < corporates < shares is visible and pinned).

### I-4 (medium). Other predictable edges

- **The fade on the Dealer and the Floor.** Transient shocks decay deterministically (half-life 2-5 days). The News
  guide *tells* the player to "sell into the jump". On the Floor, `sim trader` shows news at 1.2%/day with no losing
  seed. That is acceptable as a skill reward, but it is systematic, so it should cost more or carry more risk (see the
  I-1 fixes).
- **Mean-reverting rates, flat expectations** (§2 Tier 5): buying long bonds when the rate is high is a predictable
  edge. It is small (about 0.05-0.1%/day) but riskless when held to maturity.
- **RSD retained cash** (§2 Tier 4): riskless growth at the required return.
- **Dealer news front-running** is depth-limited. Buying 128 wheat before a +30% drought and selling after nets
  roughly 15% on about $90. That is fine and consistent with the lesson.

### I-5. What the numbers say about the core investing question

| Instrument (sim) | Return/day | Risk | Verdict |
|---|---|---|---|
| Cash in a chest | −0.10% real | none | ✅ correct lesson |
| Goods held (`sim equities`) | +0.13% | worst 4 quarters −20% | ✅ goods ≈ inflation |
| Vault | +0.29-0.30% | none | ✅ |
| 4-quarter Treasuries, held | +0.30% | none | ✅ ≈ vault |
| Corporate bonds | +0.36% | −9.2% | ✅ |
| **21-day CD** | **+0.60%** | **none** | ❌ dominates |
| Even 6 stocks | +0.55% | −9.5% worst week, −21.8% worst 4 quarters | ✅ but beaten by CDs |
| Single stocks | 0.33-0.57% | −68% to −82% worst 4 quarters | ✅ concentration punished |
| Floor news trading | ~1.2% on $1k | worst week −$83; no losing seed | ⚠ too safe |
| Floor two books | ~1.5% on $1k | worst week −$126 | ⚠ fine, skill-based |
| Futures at 4× (long, rolled) | −0.57% | 6 of 8 worlds bust | ✅ speculation punished |
| **Intraday futures on known news** | **~17% on margin** | **none** | ❌ exploit |
| **Bond buys at the dawn of a cut** | **+2.4% per event** | **none** | ❌ exploit |
| ATM calls / puts held to expiry | +1% / −5% of premium | 51% / 48% expire worthless | ✅ |
| Covered calls on wheat | +1.29%/week vs +2.19% holding | similar worst week | ✅ trades upside for income |

Buy-and-hold of *shares* is rewarded (1.8× the vault diversified). Buy-and-hold of *goods* is correctly about
inflation. Leveraged speculation loses on average. With I-1 to I-3 fixed, the incentive structure would match real
practice quite well.

---

## 5. Realism review

**Realistic and well chosen:**
- A Dealer with inventory-driven pricing and exponential impact; liquidation-value collateral with haircuts; batch
  auctions; market makers skewing on inventory; the dividend drop on the ex-date; operating leverage; credit spreads
  that widen before default with 40% recovery; Black-76 with a smile; daily variation margin; maintenance vs initial
  margin.
- Inflation that makes cash lose value and makes Almanac costs cheaper in real terms.

**Simplifications that mislead:**
1. **Derivatives don't lead spot.** In reality futures and options react *first* to news. Here they lag with the
   Dealer, which creates I-1.
2. **Futures settle at fair value, while hedgers sell at impacted prices.** That is real basis risk, but it is unnamed,
   and it makes the futures hedge raise income variance in `sim hedge`.
3. **A flat expectations curve.** Long yields don't reflect the expected rate path, so the curve can't invert.
4. **The CD term premium** is about 25× the Treasury term premium for the same horizon.
5. **Retained earnings earn the equity return risk-free.**
6. **Time scale.** Returns are quoted per in-game day (0.3%/day ≈ 11.6% a month of days). Players may carry "0.5% a
   day is normal for stocks" into real life. Consider adding an "a year of in-game time" framing (for example 28 days
   = 4 quarters = 1 game year) and quoting yearly figures alongside, or in the real-world lines ("in real life that's
   about 7% a year").
7. **The central bank is a random walk with a pull.** It isn't linked to inflation or activity, so "why do central
   banks raise rates?" (to fight inflation) can't be taught. Tying rate moves to the price level's recent pace, even
   loosely, would make the rates chapter coherent. Inflation is fixed at 0.1%/day, so inflation surprises (the main
   real-world bond risk) never happen.
8. **No short selling of shares, but naked calls are allowed.** That is fine for pedagogy, but note it in the guides.
9. **News is fully reliable about direction.** Real news is noisy. Occasionally a story "fizzles" (scale near 0) or
   is revised. That would teach "don't bet everything on one story" by experience rather than instruction.

---

## 6. Feedback and UX for learning

**Good:**
- "Information as progression" is a strong idea. It maps the value of information onto real costs.
- Passbook, Trade Receipts, Records Terminal (net worth, holdings with cost basis, income by source, calendar),
  Risk Report stress tests, and the Ticker Tape.
- Margin calls come with chat messages and a red vault light, with one day of grace.

**Gaps:**
1. **No outcome comparison.** The single most important feedback for an investor is "how did I do versus doing
   nothing/the vault/an index?" The Records Terminal shows income by source but no return rate and no benchmark. Add a
   "Your return vs the vault vs an even portfolio of the six" line over 7 and 30 days.
2. **Nothing before Tier 5.** Net worth appears only at $20,000 (Digital Record Keeping). With current pacing that is
   88-129 hours in. Most learning happens before the player can see their overall result. Consider a basic net-worth
   line in the Almanac or Passbook at Tier 2, and keep the rich views as purchases.
3. **Hidden market day vs visible day.** Economic time uses `gameTime` (`DealerService.day`), which sleeping does not
   advance, while the visible sun does. After the first sleep, "dawn" (news, margin deadlines, CD maturities) no
   longer matches sunrise, yet guides and chat say "by dawn tomorrow". Show "Market day N, hours to next market dawn"
   on the Newsstand, Passbook and margin messages, or align the economy with `dayTime`.
4. **The consequence is not always explained afterwards.** "Learn by consequence" works for the Tier 0-1 quests
   (guide after the event). There are no "why did that happen?" explainers for the big negative events: the first
   margin call, a default on a held bond, a liquidation, a single stock falling 30%, a written option assigned. Those
   are the moments where the most learning (and the most rage-quitting) happens. The design doc lists these
   explainers under the Guides tab, but only quest-triggered guides exist.
5. **Hedging results are hard to read.** Show "harvest + hedge" as one line in Records so the player sees the
   combined result, not a loss on futures next to a gain on the harvest.
6. **Inflation is invisible.** Add a price-level index on the Newsstand or Passbook ("prices are up 14% since day 0")
   and a real-return figure for the vault.

---

## 7. Implementation issues found

| # | Severity | Where | Issue |
|---|---|---|---|
| B1 | Critical (design bug) | `Dealer.advance`, `ClearingHouse.price`, `OptionsService.spotCents` | Deterministic intraday path → riskless derivatives trades (I-1) |
| B2 | High | `BondDesk.issue` / `yield` via `CentralBank.marketRate` | New bonds issued at a stale rate on decision days; no size cap (I-2) |
| B3 | High (balance) | `bank_params.properties`, `BankParams.term` | CD premium dominates risky assets (I-3) |
| B4 | Medium | `Equities.close` | Retained cash compounds at the required (risky) return with no risk |
| B5 | Medium | `CentralBank.yield` | No expectations term; the curve never inverts; predictable long-bond edge |
| B6 | Medium (UX) | `DealerService.day` (gameTime) | The market dawn drifts away from the visible sunrise after sleeping; messages say "dawn" |
| B7 | Low | `Loan.accrueTo`, quest `leverage` | Borrow and repay on the same day costs $0; the quest teaches nothing |
| B8 | Low | `quests.csv` `beat_market` | Name and goal mismatch (realized gain ≠ beating the market) |
| B9 | Low | `MarginCheck.liquidate` | Sells the largest-haircut (least liquid) collateral first, maximizing the fire-sale loss. That's defensible as "bank sheds risk", but the guide says "cheapest first" without explaining why |
| B10 | Info | `HedgeSim` | One futures lot over-hedges in the crash test (+$7.89 vs −$21.31) and raises income variance; the design doc's claim "hedged ones nothing" refers only to the forward |

I did not find conservation-of-money bugs in the paths I read. Rounding consistently favours the house, the registry
settles once, and margin close-outs are marked at the dawn price. I could not verify client screens headlessly.

---

## 8. Prioritized recommendations

### P0: fix before anyone play-tests Tier 5+ (they change the lessons)
1. **Close the intraday derivatives edge** (I-1). The smallest change is to have `ClearingHouse` and the option desk
   hear news at dawn (no lag), and to add a sub-daily random component. Add a sim assertion that no intraday strategy
   has zero losing trades. *Effort: 0.5-1 day, plus sim and guide number updates.*
2. **Issue bonds at the announced rate on decision days** (I-2). *Effort: 2 h.*
3. **Rebalance CDs** to vault + about 0.02-0.05%/day, and add a CD row to `sim equities`. Update the Term and Liquidity
   guide numbers and tests. *Effort: 2-3 h.*

### P1: make the curriculum teach investing, not only trading
4. **New quests** (small effort each, mostly `QuestGoal` plus events that already exist):
   - *Steady Hand*: hold at least 3 companies for 4 quarters without selling (rewards patience, and it fights
     panic selling).
   - *Regular Saver*: buy shares in 4 different weeks (dollar-cost averaging).
   - *Rainy Day Fund*: keep at least $X in the vault for 7 days while holding shares (an emergency reserve).
   - *Rode It Out*: hold through a week where your portfolio fell 10% (the behavioural lesson).
   - Rename or rework *Beat the Market* to require outperforming the even-six portfolio over a quarter.
   - Change *Buy the Business* to 100 shares across companies, or drop it.
   - Change *Leverage* to require a loan held ≥ 3 days, and pair it with a guide showing the interest paid.
5. **New guides** (150-250 words each, with the game's numbers):
   - *Index Investing / Owning the Whole Market*, using the even-six numbers from `sim equities`.
   - *Staying Invested*: missing the best few days, panic selling, and why timing is hard. This is triggered after the
     player's first 10% portfolio drop.
   - *Fees Add Up*: show a 1% per-week fee (or a spread paid every week) compounding over 20 weeks.
   - *Basis Risk*: why the futures hedge doesn't match the harvest, triggered after *Hedged* or *Marked to Market*.
   - *Why Did That Happen?* explainers for the first margin call, default, liquidation and assignment (the design doc
     already promises these).
   - A real-world line in each rate guide translating per-day to per-year.
6. **An index certificate** at the Stock Exchange ("Overworld Six", 1 share of each at the combined price). This is a
   small mechanic that teaches the biggest personal-finance lesson directly. *Effort: 1 day.*

### P2: feedback
7. **Benchmark line in the Records Terminal** (your return vs the vault vs the even six, 7 and 30 days). *Effort:
   0.5-1 day.*
8. **Show the market day and hours to dawn** on the Newsstand, Passbook, margin messages and Clearing House. *Effort:
   2-4 h.*
9. **Early net worth**: a one-line net worth in the Passbook at Tier 2 (vault + carried cash only). *Effort: 2 h.*
10. **Price-level index and real return** shown somewhere cheap (Newsstand masthead). *Effort: 1-2 h.*

### P3: realism and balance
11. Retained cash earns the central rate (B4). Add an expectations term to the yield curve (B5). Optionally link
    central-bank moves to recent inflation. *Effort: 0.5-1 day, plus sims.*
12. **Pacing.** Players need to reach Tier 5-7 for most of the curriculum to matter. The `sim progression` "costs
    ×0.1 for Tier 3+" row already lands near the targets for an investing player. Once I-1 to I-3 are fixed, that
    investing player is the honest baseline. I recommend acting on it rather than deferring further.
13. Occasionally let news fizzle or reverse (small probability), so "don't bet everything on one story" is felt.

---

## 9. Open questions for James

1. **Is the news head start meant to be a permanent edge, or a historical phase?** A world that "modernises" (the lag
   shrinks after the Electronic Newsfeed or Brokerage era) would teach market efficiency by experience. Right now the
   game teaches that paying for news always pays.
2. **Should derivatives lead spot?** The realistic answer is yes. It also closes I-1 cleanly.
3. **What should a CD be for?** If it is "liquidity has a price", a small premium does the job. At 0.6%/day it
   becomes the best asset in the game.
4. **Do you want explicitly behavioural content** (panic selling, FOMO, disposition effect)? It fits "fun first" if
   it's delivered as consequence plus a short explainer, not a lecture. Your memory notes say "hint, don't spell
   out", and quests like *Steady Hand* do exactly that.
5. **Pacing:** will you accept Tier 3+ cost cuts now that an investing player is modelled? Otherwise most players
   never see bonds, hedging or options.
6. **Market day vs sunrise:** should the economy follow `dayTime` so sleeping advances markets? That is more
   intuitive, but it lets players skip nights to accelerate interest. Or keep `gameTime` and show a market clock?
7. **Per-day vs per-year framing:** do you want guides to translate returns into yearly equivalents so real-world
   intuition transfers?


## Follow-up (2026-09-25): fixes after this review
- Futures and options no longer drift predictably within a day: they price the fair value the market expects at expiry
  from everything known at dawn (known news paths, events still to come on average, the trend and the anchor), plus a
  small seeded intraday noise that is zero at each dawn (`Dealer.expectedFair`, `Dealer.intradayNoise`). A unit test
  replays news-driven same-day trades: no edge and no sure thing. Dealer forwards use the same expected value.
- The Bond Desk doesn't issue new bonds on the morning of a rate change until the market has heard it, and issues at the
  rate the market has heard (`BondDesk.issuing`). Selling on the news stays open (it only avoids a loss).
- The Options Desk measures volatility on week-long surprises (price against the forecast made a week earlier), with a
  long-run level of 4.8% a day, and tilts the smile by the measured skew. `sim options`: calls +2%, puts -16% on money
  spent; a 90%-put-insured harvest keeps at least 86% of its expected value (55% uninsured). `sim hedge`: surprise
  spread $7.08 unhedged, $3.01 with futures, $0 with a forward.
- Not changed yet (for James): the 21-day CD rate, pacing, the new quests and guides.
