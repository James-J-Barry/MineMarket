package com.realisticmarkets.progression;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.realisticmarkets.progression.ProgressionEvent.NetWorth;
import com.realisticmarkets.progression.ProgressionEvent.Purchase;
import com.realisticmarkets.progression.ProgressionEvent.Sale;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProgressionTest {
    static final String WHEAT = "minecraft:wheat";
    static final String BILL_CLIP = "realisticmarkets:bill_clip";
    static final String BRASS = "realisticmarkets:brass_fittings";
    static final String INK = "realisticmarkets:ink_bottle";
    static final String LEDGER = "realisticmarkets:ledger_paper";
    static final Set<String> COMPONENTS = Set.of(BRASS, INK, LEDGER);

    UnlockTree tree;
    Quests quests;
    Blueprints blueprints;
    PlayerProgress p;

    @BeforeEach
    void setUp() {
        tree = UnlockTree.loadDefault();
        quests = Quests.loadDefault();
        blueprints = Blueprints.loadDefault();
        p = new PlayerProgress();
    }

    List<String> feed(ProgressionEvent... events) {
        return java.util.Arrays.stream(events).flatMap(e -> p.apply(e, quests).stream()).map(Quest::id).toList();
    }

    static Sale sale(String item, String group, int qty, long cents, long day) {
        return new Sale(item, group, qty, cents, 1.0, 0.95, day);
    }

    // ---- data

    @Test
    void everyNodeGrantsItsGuide() {
        assertTrue(tree.node("bill_clip").grants().contains("guide:cash_on_hand"));
        assertTrue(tree.node("price_board").grants().contains("guide:reading_a_quote"));
        assertTrue(tree.node("merchant_license").grants().contains("guide:transaction_costs"));
        assertTrue(tree.node("trade_route_crate").grants().contains("guide:two_markets"));
        assertTrue(tree.node("bank_vault").grants().contains("guide:interest_and_compounding"));
        assertTrue(tree.node("certificate_of_deposit").grants().contains("guide:term_and_liquidity"));
        assertTrue(tree.node("loan_note").grants().contains("guide:leverage_and_collateral"));
        assertTrue(tree.node("stock_exchange").grants().containsAll(java.util.List.of("guide:owning_a_share", "guide:valuing_a_company")));
    }

    @Test
    void defaultDataLoadsAndIsConsistent() {
        assertEquals(4, tree.tier(1).size());
        assertEquals(4000, tree.node("bill_clip").costCents());
        assertEquals(33, quests.all().size());
        assertEquals(24, blueprints.all().size());
        assertEquals(32, blueprints.forResult("realisticmarkets:order_slip").resultCount(), "one Ledger Paper makes 32 slips");
        assertEquals(3, tree.tier(3).size());
        assertEquals(3, tree.tier(2).size());
        blueprints.validateAgainst(tree);
        for (UnlockNode n : tree.all()) {
            if (n.requiredQuest() != null) assertTrue(quests.has(n.requiredQuest()), n.id());
        }
    }

    // ---- buying nodes

    @Test
    void purchaseReturnsCostAndGrantsBlueprint() {
        UnlockNode clip = tree.node("bill_clip");
        assertFalse(p.hasBlueprint(BILL_CLIP));
        assertEquals(4000, p.buy(clip, tree, 5000));
        assertTrue(p.hasNode("bill_clip"));
        assertTrue(p.hasBlueprint(BILL_CLIP));
        assertEquals("Already unlocked", p.whyCannotBuy(clip, tree, 5000).orElseThrow());
    }

    @Test
    void cannotBuyWithoutEnoughCash() {
        UnlockNode clip = tree.node("bill_clip");
        assertEquals("Not enough cash", p.whyCannotBuy(clip, tree, 3999).orElseThrow());
        assertThrows(IllegalStateException.class, () -> p.buy(clip, tree, 3999));
        assertFalse(p.hasNode("bill_clip"));
    }

    @Test
    void merchantLicenseNeedsMeetTheSpreadQuest() {
        UnlockNode license = tree.node("merchant_license");
        assertFalse(p.canBuy(license, tree, 100_000));
        feed(new Purchase(WHEAT, "farm", 1, 55, 0), sale(WHEAT, "farm", 1, 45, 0));
        assertTrue(p.hasCompleted("meet_the_spread"));
        p.buy(license, tree, 100_000);
        assertTrue(p.hasPerk("merchant_license"));
    }

    @Test
    void parentsMustBeOwned() {
        UnlockTree t = new UnlockTree(List.of(
                new UnlockNode("a", "A", 1, 100, List.of(), null, List.of()),
                new UnlockNode("b", "B", 1, 100, List.of("a"), null, List.of())));
        assertEquals("Requires A", p.whyCannotBuy(t.node("b"), t, 1000).orElseThrow());
        p.buy(t.node("a"), t, 1000);
        assertTrue(p.canBuy(t.node("b"), t, 1000));
    }

    @Test
    void tierGateNeedsHalfOfPreviousTierForFirstPurchaseOnly() {
        UnlockTree t = new UnlockTree(List.of(
                new UnlockNode("a", "A", 1, 0, List.of(), null, List.of()),
                new UnlockNode("b", "B", 1, 0, List.of(), null, List.of()),
                new UnlockNode("c", "C", 1, 0, List.of(), null, List.of()),
                new UnlockNode("d", "D", 1, 0, List.of(), null, List.of()),
                new UnlockNode("x", "X", 2, 0, List.of(), null, List.of()),
                new UnlockNode("y", "Y", 2, 0, List.of(), null, List.of())));
        p.buy(t.node("a"), t, 0);
        assertEquals("Unlock half of Tier 1 first", p.whyCannotBuy(t.node("x"), t, 0).orElseThrow());
        p.buy(t.node("b"), t, 0); // 2 of 4 = half
        p.buy(t.node("x"), t, 0);
        assertTrue(p.canBuy(t.node("y"), t, 0), "gate applies only to a tier's first purchase");
    }

    // ---- quests

    @Test
    void q1FirstSale() {
        assertEquals(List.of("first_sale"), feed(sale(WHEAT, "farm", 1, 45, 0)));
        assertTrue(p.hasGuide("money_and_dealer"));
        assertEquals(500, quests.quest("first_sale").rewardCents());
        assertEquals(List.of(), feed(sale(WHEAT, "farm", 1, 45, 0)), "completes once");
    }

    @Test
    void q2MeetTheSpreadNeedsBuyThenSellOfSameItem() {
        feed(new Purchase("minecraft:iron_ingot", "mining", 1, 300, 0), sale(WHEAT, "farm", 1, 45, 0));
        assertFalse(p.hasCompleted("meet_the_spread"));
        assertEquals(List.of("meet_the_spread"), feed(new Purchase(WHEAT, "farm", 1, 55, 0), sale(WHEAT, "farm", 1, 45, 0)));
        assertTrue(p.hasGuide("spread"));
    }

    @Test
    void q3FloodingNeeds256OfOneItemInOneDay() {
        feed(sale(WHEAT, "farm", 200, 7000, 3), sale("minecraft:carrot", "farm", 100, 2000, 3));
        assertFalse(p.hasCompleted("flooding_the_market"));
        feed(sale(WHEAT, "farm", 100, 2000, 4)); // new day: 200 from day 3 don't count
        assertFalse(p.hasCompleted("flooding_the_market"));
        assertEquals(List.of("flooding_the_market"), feed(sale(WHEAT, "farm", 156, 3000, 4)));
    }

    @Test
    void q4PatiencePaysNeedsPushBelow70ThenSellAfterRecoveryTo90() {
        feed(new Sale(WHEAT, "farm", 64, 2548, 1.0, 0.75, 0), new Sale(WHEAT, "farm", 64, 2000, 0.95, 0.80, 1));
        assertFalse(p.hasCompleted("patience_pays"), "never pushed below 70%");
        feed(new Sale(WHEAT, "farm", 192, 5000, 0.80, 0.60, 1), new Sale(WHEAT, "farm", 10, 400, 0.85, 0.83, 2));
        assertFalse(p.hasCompleted("patience_pays"), "not yet recovered to 90%");
        assertEquals(List.of("patience_pays"), feed(new Sale(WHEAT, "farm", 10, 400, 0.91, 0.89, 5)));
    }

    @Test
    void q4NeedsTheSameItem() {
        feed(new Sale(WHEAT, "farm", 256, 7000, 1.0, 0.60, 0), new Sale("minecraft:carrot", "farm", 10, 400, 1.0, 0.98, 3));
        assertFalse(p.hasCompleted("patience_pays"));
    }

    @Test
    void q5DiversifyNeeds20DollarsFromThreeGroupsInOneDay() {
        feed(sale(WHEAT, "farm", 64, 2548, 0), sale("minecraft:iron_ingot", "mining", 10, 2500, 0),
                sale("minecraft:bone", "mobs", 50, 1900, 0));
        assertFalse(p.hasCompleted("diversify"), "mobs at $19");
        feed(sale("minecraft:oak_log", "wood_and_stone", 64, 3000, 1)); // next day resets
        assertFalse(p.hasCompleted("diversify"));
        feed(sale(WHEAT, "farm", 64, 2000, 1), sale("minecraft:bone", "mobs", 50, 1000, 1));
        assertFalse(p.hasCompleted("diversify"));
        assertEquals(List.of("diversify"), feed(sale("minecraft:bone", "mobs", 50, 1000, 1)));
    }

    @Test
    void q6BookkeeperAndQ8SaveIt() {
        feed(new NetWorth(10_000, 24_999, 0));
        assertFalse(p.hasCompleted("bookkeeper"));
        assertEquals(List.of("bookkeeper"), feed(new NetWorth(10_000, 25_000, 0)));
        feed(new NetWorth(49_990, 80_000, 1));
        assertFalse(p.hasCompleted("save_it"), "net worth is not cash");
        assertEquals(List.of("save_it"), feed(new NetWorth(50_000, 50_000, 2)));
        assertTrue(p.hasPerk("bank_vault_discount_10"));
    }

    @Test
    void q7TwoMarketsNeedsAShipmentThatBeatsTheLocalQuote() {
        feed(sale(WHEAT, "farm", 1000, 100_000, 0), new NetWorth(1_000_000, 1_000_000, 0));
        assertFalse(p.hasCompleted("two_markets"), "local trading never completes it");
        feed(new ProgressionEvent.Shipment(11_050, 9_700, 1));
        assertFalse(p.hasCompleted("two_markets"), "a losing shipment (iron) doesn't count");
        feed(new ProgressionEvent.Shipment(4_530, 4_530, 2));
        assertFalse(p.hasCompleted("two_markets"), "breaking even isn't a profit");
        assertEquals(List.of("two_markets"), feed(new ProgressionEvent.Shipment(4_530, 5_740, 3)));
        assertEquals(4000, quests.quest("two_markets").rewardCents());
    }

    @Test
    void syncGrantsGivesOwnersContentAddedLater() throws Exception {
        // A save from before M3: Bill Clip owned, but its node didn't grant the Cash on Hand guide yet.
        String old = ProgressStateIO.HEADER + "\nnode\tbill_clip\nquest\tfirst_sale\n"
                + "grant\tblueprint:realisticmarkets:bill_clip\n";
        PlayerProgress loaded = ProgressStateIO.read(new StringReader(old));
        assertFalse(loaded.hasGuide("cash_on_hand"));
        assertTrue(loaded.syncGrants(tree, quests));
        assertTrue(loaded.hasGuide("cash_on_hand"), "node grant re-applied");
        assertTrue(loaded.hasGuide("money_and_dealer"), "quest grant re-applied");
        assertFalse(loaded.hasGuide("two_markets"), "nothing for nodes not owned");
        assertFalse(loaded.syncGrants(tree, quests), "second sync changes nothing");
    }

    @Test
    void nestEggNeedsTenDollarsOfLifetimeInterest() {
        feed(new ProgressionEvent.Interest(600, 600, 3));
        assertFalse(p.hasCompleted("nest_egg"));
        assertEquals(List.of("nest_egg"), feed(new ProgressionEvent.Interest(400, 1000, 4)));
        assertEquals(1000, quests.quest("nest_egg").rewardCents());
    }

    @Test
    void lockedInNeedsACdHeldToMaturity() {
        feed(new ProgressionEvent.CdRedeemed(50_000, 50_000, false, 3));
        assertFalse(p.hasCompleted("locked_in"), "early redemption doesn't count");
        assertEquals(List.of("locked_in"), feed(new ProgressionEvent.CdRedeemed(50_000, 51_590, true, 10)));
    }

    @Test
    void componentGrantsMakeAComponentVisibleWithoutABlueprint() {
        String paper = "realisticmarkets:security_paper";
        Set<String> all = Set.of(BRASS, INK, LEDGER, paper);
        UnlockTree t = new UnlockTree(List.of(
                new UnlockNode("cd", "CD", 1, 0, List.of(), null, List.of("perk:certificate_of_deposit", "component:" + paper))));
        assertFalse(blueprints.componentsVisibleTo(p, all).contains(paper));
        p.buy(t.node("cd"), t, 0);
        assertEquals(Set.of(paper), blueprints.componentsVisibleTo(p, all));
    }

    @Test
    void tierTwoNeedsHalfOfTierOne() {
        UnlockNode vault = tree.node("bank_vault");
        assertEquals("Unlock half of Tier 1 first", p.whyCannotBuy(vault, tree, 1_000_000).orElseThrow());
        p.buy(tree.node("bill_clip"), tree, 1_000_000);
        p.buy(tree.node("price_board"), tree, 1_000_000);
        assertTrue(p.canBuy(vault, tree, 50_000));
        assertEquals("Requires Bank Vault", p.whyCannotBuy(tree.node("certificate_of_deposit"), tree, 1_000_000).orElseThrow());
    }

    static ProgressionEvent.FloorOrderDone floor(String item, boolean buy, boolean market, long qty, long cents, long dealerBidMills, long day) {
        return new ProgressionEvent.FloorOrderDone(item, buy, market, qty, cents, dealerBidMills, day);
    }

    @Test
    void nameYourPriceNeedsAFilledLimitOrder() {
        feed(floor(WHEAT, true, true, 10, 500, 450, 1));
        assertFalse(p.hasCompleted("name_your_price"), "a market order doesn't count");
        feed(floor(WHEAT, true, false, 0, 0, 450, 1));
        assertFalse(p.hasCompleted("name_your_price"), "an unfilled limit order doesn't count");
        assertEquals(List.of("name_your_price"), feed(floor(WHEAT, true, false, 3, 150, 450, 1)));
    }

    @Test
    void beatTheDealerNeedsASaleAboveTheDealersBid() {
        feed(floor(WHEAT, false, false, 64, 2_880, 450, 1)); // 45.0 cents each: equal to the bid
        assertFalse(p.hasCompleted("beat_the_dealer"), "matching the Dealer isn't beating it");
        feed(floor(WHEAT, true, false, 64, 3_300, 450, 1));
        assertFalse(p.hasCompleted("beat_the_dealer"), "buying doesn't count");
        assertEquals(List.of("beat_the_dealer"), feed(floor(WHEAT, false, false, 64, 3_200, 450, 1))); // 50c > 45c
    }

    @Test
    void twoBooksNeedsAProfitableBlockIngotRoundTripInOneDay() {
        String block = "minecraft:iron_block", ingot = "minecraft:iron_ingot";
        feed(floor(block, true, false, 2, 14_000, 0, 5));      // $70 a block = $7.78 an ingot
        feed(floor(ingot, false, false, 18, 13_500, 0, 5));    // $7.50 an ingot: a loss
        assertFalse(p.hasCompleted("two_books"));
        feed(floor(ingot, false, false, 9, 7_200, 0, 6));      // next day: $8.00 an ingot, but no block bought today
        assertFalse(p.hasCompleted("two_books"), "both legs must be the same day");
        feed(floor(block, true, false, 1, 7_000, 0, 6));
        assertTrue(p.hasCompleted("two_books"), "bought a block at $70, sold 9 ingots at $8.00 = $72");

        PlayerProgress q = new PlayerProgress();
        q.apply(floor(ingot, true, false, 18, 12_600, 0, 1), quests);                  // $7.00 an ingot
        assertEquals(List.of("two_books"), q.apply(floor(block, false, false, 2, 13_000, 0, 1), quests)
                .stream().map(Quest::id).filter(s -> s.equals("two_books")).toList(), "the reverse works too: $65 > $63");
    }

    @Test
    void floorHistorySurvivesASave() throws Exception {
        feed(floor("minecraft:iron_block", true, false, 1, 7_000, 0, 6));
        StringWriter w = new StringWriter();
        ProgressStateIO.write(p, w);
        PlayerProgress back = ProgressStateIO.read(new StringReader(w.toString()));
        assertEquals(p, back);
        back.apply(floor("minecraft:iron_ingot", false, false, 9, 7_200, 0, 6), quests);
        assertTrue(back.hasCompleted("two_books"));
    }

    @Test
    void leverageNeedsALoanRepaidInFull() {
        assertEquals(List.of("leverage"), feed(new ProgressionEvent.LoanRepaid(50_000, 1_200, 9)));
        assertEquals(2500, quests.quest("leverage").rewardCents());
    }

    @Test
    void saveItPerkTakesTenPercentOffTheBankVault() {
        UnlockTree t = new UnlockTree(List.of(
                new UnlockNode("bank_vault", "Bank Vault", 1, 50_000, List.of(), null, List.of())));
        UnlockNode vault = t.node("bank_vault");
        assertEquals(50_000, p.costOf(vault));
        assertFalse(p.canBuy(vault, t, 45_000));
        feed(new NetWorth(50_000, 50_000, 1)); // Save It
        assertTrue(p.hasPerk("bank_vault_discount_10"));
        assertEquals(45_000, p.costOf(vault));
        assertEquals(45_000, p.buy(vault, t, 45_000), "charged the discounted price");
        assertEquals(4_000, p.costOf(tree.node("bill_clip")));
    }

    // ---- blueprints and components

    @Test
    void craftMathTakesTheScarcestMaterial() {
        Blueprint clip = blueprints.forResult(BILL_CLIP);
        Map<String, Integer> inv = Map.of("minecraft:leather", 7, BRASS, 5);
        assertEquals(3, clip.maxCraftable(inv));
        Blueprint.CraftResult r = clip.craft(inv, 10);
        assertEquals(3, r.times());
        assertEquals(Map.of("minecraft:leather", 6, BRASS, 3), r.consumed());
        assertEquals(1, clip.craft(inv, 1).times());
        assertEquals(0, clip.maxCraftable(Map.of("minecraft:leather", 2)));
        assertEquals(Map.of("minecraft:leather", 0, BRASS, 0), clip.craft(Map.of(), 5).consumed());
    }

    @Test
    void componentsBecomeVisibleWithBlueprintsThatUseThem() {
        assertEquals(Set.of(), blueprints.componentsVisibleTo(p, COMPONENTS));
        p.buy(tree.node("bill_clip"), tree, 4000);
        assertEquals(Set.of(BRASS), blueprints.componentsVisibleTo(p, COMPONENTS));
        p.buy(tree.node("price_board"), tree, 7500);
        assertEquals(Set.of(BRASS, INK), blueprints.componentsVisibleTo(p, COMPONENTS));
        p.buy(tree.node("trade_route_crate"), tree, 25000);
        assertEquals(COMPONENTS, blueprints.componentsVisibleTo(p, COMPONENTS));
    }

    // ---- persistence

    @Test
    void saveLoadRoundTrip() throws Exception {
        p.buy(tree.node("bill_clip"), tree, 4000);
        feed(new Purchase(WHEAT, "farm", 1, 55, 2), new Sale(WHEAT, "farm", 200, 5000, 1.0, 0.62, 2),
                sale("minecraft:bone", "mobs", 5, 250, 2));
        StringWriter w = new StringWriter();
        ProgressStateIO.write(p, w);
        PlayerProgress back = ProgressStateIO.read(new StringReader(w.toString()));
        assertEquals(p, back);
        assertTrue(back.hasBlueprint(BILL_CLIP));
        assertTrue(back.hasCompleted("meet_the_spread"));

        // Mid-quest history survives: 56 more wheat the same day completes Flooding the Market.
        assertEquals(List.of("flooding_the_market"),
                back.apply(new Sale(WHEAT, "farm", 56, 900, 0.62, 0.55, 2), quests).stream().map(Quest::id).toList());
    }

    @Test
    void emptyProgressRoundTrips() throws Exception {
        StringWriter w = new StringWriter();
        ProgressStateIO.write(p, w);
        assertEquals(p, ProgressStateIO.read(new StringReader(w.toString())));
    }

    @Test
    void malformedSaveLineIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> ProgressStateIO.read(new StringReader(ProgressStateIO.HEADER + "\nlowest\tminecraft:wheat\n")));
    }
}
