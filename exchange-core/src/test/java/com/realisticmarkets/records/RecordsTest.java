package com.realisticmarkets.records;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.realisticmarkets.progression.ProgressionEvent;
import com.realisticmarkets.progression.QuestGoal;
import com.realisticmarkets.records.Ledger.Source;
import com.realisticmarkets.records.NetWorth.Kind;
import com.realisticmarkets.records.NetWorth.Line;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecordsTest {

    @Test
    void theLedgerSumsIncomeBySourceOverTheLastDays() {
        Ledger l = new Ledger();
        l.record("a", Source.DEALER_SALES, 9.5, 5_000); // before the node: not recorded
        assertFalse(l.isOpen("a"));
        l.open("a", 10);
        l.record("a", Source.DEALER_SALES, 9.9, 5_000); // still before
        l.record("a", Source.DEALER_SALES, 10.2, 2_000);
        l.record("a", Source.DEALER_SALES, 10.8, 1_000);
        l.record("a", Source.INTEREST, 12.0, 300);
        l.record("a", Source.TRADING_GAINS, 15.5, 4_000);
        l.record("a", Source.TRADING_GAINS, 16.5, -1_500); // a sale below cost
        l.record("a", Source.COUPONS, 30.1, 2_700);
        l.record("b", Source.DIVIDENDS, 12, 999); // another account, never opened

        assertEquals(3_000, l.income("a", Source.DEALER_SALES, 16, 7), "days 10-16 include day 10");
        assertEquals(0, l.income("a", Source.DEALER_SALES, 17, 7), "days 11-17 don't");
        assertEquals(2_500, l.income("a", Source.TRADING_GAINS, 16, 7), "gains net of losses");
        assertEquals(3_000 + 300 + 2_500, l.totalIncome("a", 16, 7));
        Map<Source, Long> month = l.income("a", 30, 30);
        assertEquals(Source.values().length, month.size(), "every source listed");
        assertEquals(3_000 + 300 + 2_500 + 2_700, month.values().stream().mapToLong(Long::longValue).sum());
        assertEquals(0, l.totalIncome("b", 30, 30), "unopened accounts record nothing");

        l.open("a", 25); // buying again doesn't reset
        assertEquals(3_000, l.income("a", Source.DEALER_SALES, 16, 7));

        l.record("a", Source.DIVIDENDS, 100, 50);
        assertEquals(50, l.totalIncome("a", 100, 90), "days older than " + Ledger.KEEP_DAYS + " are dropped");
    }

    @Test
    void theNetWorthLineCarriesTheLastLookForward() {
        Ledger l = new Ledger();
        l.open("a", 5);
        l.noteNetWorth("a", 6.2, 10_000);
        l.noteNetWorth("a", 6.9, 12_000); // last look of the day wins
        l.noteNetWorth("a", 9.1, 9_000);
        long[] line = l.netWorthLine("a", 10, 6); // days 5..10
        assertArrayEquals(new long[] {Ledger.NO_DATA, 12_000, 12_000, 12_000, 9_000, 9_000}, line);
        assertTrue(l.netWorthOn("a", 4).isEmpty());
    }

    @Test
    void theLedgerSavesAndLoads() throws Exception {
        Ledger l = new Ledger();
        l.open("a", 3);
        l.record("a", Source.FLOOR_SALES, 4, 1_234);
        l.record("a", Source.TRADING_GAINS, 5, -200);
        l.noteNetWorth("a", 5, 77_000);
        l.open("b", 8);
        StringWriter w = new StringWriter();
        l.write(w);
        Ledger back = Ledger.read(new StringReader(w.toString()));
        assertEquals(1_234, back.income("a", Source.FLOOR_SALES, 5, 7));
        assertEquals(-200, back.income("a", Source.TRADING_GAINS, 5, 7));
        assertEquals(77_000, back.netWorthOn("a", 6).orElseThrow());
        assertTrue(back.isOpen("b"));
        back.record("b", Source.INTEREST, 7, 10);
        assertEquals(0, back.totalIncome("b", 7, 7), "b's ledger still opens on day 8");
    }

    @Test
    void netWorthIsAssetsAtTheirMarksMinusDebts() {
        NetWorth nw = new NetWorth()
                .add(Line.cash("Safe Deposit Box", 12_340))
                .add(new Line(Kind.VAULT, "Vault balance", "Bank", 1, 500_000, -1))
                .add(new Line(Kind.CDS, "CD, 28 days", "Bank", 2, 100_000, 200_000))
                .add(new Line(Kind.SHARES, "OWL", "Safe Deposit Box", 10, 4_150, 40_000))
                .add(new Line(Kind.SHARES, "OWL", "Trade Route Crate", 5, 4_150, -1)) // cost unknown
                .add(new Line(Kind.BONDS, "Treasury, day 216", "Safe Deposit Box", 3, 10_283, 30_075))
                .add(new Line(Kind.GOODS, "Wheat", "Trade Route Crate", 64, 21, -1))
                .add(Line.debt("Loan", "Bank", 150_000))
                .add(new Line(Kind.SHARES, "RSD", "Safe Deposit Box", 0, 900, 0)); // nothing held: left out

        long assets = 12_340 + 500_000 + 200_000 + 41_500 + 20_750 + 30_849 + 1_344;
        assertEquals(assets, nw.assets());
        assertEquals(150_000, nw.debts());
        assertEquals(assets - 150_000, nw.total());
        assertEquals(8, nw.lines().size());
        assertEquals(41_500 + 20_750, nw.byKind().get(Kind.SHARES));
        assertEquals(150_000, nw.byKind().get(Kind.DEBTS));
        assertEquals(1_344, nw.byKind().get(Kind.GOODS), "64 wheat at 21 cents");

        assertEquals(1_500, nw.lines().get(3).profit().orElseThrow(), "10 OWL bought for $400, worth $415");
        assertTrue(nw.lines().get(4).profit().isEmpty(), "unknown cost, no profit shown");
        assertTrue(nw.lines().get(7).profit().isEmpty(), "debts have no profit");
        assertEquals(0 + 0 + 1_500 + 774, nw.knownProfit(), "cash 0, CDs 0, OWL +$15, bonds +$7.74");

        assertThrows(IllegalArgumentException.class, () -> new Line(Kind.CASH, "x", "y", -1, 1, 0));
        assertEquals(-150_000, new NetWorth().add(Line.debt("Loan", "Bank", 150_000)).total(), "net worth can be negative");
    }

    @Test
    void progressionEventsFeedTheLedger() {
        Ledger l = new Ledger();
        l.open("a", 0);
        l.record("a", new ProgressionEvent.Sale("minecraft:wheat", "crops", 64, 2_100, 1, 0.9, 1));
        l.record("a", new ProgressionEvent.Shipment(3_000, 3_300, 1));
        l.record("a", new ProgressionEvent.FloorOrderDone("minecraft:wheat", false, true, 10, 500, 30, 2));
        l.record("a", new ProgressionEvent.FloorOrderDone("minecraft:wheat", true, true, 10, 400, 30, 2)); // a purchase
        l.record("a", new ProgressionEvent.Interest(120, 5_000, 2));
        l.record("a", new ProgressionEvent.CdRedeemed(10_000, 10_450, true, 3));
        l.record("a", new ProgressionEvent.CdRedeemed(10_000, 10_000, false, 3)); // early: principal only
        l.record("a", new ProgressionEvent.DividendCollected("OWL", 700, 3));
        l.record("a", new ProgressionEvent.CouponCollected(2_700, 3));
        l.record("a", new ProgressionEvent.StockSold("OWL", 10, 4_500, 4_000, 4));
        l.record("a", new ProgressionEvent.StockSold("RSD", 10, 900, -1, 4)); // cost unknown: not a known gain
        l.record("a", new ProgressionEvent.BondSold(1, 9_800, 10_025, false, 4));
        l.record("a", new ProgressionEvent.Craft("x", 1, 4));
        Map<Source, Long> week = l.income("a", 6, 7);
        assertEquals(2_100 + 3_300, week.get(Source.DEALER_SALES), "the Dealer and the Capital");
        assertEquals(500, week.get(Source.FLOOR_SALES), "Floor sales, not purchases");
        assertEquals(120 + 450, week.get(Source.INTEREST), "vault interest and a matured CD's gain");
        assertEquals(700, week.get(Source.DIVIDENDS));
        assertEquals(2_700, week.get(Source.COUPONS));
        assertEquals(500 - 225, week.get(Source.TRADING_GAINS), "+$5 on OWL, -$2.25 on the bond");
    }

    @Test
    void theCalendarListsWhatFallsDueSoonestFirst() {
        Calendar c = new Calendar()
                .add(21, Calendar.Kind.RATE_DECISION, "", 0)
                .add(17, Calendar.Kind.COUPON, "Treasury, day 216", 2_700)
                .add(17, Calendar.Kind.COUPON, "Treasury, day 216", 540) // the same bond in another box
                .add(15, Calendar.Kind.MARGIN_CHECK, "", 0)
                .add(9, Calendar.Kind.CD_MATURITY, "CD", 10_450) // already past
                .add(28, Calendar.Kind.EARNINGS, "OWL", 0);
        var next = c.upcoming(15, 3);
        assertEquals(3, next.size());
        assertEquals(Calendar.Kind.MARGIN_CHECK, next.get(0).kind(), "today's first");
        assertEquals(3_240, next.get(1).cents(), "one line per bond and day");
        assertEquals(21, next.get(2).day());
        assertEquals(21, Calendar.nextEvery(14, 7), "the next review after a review day");
        assertEquals(21, Calendar.nextEvery(20, 7));
        assertEquals(7, Calendar.nextEvery(0, 7));
    }

    @Test
    void balanceSheetQuestGoal() {
        assertEquals(new QuestGoal.BalanceSheet(5_000_000), QuestGoal.parse("balance_sheet:5000000"));
    }
}
