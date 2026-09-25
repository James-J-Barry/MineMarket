# Realistic Markets: fun and appeal review

Reviewed 2026-09-25 at commit `b4ee1ad` (M10b done; M7b-M10 in-game checks still open). Scope: the design
(`docs/design.md`, `docs/milestones/M2-M10.md`), the implementation (`mod/`, `exchange-core/`, `sim/`), the progression
data (`nodes.csv`, `quests.csv`, `blueprints.csv`, `dealer_catalog.csv`, `events.csv`, `guides.txt`) and a fresh
`./scripts/dev.sh sim progression` run. No code was changed. GUI claims come from reading the screen code; nothing was
play-tested in a client.

---

## 1. Executive summary

Realistic Markets has an unusually strong economic core. The pricing model is honest: every counterparty reacts to
volume, news has a real head start, bonds move against rates, options are priced properly, and you can't cheese it.
The guides are short, concrete and pinned to real numbers. As a *financial simulator* it is already better than
anything else in the Minecraft modding space. The `sim` harness and the conservation tests are rare discipline for a
hobby mod.

As a *Minecraft mod people will enjoy*, it has four structural problems. They matter more than any single feature:

1. **The pacing collapses after Tier 2.** The fresh sim run: Tier 3 at 19-22 h (target 10), Tier 4 at 45-61 h (15),
   Tier 5 at 88-129 h (22), Tier 6 at 173-252 h (30), Tier 7 at 311-426 h (40). Tier 8 ($1,000,000 of nodes) isn't
   even modelled. For a normal player, **everything from the Stock Exchange up is content they will never see.** Nine
   milestones of work sit behind a wall that only a sim can climb.
2. **It is almost entirely menus.** Of 19 blocks, 18 are plain `cube_bottom_top` cubes with a 16x16 generated
   texture. Only the Price Board draws anything in the world, and only the Bank Vault's alarm changes a block state.
   There are no sounds, particles, animations or entities anywhere in `mod/src` (a grep for `SoundEvents`,
   `playSound`, `ParticleTypes` or `sendParticles` finds nothing). Create, Botania and Tinkers' are loved because
   things *happen in the world*. Here, everything happens in a 236x230 panel of grey text.
3. **It doesn't connect to Minecraft.** Nothing reads the real world. A "Pillager raid" headline is a seeded random
   event, not the raid on your village. Blocks expose no inventories to hoppers and emit no redstone, so farms and
   contraptions can't plug in. Mod items never do anything outside a finance screen. The mod gives players a reason
   to farm, but no reason to *build*.
4. **Several UI bugs will make the late game feel broken.** The worst: the Almanac lists 24 upgrades, 33 quests and
   34 guides in a 170-px-tall panel with no scrolling. Most rows draw below the book, and at common GUI scales off
   the screen. The Almanac's Buy button also ignores the bank balance, so the $300k-$500k Tier 8 nodes can't be
   bought by clicking even though the code behind the button can pay from the bank. Details in section 5.

The good news is that problems 2-4 are cheap next to what has already been built. A few weeks of "presence" work
would change the game's feel more than another tier would: sounds, particles, animated block entities, a
villager-merchant crowd around the Trading Floor, events tied to real world happenings, and hopper and redstone
hooks. Problem 1 is one CSV column and a decision James has already deferred three times. The sim already shows the
fix: Tier 3+ costs at a tenth of today's land the investing player at 10/15/24/39/72 h.

**My top three recommendations:**

1. Fix the Almanac overflow and the bank-funded Buy (a day's work).
2. Scale Tier 3+ node costs by roughly 0.1-0.25 and multiply quest rewards so every tier is reachable in a normal
   save (an hour of work, plus sim checks).
3. Start an **"In-world presence" milestone (M10.5)** before multiplayer: sounds, particles, animated blocks, NPC
   traders, real-world events, and hopper and redstone integration.

---

## 2. Lessons from popular mods

| Mod | What players love | What it means for Realistic Markets |
|---|---|---|
| **Create** | "The game isn't played inside a bunch of UIs"; animated gears and belts you *watch*; "every step of every machine is clearly visible"; Ponder: "hold W to see a fully animated and rendered explanation"; brass and andesite look that fits vanilla ([Modrinth](https://modrinth.com/project/LNytGWDc), [CBR, 10 reasons](https://www.cbr.com/minecraft-create-best-mod/)) | This mod is the opposite. Markets are abstract, but their *effects* can be visible: tickers scrolling, a crowd on the floor, coins clinking into a drawer, a crate cart leaving. A Ponder-style animated demo would teach "sell 64 wheat, watch the price slide" far better than 200 words. |
| **Botania** | Its stated philosophy: "No GUIs: interact with everything in-world", "No numbers: players shouldn't try to minmax everything", "Provide pleasing visuals, despite using only two different particle effects" ([botaniamod.net](https://botaniamod.net/index.html)); the Lexica is the one book that teaches everything ([FTB Wiki](https://ftb.fandom.com/wiki/Lexica_Botania)) | Finance *is* numbers, so "no numbers" doesn't transfer. What transfers is **cheap visual feedback** (two particle types were enough) and a single, well-organised in-game book. The Almanac is that book, but it can't scroll (section 5). |
| **Tinkers' Construct** | A multiblock smeltery with visible molten metal; tools you build and modify ([SlimeKnights](https://slimeknights.github.io/posts/2025/03/03/byproduct-update/), [Tinkers' wiki](https://tinkers-construct.fandom.com/wiki/Smeltery)) | Visible stored value is satisfying. A vault whose gold pile grows, or a Safe Deposit Box that shows its stacks, gives the same "look what I've got" pride. |
| **Farmer's Delight** | "Gently expands" vanilla; "Vanilla++"; "encourages hybrid setups" with vanilla and other mods ([CurseForge](https://www.curseforge.com/minecraft/mc-mods/farmers-delight), [Apex guide](https://apexminecrafthosting.com/guides/minecraft/mods/farmers-delight-mod/)) | Realistic Markets could make farming and mining *more interesting*: prices that tell you what to grow this week, and a reason to build a sugar-cane farm when confectioners are buying. The link is there in the Dealer, but it's only visible in menus. |
| **Create: Numismatics** | Coins, Vendors, Depositors that **emit redstone when paid**, Bank Cards, ID cards for business partners; it plugs into Create's automation ([Modrinth](https://modrinth.com/mod/numismatics)) | Money that talks to redstone and hoppers turns an economy into a *building* toy. This mod has none of that yet. |
| **Lightman's Currency** | Grief-proof player shops, an ATM, a portable ATM; coins minted from metals ([Modrinth](https://modrinth.com/mod/lightmans-currency)) | Its whole appeal is player-to-player trading. Multiplayer (M11) is where this mod's markets will feel alive. Single player needs NPC stand-ins that are *visible*. |
| **Stock Market mod (KROIA)** | A matching engine with liquidity and volatility plugins; villager trades repriced from the market ([GitHub](https://github.com/KROIA/StockMarket)) | This is the closest competitor. It ties the market into villager trading, which is vanilla-integrated and visible. Realistic Markets is deeper, but lives only in its own blocks. |
| **FTB Quests** | A chapter map of connected quest nodes with rewards; party sync ([Craft Down Under guide](https://craftdownunder.co/guides/mods/ftb-quests)) | Players expect a *visual tree*: see what's next and why it's locked. The Almanac is a flat list of 24 names. |
| **Animal Crossing's Stalk Market** | Speculation is fun when it's simple, weekly, visible and **social**: players formed groups to sell on whoever's island paid most ([Den of Geek](https://www.denofgeek.com/games/animal-crossing-turnips-stalk-market-gamestop-stocks/), [Vice](https://www.vice.com/en/article/animal-crossing-new-horizons-turnip-exchange-subreddit/)) | The most-loved game "market" is one commodity, one weekly cycle and one decision. Realistic Markets' best moments (the Newsstand head start, Two Books) have that shape. Lean into them. |
| **Retention research** | Feedback every 30-90 s of active play, a milestone every 10-15 min, early wins in the first session, varied reward sizes ([Game Developer: reward schedules](https://www.gamedeveloper.com/business/reward-schedules-and-when-to-use-them), [Playio FTUE](https://blog.playio.co/mobile-game-onboarding-retention)) | Tier 0-1 hits this well (quests land on days 2-4). From Tier 3 on, a milestone takes 10-100 hours. |

Why players drop mods (distilled from the above and community discussion, e.g. [Sportskeeda's summary of the "why
did you quit" thread](https://Sportskeeda.com/minecraft/former-minecraft-players-share-reasons-left-game)): the
"cool parts" are gated behind tedium; it isn't clear what to do next; progress lives in UIs rather than the world;
and there's no one to show it to.

---

## 3. The player's journey, tier by tier

### First 10 minutes (Tier 0)

*Design:* craft a Basic Exchange, sell something, get dollars. *Reality:* the flow is clean. The Sell tab quotes
Normal / Market / Pays, the drawer fills with bills, and First Sale pays $5 within minutes. The three Tier 0 recipes
use vanilla items a day-one player has (gold is the only hurdle).

- **Discoverability gap.** Nothing tells a new player the mod exists. There's no guide book on first join, no
  advancement toast, no JEI/EMI-style hint. The first recipe needs 2 gold, which many players won't have on day one.
- **The design promises in-world feedback that isn't built:** "holding an item near the block shows its current bid
  above the block, like a name tag" and "sneak + right-click with a stack sells the whole stack"
  (`docs/design.md` lines 81-82). `BasicExchangeBlock` only overrides `useWithoutItem` (line 30), so neither exists.
  CLAUDE.md parks the floating price. Both are exactly the kind of small, tactile delight that sells a mod in its
  first 10 minutes.
- Selling produces no sound. In vanilla, a villager trade gives you the villager's "hmm", the XP orb and the level-up
  chime. Here, bills appear silently in a grid.

### First hour to ~2 h (Tier 1)

The best-paced part of the game. The sim puts Tier 1 at 2.3 h, with quests on days 2, 3, 3, 4 and 4. Each Tier 1
unlock changes what the player does:

- **Bill Clip:** a real quality-of-life win.
- **Price Board:** the only in-world information display in the mod, lit, with ▲/▼ arrows. This is the right
  instinct, and the mod needs ten more like it.
- **Merchant License:** 20% to 12%. Invisible, but you feel it in payouts.
- **Trade Route Crate:** a genuine decision (ship or sell locally), a delayed payoff, and +15-40% income.

Weaknesses: the crate is a static box. Nothing leaves and nothing arrives, and a hopper can't feed it (the block
entity keeps private `SimpleContainer`s and implements no `Container` or `WorldlyContainer`). Quest rewards
($5-$40) are small but proportionate here.

### Midgame (Tier 2-3, ~10-22 h)

- **Bank Vault (Tier 2):** safety from death drops is a real motivator. Interest at 0.3% a day on a few hundred
  dollars is $1-2 a day, which the sim shows is **2-3% of income** ($4.30 vs $180.80 a day). Compounding, the lesson
  of this tier, is almost imperceptible at these balances. The 8 iron blocks (72 ingots) in the recipe is the single
  biggest early wall (M4 notes it).
- **CDs and Loans:** thoughtful and correct. They're also paperwork: a Loan Note has no in-world consequence except a
  red texture on the vault when it's margin-called. That red vault is great, and the mod's best "the world
  reacts" moment.
- **Trading Floor (Tier 3):** the design's centrepiece, and M5c made it actually pay (news trading ~$12 a day, Two
  Books ~$14 a day on $1,000). But the Floor is a 236x230 screen of 14 icons, `-16 -1 +1 +16` and `-10% -1c +1c +10%`
  buttons, three visible orders, and "Auction in Ns". There are no NPC traders to see (parked, M5 decision 13), no
  bell at the auction, no shouted fills. Placing a sell order for 500 wheat takes about 31 clicks on `+16`
  (`TradingFloorScreen.java` lines 29-36). There's no "all I carry" button.
- **Newsstand:** the mod's best idea. A real, felt informational edge that you pay for, and the Stalk-Market-style
  "act before everyone else" thrill. It's also the most fun loop the sims found (steady profit, ~3 orders a day).
- **Ticker Tape:** a good-looking payoff for reading the market.

At ~20 h the player has a strong toolkit, and the next unlock is $10,000 away with income of ~$200 a day: **about
50 in-game days (17 hours) of the same loop for one block.** This is where I expect most players to quit.

### Late game (Tier 4-8, 45-400+ h)

- **Stock Exchange and companies (Tier 4):** clever. Earnings come from the commodity prices you trade, flooding iron
  hurts Deepslate Mining, and there are dividends. The Report tab is proper fundamental analysis. It's also a menu
  tree the player reaches, per the sim, around hour 45-60.
- **Bonds, Records Terminal (Tier 5):** a correct model with a slow payoff; a coupon every 7 days is 2h20m of play.
  The Records Terminal is the "information capstone". Players who reach it at hour ~100 will like it, but the Tier 5
  quest Balance Sheet asks for $50,000 net worth.
- **Forwards and futures, options (Tier 6-7):** the most sophisticated content, and the least likely to be seen. The
  Options Desk screen has 49 text draws and 14 kinds of buttons: underlyings x 12, call/put, 2 expiries, 5 strikes,
  quantity, buy, write, top up, collect. It's a trading terminal, not a Minecraft screen. The *toy* inside it (buy a
  cheap put before harvest; a Long Shot paying 3x) is lovely, but it's buried.
- **ATM, Pocket ATM, Brokerage (Tier 8):** $1,000,000 in total. A gather-and-save player earning ~$180 a day needs
  over 15 real-time years of in-game days. A compounding investor at 0.55% a day needs ~420 in-game days (140 h) to
  grow $100k into $1M. Pocket ATM, the most *convenient* perk in the mod, arrives when convenience no longer matters.

### Replayability and multiplayer

- World events and fair values are seeded per world, so a second world plays a little differently. There are no
  difficulty presets yet: the design promises Relaxed/Standard/Realistic (line 502), but the code only has CSV and
  properties overrides.
- Multiplayer (M11) is where this design could shine: shared Dealer impact, player-written options, a real order
  book, and "whose Price Board shows the best arbitrage". Collusion and wash trading are already noted in the design.
  Nothing social exists yet; single player has no way to show off (no leaderboard, no trophies, no rank title).

---

## 4. Strengths

1. **Economic integrity.** Every faucet reacts to volume. `sim floor` finds no profitable Floor-Dealer loop. Rounding
   goes in the house's favour. Serials settle once. That makes every profit *earned*, which is what the "trading
   over grinding" goal needs.
2. **Information as progression.** Paying for the Newsstand's head start, the Ticker Tape's history and the Records
   Terminal's overview is a genuinely novel progression axis. It makes the *player* smarter, not just their numbers
   bigger.
3. **Learn by consequence.** Quests like Flooding the Market, Patience Pays, Met the Call and Premium Collector fire
   the guide *right after* you feel the concept (e.g. `marked_to_market` grants `guide:margin_calls`). This is
   excellent educational design.
4. **Guides are short, concrete and tested.** 150-250 words, real numbers pinned by `GuidesTest`, and a one-line
   real-world parallel. Tear-out books make them shareable.
5. **World events with texture.** The 25 headlines ("Smiths strike: iron sits unsold", "Faulty contraptions: dust
   dumped") are charming, and two days in three bring news.
6. **The Price Board and the vault alarm.** They prove the team can do in-world feedback, and they're the most
   "Minecraft" parts of the mod.
7. **Death and custody stakes.** Dropping bearer papers on death gives the Safe Deposit Box, binder and Brokerage a
   *felt* reason to exist.
8. **Engineering discipline.** Sims for every balance claim, GameTests for every block, and atomic saves. This makes
   big balance changes cheap and safe to try.

---

## 5. Problems ranked by impact

### P1. Tiers 3-8 are unreachable in normal play (critical)

*Evidence:* the `sim progression` output (this run):

| | T1 | T2 | T3 | T4 | T5 | T6 | T7 |
|---|---|---|---|---|---|---|---|
| gather only (early survival) | 2 h | 10 h | 22 h | 57 h | 120 h | 235 h | 402 h |
| investing player | 2 h | 9 h | 19 h | 45 h | 88 h | 173 h | 311 h |
| design target | 2 h | 5 h | 10 h | 15 h | 22 h | 30 h | 40 h |

Node costs (`nodes.csv`) roughly double each tier ($515, $3.7k, $5.5k, $21k, $45k, $120k, $290k, $1M), while income
grows by tens of percent. Quest rewards total about **$1,700 across all 33 quests** against **~$1.49M** of nodes
(0.1%). Cashless pays $200 after a $300,000 unlock.

*Why it matters for fun:* the design's own rule ("each tier should pay 3-5x more per hour than the last while costing
about as much more", `design.md` line 489) is what keeps every tier's grind similar in length. Today the grind grows
roughly 2x per tier, so each tier feels twice as long as the last. Around Tier 3-4 the player stops learning new
concepts and just repeats the same loop for tens of hours. Most of the mod's best content (options, futures,
bonds) is effectively cut.

*Evidence the fix is known:* the same sim shows Tier 3+ costs x0.1 gives 10/15/24/39/72 h for the investing player.
Tier 7 is still slow at 72 h because tools don't multiply income.

### P2. Almost no in-world presence, sound or animation (critical for appeal)

*Evidence:*

- 18 of 19 blocks use `minecraft:block/cube_bottom_top` (e.g. `models/block/trading_floor.json`) with 16x16
  generated textures.
- The only block entity renderer is `PriceBoardRenderer`, and the only state change is `BankVaultBlock.ALARM`.
- No sounds, particles or entities anywhere in `mod/src`.
- NPC traders are "cosmetic and parked" (`design.md` line 464; M5 decision 13; M6 decision 14).
- The design's tactile touches are unbuilt: the floating bid, quick sell, and "a lectern that glows faintly when a
  quest reward is waiting" (line 171).

*Why it matters:* the core lesson from Create and Botania is that players enjoy *watching* systems work. Here a
$150,000 Options Desk looks like a purple cube. An auction every 10 seconds (a natural heartbeat!) makes no sound.
News "breaks at dawn" with no bell, no paper boy and no chat line. Screenshots and videos, which is how mods spread,
would show only menus.

### P3. The mod doesn't touch the real Minecraft world (high)

*Evidence:*

- `events.csv` headlines are seeded from the world seed and day. A "Pillager raid" or "Drought" never corresponds to
  anything in the player's world, and the player's actions (a raid defended, a village grown, a mineshaft found)
  never move a market except through selling volume.
- No block exposes items to hoppers or emits redstone (no `WorldlyContainer`, `getAnalogOutputSignal` or
  `isSignalSource` anywhere). A Trade Route Crate can't be fed by a farm's hopper chain, and a Price Board can't
  trigger a contraption when wheat is above Normal.
- Mod items do nothing outside finance screens: bills can't be displayed, and certificates can't be framed.

*Why it matters:* Farmer's Delight and Numismatics succeed by making *existing* play richer. Realistic Markets runs
alongside survival rather than enriching it. Even the design pillar "Grounded in Minecraft value" (line 15) applies
only to starting prices.

### P4. The Almanac can't show its own content (high; likely visible bug)

*Evidence (`client/AlmanacScreen.java`, `menu/AlmanacMenu.java`):*

- The panel is `HEIGHT = 170`. Upgrade rows draw at `LIST_Y + i * ROW` = 24 + 13i (line 176) for all 24 nodes, with
  no scroll or paging. Row 10 (Stock Exchange) lands at y = 154 and overlaps the "Cash" line drawn at
  `imageHeight - 18` = 152 (line 185). Row 23 (Brokerage) lands at y = 323, **153 px below the book**.
- Quests (33 rows, line 243) reach y = 440 and guides (34 rows, line 218) reach y = 453. The hints at
  `imageHeight - 30` and `- 28` overlap the lists.
- At 1080p with GUI scale 3 the screen is 360 px tall and the panel's top sits at ~95, so every quest past about #20
  and every node past about #19 is drawn **off-screen**. `rowAt` has no lower bound, so invisible rows are still
  clickable.
- The Drafting Table shows 4 of 22 blueprints at a time (`DraftingTableScreen.java` line 25). It scrolls, but with a
  lot of friction.

This probably went unnoticed because James's Almanac check was at M2, when there were 4 nodes and 8 quests.
**Confirm in-game.**

### P5. The Almanac's Buy button ignores the bank (high, late game)

*Evidence:* `AlmanacMenu.refresh()` computes node state from `long cash = Wallet.count(player.getInventory())`
(line 100): bills only. So a node is `NO_CASH` unless the bills you carry cover it (line 108). `AlmanacScreen` enables
Buy only when the state is `AVAILABLE` (line 86). But `ProgressionService.buyNode` pays from bills *and then the bank*
(the M10b change). The GameTests call `buyNode` directly (`ForwardGameTests.buy`), so they never hit the disabled
button.

*Effect:* for any node costing more than the bills you carry, the screen says "Not enough cash" and the button is
greyed out, even with the money in the vault. At Tier 8 ($200k-$500k) it's close to impossible to carry the bills: 36
slots x 64 x $100 = $230,400, before armour, tools or goods. The feature that was built to fix this can't be reached
from the UI. (Fix: pass `bills + bank balance` into `refresh`, and show "Bank pays $X" on the detail panel.)

### P6. Menus are dense, precise and fiddly (medium-high)

*Evidence:*

- Nine screens are 236-280 px wide, with up to 49 text draws (Options Desk) and 26 buttons (Bank Vault).
- Quantities are set by repeated ±1/±16 clicks, with no "all I carry" or typed amounts (Floor, Options Desk, the
  Forward tab's -64/-16/+16/+64).
- Only 3 open orders are shown on the Floor, 4 holdings on the Options Desk, and 5 on the Bond Desk.
- The Basic Exchange buy list is capped at `MAX_ITEMS = 64` (`BasicExchangeMenu.java` line 109), and the catalog is
  at exactly 64 rows. The next row added (netherite, say) is silently dropped from the Buy tab.

Screens can't be verified headlessly (CLAUDE.md rule 5). M7b-M10 still have open "James's in-game check" boxes.

### P7. "Dawn" drifts away from sunrise after the first sleep (medium, confusing)

*Evidence:* the market day is `gameTime / 24000 + dayOffset` (`DealerService.java` line 149). Sleeping advances
`dayTime` (the sun) but not `gameTime`. So after a player sleeps, "news breaks at dawn", interest, margin calls,
futures marks and "deliver by dawn of day N" happen at some arbitrary visible time of day, and the market's "day N"
no longer matches the sky. The invariant ("never wall clock") is right, but players read "dawn" literally. Either say
"market open" or "trading day" in text, or derive the day from `dayTime`. That fits "sleeping skips a night" but lets
sleeping speed up interest, so a sim check is needed.

### P8. The compounding and income lessons are too small to feel (medium)

*Evidence:* vault interest is 2-3% of income at Tier 2. A coupon arrives every 7 in-game days (2h20m). The sims'
vault is $3 a day on $1,000.

*Why it matters:* the design wants players to *feel* interest, but at realistic daily rates on small balances the
numbers are cents. Nest Egg ($10 of interest) takes about 3 in-game days on $1,000. A "Passbook moment" (a
page-turn sound and a line "Interest +$4.20" at each dawn) would at least make it visible.

### P9. Single player has no social or showing-off hooks (medium)

There are no leaderboards (planned only as an admin tool), no net-worth trophy or title, no statue, no cosmetic
unlocks (a gold-trimmed exchange, a bank lobby), and no shareable "trade story". Animal Crossing shows the social
layer *is* the fun of speculation. M11 fixes this for servers; single player needs a surrogate.

### P10. Components are a tax, not a supply chain (low-medium)

Ten buy-only components at 40% spreads with shallow depth. The design says this teaches "make-vs-buy", but there is
no *make* option, so it's only "buy". The design explicitly rules out recipes (CLAUDE.md invariants). This is fine
if intended; it just isn't a decision the player makes.

### Smaller issues noticed

- Floor buy orders are paid from carried bills only (`FloorService.place`, lines 184-186), not the bank. Big buys
  mean carrying stacks of $100s.
- The Bookkeeper quest says "the lectern announces it", but it is announced by a chat line, like every quest
  (`ProgressionService.emit`). That's fine; just note that it's a chat line, not an in-world moment.
- The design's "Datapack JSON, `/reload`" (line 184) is actually CSV plus `/mkt dealer reload`, and the
  capital catalog isn't copied to `config/` (CLAUDE.md known gaps).
- The design doc still lists "difficulty presets (Relaxed, Standard, Realistic)" (line 502) as if they exist.

---

## 6. Recommendations

Effort is rough solo-developer time with Claude Code, including GameTests and sim runs.

### Quick wins (hours to a couple of days each)

| # | Change | Why | Effort |
|---|---|---|---|
| Q1 | **Almanac: scrollable lists, grouped by tier with headers, "next affordable" highlighted.** Same for quests (split active and done) and guides (group by tier). | Fixes P4; makes the tree navigable | 0.5-1 day |
| Q2 | **Almanac Buy uses bills + bank**, and the detail panel shows "Bills $X + bank $Y" | Fixes P5 | 1-2 h |
| Q3 | **Pacing pass:** Tier 3+ node costs x0.1-0.25 (the sim's own lever), quest rewards x5-10 from Tier 3 up (e.g. Cashless $20k), and Tier 2 per the M4 experiment (halve prices, 4-block vault) | Fixes P1; every tier becomes reachable | 0.5 day plus sim runs; needs James's decision |
| Q4 | **Sounds**, all vanilla assets, so no new art: sale → `ENTITY_VILLAGER_YES` plus `ITEM_ARMOR_EQUIP_CHAIN` ("coins"); auction clear → `BLOCK_NOTE_BLOCK_BELL` for players with open orders nearby; news at dawn → a bell at every placed Newsstand; quest complete → `UI_TOAST_CHALLENGE_COMPLETE`; margin call → `BLOCK_BELL_USE` plus a red alarm; vault interest → a page turn | Cheap, large feel gain (P2) | 1 day |
| Q5 | **Particles:** `HAPPY_VILLAGER` on a profitable sale or a filled order, `ENCHANT` rising from the lectern when a quest reward or a new affordable node is waiting, `SMOKE` on the crate at departure and `CLOUD` on arrival, red `DUST` on a margin-called block | "Two particle effects" is enough, per Botania | 0.5-1 day |
| Q6 | **Toasts, not only chat,** for quest completions and unlocks (vanilla advancement-style toast or a custom `Toast`) | Milestone feedback that players notice | 0.5 day |
| Q7 | **Quick sell and "all I carry"**: sneak-right-click the Basic Exchange with a stack (the design's two-click confirm); "All" and typed quantities on the Floor, Options Desk and Forward tab | Cuts the most common friction | 1 day |
| Q8 | **Hopper faces** on the Trade Route Crate (cargo in from the top and sides, drawer out from the bottom) and on the Safe Deposit Box (securities and currency only) | Lets farms plug in (P3) | 0.5-1 day |
| Q9 | **Comparator output**: Price Board → signal strength from market vs Normal; Bank Vault → alarm; Trading Floor → your order filled | Lets redstone builders make "sell when high" contraptions; Numismatics-style depositor fun | 1 day |
| Q10 | Rename "dawn" in player-facing text to "market open" (or fix the day to follow `dayTime`, after a sim check) | Fixes P7 confusion | 2 h (text) / 1 day (logic) |
| Q11 | Raise `MAX_ITEMS`, or page the buy list, before adding catalog rows | Prevents a silent bug | 1 h |
| Q12 | A **first-join nudge**: give the player a single "Market Almanac: Getting Started" written book (not currency) on first join, or show an advancement pointing to the Basic Exchange recipe | Discoverability | 2-4 h |

### Bigger features (days to weeks)

| # | Feature | What it adds | Effort |
|---|---|---|---|
| B1 | **In-world presence milestone (M10.5):** real models (a counter with an awning for the Basic Exchange, a pit with a railing and chalkboards for the Trading Floor, a newsstand with a paper rack, a ticker that visibly scrolls text via a BER, a vault door that swings open), and BERs showing live data: the last trade on the Floor's board, headlines on the Newsstand face, a green or red share price on the Stock Exchange | Screenshots and videos that sell the mod; the world tells you the market's state at a glance | 2-4 weeks, mostly art |
| B2 | **Visible NPC traders:** 3-8 cosmetic villager-merchant entities (or armour stands with heads) around the Trading Floor and Stock Exchange, with the count scaling with volume. They cheer on up-moves and grumble on crashes; one "market maker" stands at the post | Makes the order book feel like people; long-parked in the design | 1-2 weeks |
| B3 | **Real-world events:** hook vanilla signals into `WorldEvents` as extra, *non-seeded* events: a raid in any village → the raid event; a player-killed Ender Dragon → "End expedition returns", ender pearl prices fall; heavy rain or thunder → crop outlook; a newly generated trial chamber or mineshaft → a rich-vein story; mass villager deaths → prices rise. Keep the seeded ones as background | Player actions move markets; "grounded in Minecraft value" becomes true | 1-2 weeks (plus a save-state question, since today's events need no save) |
| B4 | **Villager integration:** let the Dealer's fair value nudge vanilla villager trade prices (the KROIA Stock Market mod does this), or let a Trade Route Crate ship to a *placed* village (a wandering trader carries the goods) | Ties the mod into vanilla's existing economy | 1-2 weeks |
| B5 | **Ponder-like demos:** a Guides tab "Show me" button that runs a tiny animated diagram in the screen (e.g. price walking down as 64 wheat drop in; a put's payoff hinge). Or at minimum, one annotated screenshot per guide | Create's biggest onboarding win | 1-2 weeks for a small framework plus 10 scenes |
| B6 | **Visual Almanac tree:** an FTB-Quests-style node map by tier with lines to prerequisites, costs and the concept on hover | Players can see the road ahead | 3-5 days |
| B7 | **Visible wealth:** a Bank Vault or Safe Deposit Box BER that shows a pile of gold and bills growing with the balance or contents (bucketed); a framed-certificate wall item | Pride and progress in the world, Tinkers'-style | 3-5 days |
| B8 | **Showing-off hooks for single player:** net-worth titles at the Almanac ("Merchant", "Banker", "Tycoon"), a statue or trophy block per tier, cosmetic unlocks (gold-trimmed variants of blocks) as cheap nodes | Gives late-game money a *fun* sink and a reward to display | 3-5 days |
| B9 | **Weekly "market day" set pieces:** e.g. every 7th day a Harvest Fair where the Capital buys one farm good at a premium, or an IPO day at the Stock Exchange | A Stalk-Market-like rhythm: one simple, recurring, high-stakes decision | 1 week |
| B10 | **Difficulty presets** (Relaxed = costs x0.1 and rewards x10; Standard; Realistic = today's numbers), chosen once per world | Lets players who love the sim keep it hard; everyone else reaches options | 2-3 days (the CSV overlay mechanism already exists) |
| B11 | **Multiplayer social layer (M11+):** player shops at Basic Exchanges (Lightman's-style), a public leaderboard board block, player-written options and forwards, and a server-wide Newsstand with player-submitted "rumours" | Where this design will be most loved | Large; already planned |

### Suggested order

1. Q1, Q2, Q11 (bugs), then Q3 (pacing) with a James decision, then `check`.
2. Q4-Q7 in one "feel" pass, plus Q10 text.
3. Q8-Q9 automation hooks.
4. B1 + B2 as an M10.5 "presence" milestone, then B3.
5. Then M11 multiplayer, with B8/B11 alongside.

---

## 7. Open questions for James

1. **Who is the target player?** A survival player who wants their farm to matter (then pacing must look like
   Tier 7 at ~40-60 h, and in-world presence matters most), or a finance enthusiast who will read the Options Desk
   happily at hour 300 (then Realistic stays as is)? Difficulty presets (B10) let you serve both.
2. **Pacing:** accept Tier 3+ costs x0.1-0.25 and larger quest rewards now, or keep deferring? Every milestone since
   M4 has recorded "balance deferred", and the gap has grown from 2x to ~8x.
3. **In-world presence vs. new finance content:** would you put an M10.5 "presence" milestone before M11
   multiplayer?
4. **Real-world events (B3):** is it OK that some events are no longer reproducible from the seed (they'd need a
   small save file)?
5. **Automation:** should hoppers be allowed to feed the crate and Safe Deposit Box? Does automated selling threaten
   "can't be cheesed"? (Price impact still caps it, so I think it's safe.)
6. **"Dawn":** should the market day follow the sun (sleep advances the economy) or stay on game ticks (and change
   the wording)?
7. **Art:** will you repaint textures and models yourself, commission them, or should Claude generate simple 3D
   models (e.g. Blockbench-style JSON) as better placeholders?
8. **Death penalty** (open in the design): with papers worth tens of thousands at Tier 5+, losing them to a creeper
   may cause rage-quits. Keep, or add a config toggle defaulting to "keep papers, drop bills"?
