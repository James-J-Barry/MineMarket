# In-world gameplay instead of screens: what other mods do, and what it means for Realistic Markets

Date: 2026-09-25. Research for James after the fun-and-appeal review found that 18 of 19 blocks are plain cubes and
almost everything happens in container screens. This complements `fun-and-appeal-review.md` (which covers pacing,
bugs and the wider mod landscape); here the question is narrower: **how do well-loved mods move interaction and
information out of menus and into the world, and which of those patterns fit a finance mod?**

The honest caveat first: screens aren't the enemy. Order books, option chains and balance sheets are dense tables,
and a screen is the right tool for them (even the Create 6 Stock Keeper has one). The goal is that the *everyday*
actions and the *headline* information live in the world, and screens are for the deep dive. Game-UI writers make the
same point: diegetic UI helps immersion and speed when used sparingly, but becomes a cognitive burden when used for
everything ([indieklem](https://indieklem.substack.com/p/19-the-diegetic-dilemma-benefits-and-challenges-of-immersive-interfaces-in-games),
[Game Developer](https://www.gamedeveloper.com/design/user-interface-design-in-video-games)).

## Patterns worth borrowing

### 1. Show the contents on the block face (Storage Drawers)
Drawers render the stored item and count on the front instead of opening a chest screen. Interaction is by clicks on
the block: right-click inserts the held stack, double right-click inserts every matching stack, left-click takes one,
shift-left-click takes a stack. Reviewers call the model so intuitive that "a decade of storage mods copied it"
([CurseForge](https://www.curseforge.com/minecraft/mc-mods/storage-drawers),
[holy.gg guide](https://www.holy.gg/en/post/minecraft-storage-drawers-mod-guide)).

**For us:** the Basic Exchange is the single most-used block and it's a screen for every sale.
- Right-click the Exchange with a stack: sell it, bills pop into the Bill Clip. Double-click sells all of that item.
  (The design doc already specifies a sneak quick-sell; this is the same idea, Drawers-style.)
- Render the held item's price floating over the Exchange (also already in the design doc and parked), and the last
  sale's total for a few seconds.
- Safe Deposit Box and Trade Route Crate: render the top items/bills on the front face, like a drawer or shelf.

### 2. Information on blocks you place and wire up (Create's Display Link, Display Boards, Nixie Tubes)
Create lets a Display Link read a block (item counts, fluid levels, time, train schedules) and write it onto Display
Boards or Nixie Tubes that players build into walls ([Create wiki: Display Link](https://create.fandom.com/wiki/Display_Link)).
Players build dashboards as part of their base; information becomes architecture.

**For us:** we already have the Record Link (linking storage to a Records Terminal) and the Price Board (a wall block
with a block-entity renderer, `PriceBoardRenderer`). Generalize them:
- A **Ticker Board**: a multi-block wall display (like Create's Display Board) that scrolls live Floor or Stock
  Exchange prices, headlines from the Newsstand, or the central rate. Link it with the Record Link.
- **Net-worth sign / nixie counter**: one line of the Records Terminal (net worth, today's income, the vault balance)
  written onto a wall block.
- The Ticker Tape's chart drawn on a wall-mounted screen block (2x2 or 3x2), not only in its GUI.
The Price Board renderer is the precedent in this codebase; each of these is a `BlockEntityRenderer`.

### 3. Shops you walk up to (Create 6 Table Cloths, Numismatics Vendors, Lightman's Currency traders)
Create 6: a Stock Keeper sets items on Table Cloths; players right-click cloths to build a Shopping List, then click the
Stock Keeper to pay and have the goods delivered by the logistics network
([Create wiki: Table Cloth](https://create.fandom.com/wiki/Table_Cloth),
[Stock Ticker](https://create.fandom.com/wiki/Stock_Ticker)). Numismatics has Vendors and Depositors that trade on a
click, accept a Bank Card, and emit a redstone pulse when paid, which is what makes "redstone capitalism" possible
([Modrinth](https://modrinth.com/mod/numismatics)). Lightman's Currency ships vending machines, shelves, freezers,
display cases and armor-stand traders that render their stock
([CurseForge](https://www.curseforge.com/minecraft/mc-mods/lightmans-currency)).

**For us:**
- The Dealer's Buy tab could also exist as **counter blocks**: a Dealer Stall with a few items on display, click to
  buy one, sneak-click for a stack. The screen stays for browsing the full catalog.
- **Order Slips as physical orders**: pin a filled Order Slip to a "Trading Post" board block; it renders there until
  it fills, then the Trade Receipt drops out. The Floor screen remains the order book.
- The Bank Card on an ATM already follows the Numismatics pattern; add the **redstone pulse on payment** to a
  "Depositor"-style block so players can build pay-to-open doors, toll gates, rent collectors.

### 4. Processing you can watch (Farmer's Delight cutting board, Tinkers' smeltery, Create belts and depots)
Farmer's Delight puts the ingredient on the board and you cut it with a knife in the world; the cooking pot lets you
serve a meal by clicking it with a bowl, skipping the screen
([minecraft-guides: Cutting Board](https://www.minecraft-guides.com/wiki/farmers-delight/tools/cutting-board/)).
Tinkers' molten metal is visible in the smeltery and pours from a faucet onto a casting table
([Tinkers' wiki: Casting Basin](https://tinkers-construct.fandom.com/wiki/Casting_Basin)). Create's items ride belts
and sit on depots as real items.

**For us:** trades are invisible transactions. Make settlement visible:
- **Trade Route Crate**: goods leave in a little cart/boat animation or particles on the next dawn; the payout drawer
  visibly fills on arrival.
- **Trading Floor**: each 10-second auction could "ring": a bell sound, the clearing price on a board above the block,
  and fills dropping into an output chute in front of it.
- **Bond Desk / Stock Exchange**: coupons and dividends stamped on presentation (sound + particle), certificates
  visibly slid across the desk (item entity animation) rather than silently changing NBT.

### 5. Look at it to learn about it (Jade / WTHIT, Create's goggles)
Jade and WTHIT show a small HUD tooltip for whatever block the crosshair is on (name, progress, contents); Jade "has
become the default info HUD in most modern packs" ([Craft Down Under: Jade](https://craftdownunder.co/guides/mods/jade),
[CurseForge: WTHIT](https://www.curseforge.com/minecraft/mc-mods/wthit)). Create's Engineer's Goggles add machine stats
to that overlay when worn.

**For us:** this is cheap and very high value, and it fits "information as progression":
- Provide Jade/WTHIT plugins (both are available on Fabric for 26.1) so looking at a block shows its headline: the
  Exchange's price for the held item, a vault's "locked / margin call" state, a crate's shipment ETA, the Floor's
  next auction countdown.
- A purchasable **"Ledger Spectacles"** (or reuse the Bill Clip / Passbook progression) that unlocks richer overlay
  lines, the way goggles gate Create's stress numbers.

### 6. Teach in the world (Create's Ponder)
Ponder is Create's in-game documentation: hold W over an item and an animated scene builds the machine and explains it
step by step, "to avoid the need of online wikis or lengthy tooltips"
([Create wiki: Pondering](https://create.fandom.com/wiki/Pondering), [Ponder library](https://github.com/Creators-of-Create/Ponder)).
Ponder is a standalone library now, so other mods can use it.

**For us:** our guides are good, but they're text in a book. Short Ponder scenes would show:
the Dealer price sinking as a stack is dumped and recovering over two days; linking a vault to a Records Terminal;
a Trade Route Crate shipping and paying out; collateral being posted and a margin-call liquidation. Hard to do for
abstract finance (charts are easier drawn in a screen), but the *physical* flows (Exchange, crate, vault, linking,
papers) suit it well.

### 7. Build the institution (Immersive Engineering multiblocks)
IE multiblocks are placed block by block in a pattern and formed with the Engineer's Hammer; the formed structure then
renders as one machine ([minecraft-guides: Immersive Engineering](https://www.minecraft-guides.com/mod/immersive-engineering/)).

**For us:** late-tier institutions are one block each today. Forming them from parts would make them landmarks and
make progress visible from across the base:
- **Trading Floor pit**: a 5x5 floor of trading tiles around the block, with a quote board on a wall.
- **Stock Exchange hall**: columns + a ticker board; **Clearing House** vault doors.
Keep the "one block works on its own" rule for accessibility and treat the multiblock as a cosmetic/perk upgrade
(for example a small fee discount), so it's aspiration, not a gate.

### 8. People in the world (Villager Workers, Create: Villager Commerce, Off To Market)
Villager Workers adds Merchants who move into a Market with chests and trade with players and villagers; Create:
Villager Commerce has villagers buy from player shops at Merchant Stalls through Create's stock network; Off To Market
grows villages into towns and cities as you trade with them, unlocking goods and better prices
([Villager Workers 2](https://www.curseforge.com/minecraft/mc-mods/workers),
[Create: Villager Commerce](https://www.curseforge.com/minecraft/mc-mods/create-villager-commerce),
[Off To Market](https://www.curseforge.com/minecraft/mc-mods/off-to-market-enhanced-trading)).

**For us:** the design doc already parks "NPC traders shown as villager merchants around the Trading Floor and Stock
Exchange (more when the market is busy)". It's the single biggest immersion upgrade available, because the Floor and
the Exchange are *supposed* to be crowds:
- Cosmetic villager-model traders spawned in a radius around the Trading Floor, their count following auction volume,
  with profession hats for market makers, noise traders, momentum traders; they cheer or groan (sounds) when the
  clearing price jumps.
- Later: let the Capital be a real destination village (Off To Market style) that grows as you ship to it.

### 9. Plug into automation (Numismatics redstone, Create filters, Supplementaries notice boards)
Numismatics' Depositor emits a redstone pulse when paid ([Modrinth](https://modrinth.com/mod/numismatics)); Create
blocks carry small filter slots on their faces that you configure by clicking an item onto them
([Create wiki: Filter](https://create.fandom.com/wiki/Filter)); Supplementaries' Notice Board displays a book's first
page and accepts hoppers, "meaning you can make some interesting contraptions that display specified text dynamically"
([Craft Down Under: Supplementaries](https://craftdownunder.co/guides/mods/supplementaries)).

**For us:** today no block accepts hoppers or outputs a comparator signal (the fun review confirmed this). Farms can't
feed the economy without the player carrying stacks by hand. Cheap, high-value hooks:
- **Hopper input** on the Trade Route Crate's cargo and a new **Selling Hopper / Consignment Box** that sells into the
  Dealer (or the Floor at a limit price) at each dawn, paying into its drawer: farms can finally run the economy.
- **Comparator output**: vault balance tier, Newsstand "news today" (a pulse at dawn if a story names the item in a
  filter slot), Price Board "price above/below my set level", Clearing House "margin call". That makes redstone
  trading rules possible (sell when the lamp is on), which is exactly the kind of emergent play Create is loved for.
- **Filter slots on faces** (Create-style) for the Price Board's item and the Newsstand watch item, so they're set by
  clicking an item on, not in a screen.

### 10. A market mod done as screens, for contrast (KROIA StockMarket)
KROIA's StockMarket brings a real-time matching engine with candlestick charts, order books and limit/market orders,
plus a market bot for single player ([GitHub](https://github.com/KROIA/StockMarket),
[Modrinth](https://modrinth.com/mod/kroia_stockmarket)). It's the closest existing analogue to Realistic Markets and
it lives almost entirely in screens, so in-world presence is a way for this mod to stand apart, not just a polish item.

## A plan, cheapest first

| # | Change | Pattern | Effort | Why first |
|---|---|---|---|---|
| 1 | Sounds and particles on every trade, payout, coupon, margin call, dawn mark (vanilla sound events) | 4 | 1-2 days | Everything gets feedback at once |
| 2 | Basic Exchange quick-sell (right-click / double-click) and the floating held-item price | 1 | 2 days | The most-used action leaves the screen |
| 3 | Jade + WTHIT plugins: one headline per block | 5 | 2 days | Information in the world with no new blocks |
| 4 | Comparator outputs and hopper inputs (crate cargo, a Consignment Box) | 9 | 3-4 days | Farms and redstone plug into the economy |
| 5 | Wall displays: Ticker Board and a net-worth counter, via the Record Link | 2 | 1 week | Bases grow dashboards; reuses PriceBoardRenderer |
| 6 | Real block models (desk, terminal, pit) instead of cubes | - | 1 week (art) | Landmarks; James may want to repaint anyway |
| 7 | Visible settlement: crate shipments, auction bell and board, papers slid across desks | 4 | 1-2 weeks | Makes invisible transactions tangible |
| 8 | Cosmetic trader villagers around the Floor and Exchange, crowd = volume | 8 | 2 weeks | Biggest immersion win; already in the design doc |
| 9 | Ponder scenes for the physical flows (Exchange, crate, vault, linking) | 6 | 1-2 weeks | In-game teaching without walls of text |
| 10 | Optional multiblock halls (Floor pit, Exchange hall) as cosmetic perks | 7 | 2-3 weeks | Aspirational builds for late game |

Items 1-4 are about two weeks together and change the feel the most for the least risk. 5-8 are the "in-world presence"
milestone the fun review suggested putting before multiplayer.

## Questions for James
1. Should quick-sell at the Basic Exchange be free from the start, or a small Tier 1 perk (fits "convenience is earned")?
2. Jade/WTHIT overlays: free for everyone, or unlocked (Ledger Spectacles) as information progression?
3. Would you rather have cosmetic trader villagers (easy, no gameplay change) or traders that actually are the NPC
   agents (their positions/moods reflect the model's agents; more work, more teaching value)?
4. Consignment Box selling at dawn automates income: good (Create-style factories feeding the economy) or too much
   of a grind-killer? Price impact still caps it either way.
5. Do you want to repaint textures before or after real models? Models change what textures are needed.


## Built (2026-09-25): items 1, 2 and 5
- **1. Sounds and particles** (`fx/Feedback`): vanilla sounds and particles on sales, purchases, payouts (dividends,
  coupons, fills, shipments arriving at the crate, brokerage income), contracts signed, dawn marks won or lost,
  forfeits and close-outs, margin calls (the bell), Almanac unlocks and quests. Played at the block when a screen knows
  where it is, else at the player.
- **2. Quick-sell** (`dealer/QuickSell`): sneak and right-click a Basic Exchange holding goods to sell the stack (bills
  into the Bill Clip or inventory; it counts as a sale for quests and the ledger); sneak-click again within half a
  second to sell every stack of that item you carry. Looking at an Exchange with goods in hand shows "64 Wheat: the
  Dealer pays $25.40 (sneak-click to sell)" on the action bar.
- **5. Wall displays** (`block/MarketBoardBlock`, `block/LedgerDisplayBlock`, `client/WallDisplayRenderer`):
  - **Price Boards** in three kinds, three items each (four ran off the texture): the original Price Board (the
    Dealer's bid and ask), the **Floor Price Board** (blueprint with the Trading Floor: last trade with its move since
    the day's open, and the book's best bid / ask) and the **Stock Price Board** (blueprint with the Stock Exchange:
    the same for shares, chosen by clicking any certificate of the company, which isn't used up). Boards saved with a
    fourth item drop it at the board.
  - **News Board** (blueprint with the Newsstand): the central rate and the last two days' stories, green up, red
    down. (An earlier Market Board that cycled pages was split into these, per James.)
  - **Ledger Display** (blueprint with Digital Record Keeping): link it to a Records Terminal with the Record Link
    (terminal first, then the display): net worth, debts, income today and over 7 days, and what's due next.
  GameTests: quick-sell (single and all), the Market Board's three pages, the Ledger Display's link and numbers.
