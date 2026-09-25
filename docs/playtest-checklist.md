# In-game checks: M7b to M10

Everything below passed `./scripts/dev.sh check` (243 unit tests, 82 GameTests), but screens and in-world clicks
can't be tested headlessly. Restart the client (new blocks, items and menus). A creative world is fastest:
the mod's creative tab has every block and item; `/mkt dealer cash <dollars>`, `/mkt dealer timeshift <days>` and
`/mkt dealer rates` help. Buy the nodes at the Almanac (big ones now draw on your bank balance once bills run out).

## M7b: Digital Record Keeping
1. Place a Records Terminal, a Bank Vault (with some balance), a Safe Deposit Box (bills, shares, bonds) and a Trade
   Route Crate (goods). Right-click the terminal holding a Record Link, then each block: "Linked (n of 16)".
2. Open the terminal: Overview (net worth, kinds, 30-day line), Holdings (items, locations, gains; "?" for papers
   with no recorded cost), Income (sell something at the Dealer first), Calendar (coupons, maturities, earnings, the
   next rate decision). Put papers in a plain chest: they don't count. Move the box one block: the link breaks.
3. With $50,000 linked, Balance Sheet completes.

## M8a: Forward Contracts
1. With the Forward Contract node, the Basic Exchange shows a **Fwd** tab. Put wheat in the slot, set 128, 7 days:
   price and deposit appear. Sign (needs 1 Security Paper and the deposit): a Forward Contract paper arrives.
2. `/mkt dealer timeshift 7`, carry the wheat, Deliver: price plus deposit paid. Dump wheat first to see Hedged.
3. Sign another and timeshift 9: at dawn a red message says the deposit is forfeit.

## M8b: Clearing House
1. Place a Clearing House. Deposit all cash; pick Wheat and the far expiry; +5 lots; Buy. Positions show in the table.
2. Timeshift 1: a chat line reports the dawn mark. Crash wheat (`/mkt dealer sell wheat 3000`), timeshift 1: MARGIN
   CALL and a Margin Call Notice. Deposit to meet it, or timeshift again to be closed out.
3. Link it to a Records Terminal: the futures account shows (a debt if negative).

## M9a: Options Desk
1. Buy tab: switch goods and companies, call/put, expiries, strikes; check the Greeks and the payoff table read well.
2. Buy 2 calls; Holdings shows them; Sell pays the bid. Buy a 90% put, crash wheat, timeshift past expiry (to dawn),
   Collect: Insured (and maybe Long Shot).

## M9b: Writing, Volatility Board, Risk Report Module
1. Write tab: put 256 wheat in the collateral slots, pick a call: "Covered 100%", Write pays the premium (Covered
   Call). Try oak logs instead: a smaller premium. Expire it in the money: Holdings offers "Collect collateral" (the
   strike, no wheat).
2. Volatility Board: bars rise away from 100% (the smile); switch underlyings.
3. Right-click a Records Terminal with a Risk Report Module: a Risk tab appears (cover, exposure, 20% stress).

## M10a: ATM, Bank Card, Pocket ATM
1. Place an ATM; right-click empty-handed: "Insert your Bank Card". With a Bank Card: your account (balance shown,
   deposit, withdraw; no CD or loan tabs). Cashless completes.
2. With the Pocket ATM perk, right-click the card anywhere.

## M10b: Brokerage Terminal
1. Carry share certificates, bonds and options; Holdings > Deposit all papers: they vanish into book entry (Paperless).
2. Select a row, Withdraw as papers: they come back (shares in the fewest certificates).
3. Timeshift past a coupon or report: the Cash tab shows the income; withdraw it.
4. Markets tab: each button should open that exchange's screen (not tested headlessly).
