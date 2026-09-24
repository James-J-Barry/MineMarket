package com.realisticmarkets.sim;

import com.realisticmarkets.contracts.BankAccount;
import com.realisticmarkets.contracts.BankParams;
import com.realisticmarkets.dealer.Capital;
import com.realisticmarkets.dealer.Dealer;
import com.realisticmarkets.dealer.DealerCatalog;
import com.realisticmarkets.dealer.DealerParams;
import com.realisticmarkets.dealer.ShipmentBook;
import com.realisticmarkets.exchange.RejectedException;
import com.realisticmarkets.money.Money;
import com.realisticmarkets.progression.Blueprint;
import com.realisticmarkets.progression.Blueprints;
import com.realisticmarkets.progression.PlayerProgress;
import com.realisticmarkets.progression.ProgressionEvent;
import com.realisticmarkets.progression.Quest;
import com.realisticmarkets.progression.Quests;
import com.realisticmarkets.progression.UnlockNode;
import com.realisticmarkets.progression.UnlockTree;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * M2 balance check: a scripted player gathers a mix of goods (a production profile), sells to the Dealer once
 * per in-game day, chases quests 1-6, buys Tier 1 nodes and the components to craft one of each blueprint.
 * Reports play time until all of Tier 1 is owned and crafted (design target: about 2 hours), then how much the
 * Trade Route Crate lifts steady income afterwards (M3 target: +30-50% for a farm-heavy player).
 *
 * <p>Strategy (a player who has read the guides): sell everything once a day, except hold an item whose market
 * price is under 90% of normal; stockpile cobblestone to dump 256 at once for Flooding the Market; while
 * Diversify is open, hold a group until it's worth $20 in one go. Vanilla craft materials (leather, logs, iron)
 * are kept back once their blueprint is unlocked; glass panes are assumed on hand.
 */
public final class ProgressionSim {
    static final double MINUTES_PER_DAY = 20.0;
    static final int MAX_DAYS = 200;
    static final String DUMP_ITEM = "minecraft:cobblestone";
    static final double HOLD_BELOW = 0.90;
    static final long GROUP_TARGET_CENTS = 2050;
    static final String BANK_VAULT = "realisticmarkets:bank_vault";
    static final int AFTER_DAYS = 10;
    static final BankParams BANK = BankParams.loadDefault();

    record Source(String item, double perDay, int fromDay, int ramp) {
        double output(int day) {
            if (day < fromDay) return 0;
            if (ramp <= 0 || day >= fromDay + ramp) return perDay;
            return perDay * (1.0 / 3 + (2.0 / 3) * (day - fromDay) / ramp);
        }
    }

    record Result(double scale, int day, long nodeCents, long componentCents, long questCents,
                  Map<String, Integer> questDay, long cashEnd, boolean done, long interestCents,
                  long recentIncomePerDay, long recentInterestPerDay) {
        double hours() { return day * MINUTES_PER_DAY / 60.0; }
        double componentShare() { return componentCents / (double) (nodeCents + componentCents); }
    }

    public static void main(String[] args) throws Exception {
        String profile = args.length > 0 ? args[0] : "early_survival";
        List<Source> sources = loadProfile(profile);
        Path out = Path.of("build/sim/progression_" + profile + ".csv");
        Files.createDirectories(out.toAbsolutePath().getParent());

        System.out.printf(Locale.ROOT, "Tier 1 progression, profile '%s' (1 in-game day = %.0f min of play, one Dealer visit per day)%n",
                profile, MINUTES_PER_DAY);
        Result base;
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(out))) {
            w.println("day,cash_cents,sold_cents,nodes,crafted,quests");
            base = run(sources, 1.0, w, 1);
        }
        print(base);
        System.out.println();
        System.out.println("Sensitivity (all gathering rates scaled):");
        System.out.printf(Locale.ROOT, "%8s  %8s  %8s  %10s%n", "rates", "days", "hours", "comp share");
        for (double s : new double[] {0.25, 0.5, 0.75, 1.0, 1.5}) {
            Result r = run(sources, s, null, 1);
            System.out.printf(Locale.ROOT, "%7.0f%%  %8s  %8s  %9.0f%%%n", s * 100,
                    r.done() ? String.valueOf(r.day()) : ">" + MAX_DAYS, r.done() ? String.format(Locale.ROOT, "%.1f", r.hours()) : "-",
                    r.componentShare() * 100);
        }
        System.out.printf(Locale.ROOT, "Target: about 2 hours; components noticeable but under ~25%%. CSV: %s%n", out.toAbsolutePath());

        System.out.println();
        System.out.printf(Locale.ROOT, "After Tier 1: income with vs without the Trade Route Crate (licensed, %d days, steady = last half)%n",
                CRATE_DAYS);
        System.out.printf(Locale.ROOT, "%-16s  %12s  %12s  %8s  %s%n", "profile", "local $/day", "crate $/day", "uplift", "shipped share");
        for (String name : new LinkedHashSet<>(List.of(profile, "farm_heavy"))) {
            List<Source> src = loadProfile(name);
            CrateResult local = runCrate(src, false);
            CrateResult crate = runCrate(src, true);
            System.out.printf(Locale.ROOT, "%-16s  %12s  %12s  %7.0f%%  %12.0f%%%n", name,
                    Money.format(local.centsPerDay()), Money.format(crate.centsPerDay()),
                    (crate.centsPerDay() / (double) local.centsPerDay() - 1) * 100, crate.shippedShare() * 100);
        }
        System.out.println("Target (M3): the crate lifts a farm-heavy player's income by roughly 30-50%, not multiplies it.");

        System.out.println();
        System.out.printf(Locale.ROOT, "Tier 2 complete (Bank Vault, CD and Loan Note owned, vault built; cumulative play time;%n"
                + "  interest and income measured over the %d days after, all savings in the vault):%n", AFTER_DAYS);
        System.out.printf(Locale.ROOT, "%-16s  %6s  %7s  %12s  %14s  %16s%n", "profile", "days", "hours", "Tier 2 spend",
                "interest total", "income vs interest/day");
        for (String name : new LinkedHashSet<>(List.of(profile, "farm_heavy"))) {
            Result r = run(loadProfile(name), 1.0, null, 2);
            System.out.printf(Locale.ROOT, "%-16s  %6s  %7s  %12s  %14s  %9s vs %s%n", name,
                    r.done() ? String.valueOf(r.day()) : ">" + MAX_DAYS, r.done() ? String.format(Locale.ROOT, "%.1f", r.hours()) : "-",
                    Money.format(r.nodeCents() + r.componentCents()), Money.format(r.interestCents()),
                    Money.format(r.recentIncomePerDay()), Money.format(r.recentInterestPerDay()));
        }
        System.out.println("Design target: all of Tier 2 by about 5 h (parked: James kept current prices, 2026-09-24).");

        System.out.println();
        System.out.println("Tier 3 (M5a): all of Tiers 1-3 owned, Trading Floor built and Order Slips crafted; cumulative play time.");
        System.out.println("  Components include materials this profile never gathers (gold), bought from the Dealer.");
        System.out.printf(Locale.ROOT, "%-16s  %6s  %7s  %12s  %14s%n", "profile", "days", "hours", "total spend", "quest rewards");
        for (String name : new LinkedHashSet<>(List.of(profile, "farm_heavy"))) {
            Result r = run(loadProfile(name), 1.0, null, 3);
            System.out.printf(Locale.ROOT, "%-16s  %6s  %7s  %12s  %14s%n", name,
                    r.done() ? String.valueOf(r.day()) : ">" + MAX_DAYS, r.done() ? String.format(Locale.ROOT, "%.1f", r.hours()) : "-",
                    Money.format(r.nodeCents() + r.componentCents()), Money.format(r.questCents()));
        }
        System.out.println("Design target: Tier 3 by about 10 h (the doc's pacing; Tier 2 already runs long).");

        System.out.println();
        System.out.println("Tier 4 (M6): all of Tiers 1-4 owned and every blueprint crafted once; cumulative play time.");
        System.out.println("  A gathering player who saves in the vault and never trades or invests (a floor on the pace).");
        System.out.printf(Locale.ROOT, "%-16s  %6s  %7s  %12s  %14s%n", "profile", "days", "hours", "total spend", "quest rewards");
        for (String name : new LinkedHashSet<>(List.of(profile, "farm_heavy"))) {
            Result r = run(loadProfile(name), 1.0, null, 4);
            System.out.printf(Locale.ROOT, "%-16s  %6s  %7s  %12s  %14s%n", name,
                    r.done() ? String.valueOf(r.day()) : ">" + MAX_DAYS, r.done() ? String.format(Locale.ROOT, "%.1f", r.hours()) : "-",
                    Money.format(r.nodeCents() + r.componentCents()), Money.format(r.questCents()));
        }
        System.out.println("Design target: Tier 4 by about 15 h.");
    }

    static final int CRATE_DAYS = 30;
    static final int CRATE_STACKS = 9;

    record CrateResult(long centsPerDay, double shippedShare) {}

    /**
     * Steady-state daily income after Tier 1, selling every day's output. With the crate, each item goes wherever
     * it pays more (all local, all Capital, or half each, judged on today's quotes), up to 9 stacks a day; a
     * shipment is priced when it arrives the next day. Both runs hold the Merchant License.
     */
    static CrateResult runCrate(List<Source> sources, boolean useCrate) {
        DealerCatalog catalog = DealerCatalog.loadDefault();
        Dealer local = new Dealer(catalog, DealerParams.defaults(), 7L);
        com.realisticmarkets.dealer.WorldEvents events = com.realisticmarkets.dealer.WorldEvents.loadDefault(catalog, 7L);
        local.setShocks(events);
        Capital.Config cfg = Capital.loadDefault();
        Dealer capital = Capital.dealer(catalog, DealerParams.defaults(), cfg, 11L);
        capital.setShocks(events);
        ShipmentBook book = new ShipmentBook();
        long steadyCents = 0, steadyShipped = 0;
        int steadyDays = 0;
        for (int day = 1; day <= CRATE_DAYS; day++) {
            long income = 0, shippedIncome = 0;
            for (ShipmentBook.Settlement st : book.settleDue(capital, day, cfg.freight())) {
                income += st.payoutCents();
                shippedIncome += st.payoutCents();
            }
            Map<String, Integer> shipment = new LinkedHashMap<>();
            int stacksLeft = useCrate ? CRATE_STACKS : 0;
            for (Source s : sources) {
                int qty = (int) Math.floor(s.output(day));
                if (qty <= 0) continue;
                int toCapital = 0;
                if (stacksLeft > 0 && capital.catalog().trades(s.item())) {
                    int max = Math.min(qty, stacksLeft * 64);
                    long allLocal = quote(local, s.item(), qty, day, true);
                    long allShip = ShipmentBook.estimate(capital, Map.of(s.item(), max), day, cfg.freight())
                            + quote(local, s.item(), qty - max, day, true);
                    int half = Math.min(max, qty / 2);
                    long split = ShipmentBook.estimate(capital, Map.of(s.item(), half), day, cfg.freight())
                            + quote(local, s.item(), qty - half, day, true);
                    if (allShip > allLocal && allShip >= split) toCapital = max;
                    else if (split > allLocal) toCapital = half;
                }
                if (toCapital > 0) {
                    shipment.put(s.item(), toCapital);
                    stacksLeft -= (toCapital + 63) / 64;
                }
                if (qty - toCapital > 0) {
                    try {
                        income += local.sell(s.item(), qty - toCapital, day, true).cents();
                    } catch (RejectedException collapsed) {
                        // sold for nothing
                    }
                }
            }
            if (!shipment.isEmpty()) book.ship("sim", "crate", shipment, 0, day, capital, cfg.transitDays());
            if (day > CRATE_DAYS / 2) {
                steadyCents += income;
                steadyShipped += shippedIncome;
                steadyDays++;
            }
        }
        return new CrateResult(steadyCents / steadyDays, steadyCents == 0 ? 0 : steadyShipped / (double) steadyCents);
    }

    static void print(Result r) {
        if (!r.done()) {
            System.out.printf(Locale.ROOT, "Tier 1 NOT complete after %d days%n", MAX_DAYS);
        } else {
            System.out.printf(Locale.ROOT, "Tier 1 owned and crafted on day %d: %.1f hours of play%n", r.day(), r.hours());
        }
        System.out.printf(Locale.ROOT, "  Spent: nodes %s + components %s = %s; components are %.0f%% of Tier 1 spending%n",
                Money.format(r.nodeCents()), Money.format(r.componentCents()), Money.format(r.nodeCents() + r.componentCents()),
                r.componentShare() * 100);
        System.out.printf(Locale.ROOT, "  Quest rewards received: %s; cash left: %s%n", Money.format(r.questCents()), Money.format(r.cashEnd()));
        r.questDay().forEach((q, d) -> System.out.printf(Locale.ROOT, "  quest %-20s day %d%n", q, d));
    }

    /** Plays until every node of tier {@code <= maxTier} is owned and each of their blueprints crafted once. */
    static Result run(List<Source> sources, double scale, PrintWriter csv, int maxTier) {
        DealerCatalog catalog = DealerCatalog.loadDefault();
        Dealer dealer = new Dealer(catalog, DealerParams.defaults(), 7L);
        dealer.setShocks(com.realisticmarkets.dealer.WorldEvents.loadDefault(catalog, 7L));
        UnlockTree tree = UnlockTree.loadDefault();
        Quests quests = Quests.loadDefault();
        Blueprints blueprints = Blueprints.loadDefault();
        PlayerProgress p = new PlayerProgress();

        Map<String, Double> stock = new LinkedHashMap<>();
        Map<String, Integer> questDay = new LinkedHashMap<>();
        Set<String> crafted = new LinkedHashSet<>();
        long[] cash = {0};
        long nodeCents = 0, componentCents = 0;
        long[] questCents = {0};
        boolean dumped = false;
        boolean spreadDone = false;
        List<UnlockNode> targetNodes = new ArrayList<>();
        for (UnlockNode n : tree.all()) if (n.tier() <= maxTier) targetNodes.add(n);
        Set<String> targetBlueprints = new LinkedHashSet<>();
        for (Blueprint bp : blueprints.all()) if (tree.node(bp.node()).tier() <= maxTier) targetBlueprints.add(bp.result());
        BankAccount vault = null; // exists once the Bank Vault is built; all spare cash sits in it
        long interestCents = 0;
        List<Long> incomeLog = new ArrayList<>();
        List<Long> interestLog = new ArrayList<>();
        int doneDay = -1;

        for (int day = 1; day <= MAX_DAYS; day++) {
            final int today = day;
            for (Source s : sources) stock.merge(s.item(), s.output(day) * scale, Double::sum);
            java.util.function.Consumer<ProgressionEvent> emit = e -> {
                for (Quest q : p.apply(e, quests)) {
                    cash[0] += q.rewardCents();
                    questCents[0] += q.rewardCents();
                    questDay.put(q.id(), today);
                }
            };
            emit.accept(new ProgressionEvent.DayRollover(day));
            long interestToday = 0;
            if (vault != null) {
                interestToday = vault.accrueTo(day, BANK.interestRate());
                cash[0] += interestToday;
                interestCents += interestToday;
                if (interestToday > 0) emit.accept(new ProgressionEvent.Interest(interestToday, vault.interestTotalCents(), day));
            }

            // Quest 2, once affordable: buy one wheat and sell it straight back.
            if (!spreadDone && cash[0] >= 100) {
                long cost = dealer.buy("minecraft:wheat", 1, day, false).cents();
                cash[0] -= cost;
                emit.accept(new ProgressionEvent.Purchase("minecraft:wheat", "farm", 1, cost, day));
                emit.accept(sell(dealer, "minecraft:wheat", 1, day, false, cash));
                spreadDone = true;
            }

            Map<String, Integer> reserve = reserveFor(p, blueprints, crafted);
            boolean licensed = p.hasPerk("merchant_license");
            boolean diversifyOpen = !p.hasCompleted("diversify");
            Map<String, Long> groupValue = new LinkedHashMap<>();
            Map<String, Integer> toSell = new LinkedHashMap<>();
            for (Map.Entry<String, Double> e : stock.entrySet()) {
                String item = e.getKey();
                int qty = (int) Math.floor(e.getValue()) - reserve.getOrDefault(item, 0);
                if (qty <= 0) continue;
                if (item.equals(DUMP_ITEM) && !dumped) {
                    if (qty < 256) continue;
                    qty = 256;
                    dumped = true;
                } else if (dealer.mid(item, day) / dealer.normalValue(item, day) < HOLD_BELOW) {
                    continue;
                }
                toSell.put(item, qty);
                groupValue.merge(catalog.spec(item).group(), quote(dealer, item, qty, day, licensed), Long::sum);
            }
            long soldToday = 0;
            for (Map.Entry<String, Integer> e : toSell.entrySet()) {
                String group = catalog.spec(e.getKey()).group();
                boolean dump = e.getKey().equals(DUMP_ITEM) && e.getValue() == 256;
                if (diversifyOpen && !dump && groupValue.get(group) < GROUP_TARGET_CENTS) continue;
                ProgressionEvent.Sale sale = sell(dealer, e.getKey(), e.getValue(), day, licensed, cash);
                if (sale == null) continue;
                stock.merge(e.getKey(), (double) -e.getValue(), Double::sum);
                soldToday += sale.proceedsCents();
                emit.accept(sale);
            }
            emit.accept(new ProgressionEvent.NetWorth(cash[0], cash[0] + stockValue(dealer, stock, day, licensed), day));

            // Spend: cheapest affordable node first, then components to craft each unlocked blueprint once.
            List<UnlockNode> nodes = new ArrayList<>(targetNodes);
            nodes.sort(Comparator.comparingInt(UnlockNode::tier).thenComparingLong(p::costOf));
            for (UnlockNode n : nodes) {
                if (p.canBuy(n, tree, cash[0])) {
                    long cost = p.buy(n, tree, cash[0]);
                    cash[0] -= cost;
                    nodeCents += cost;
                }
            }
            for (Blueprint bp : blueprints.all()) {
                if (!p.hasBlueprint(bp.result()) || crafted.contains(bp.result()) || !targetBlueprints.contains(bp.result())) continue;
                long need = 0;
                for (Blueprint.Material m : bp.materials()) {
                    if (catalog.trades(m.item()) && "components".equals(catalog.spec(m.item()).group())) {
                        need += dealer.quoteBuy(m.item(), m.count(), day, p.hasPerk("merchant_license")).cents();
                    } else if (buysFromDealer(m, stock, catalog)) {
                        need += dealer.quoteBuy(m.item(), m.count(), day, p.hasPerk("merchant_license")).cents();
                    }
                }
                if (need > cash[0] || !haveVanilla(bp, stock, catalog)) continue;
                for (Blueprint.Material m : bp.materials()) {
                    if (buysFromDealer(m, stock, catalog)) { // a material this player never gathers (gold)
                        long c = dealer.buy(m.item(), m.count(), day, p.hasPerk("merchant_license")).cents();
                        cash[0] -= c;
                        componentCents += c;
                        emit.accept(new ProgressionEvent.Purchase(m.item(), catalog.spec(m.item()).group(), m.count(), c, day));
                    } else if (catalog.trades(m.item()) && "components".equals(catalog.spec(m.item()).group())) {
                        long c = dealer.buy(m.item(), m.count(), day, p.hasPerk("merchant_license")).cents();
                        cash[0] -= c;
                        componentCents += c;
                        emit.accept(new ProgressionEvent.Purchase(m.item(), "components", m.count(), c, day));
                    } else if (!m.item().equals("minecraft:glass_pane")) {
                        String src = vanillaSource(m.item());
                        stock.merge(src, -vanillaUnits(m.item(), m.count()), Double::sum);
                    }
                }
                crafted.add(bp.result());
                emit.accept(new ProgressionEvent.Craft(bp.result(), 1, day));
                if (bp.result().equals(BANK_VAULT)) vault = new BankAccount(day);
            }
            if (vault != null) { // end of day: whatever cash is left goes into the vault overnight
                long diff = cash[0] - vault.balanceCents();
                if (diff > 0) vault.deposit(diff, day, BankAccount.Kind.DEPOSIT);
                else if (diff < 0) vault.withdraw(-diff, day, BankAccount.Kind.WITHDRAW);
            }
            incomeLog.add(soldToday);
            interestLog.add(interestToday);

            if (csv != null) {
                csv.printf(Locale.ROOT, "%d,%d,%d,%d,%d,%d%n", day, cash[0], soldToday, p.nodes().size(), crafted.size(),
                        p.completedQuests().size());
            }
            boolean allNodes = targetNodes.stream().allMatch(n -> p.hasNode(n.id()));
            if (doneDay < 0 && allNodes && crafted.containsAll(targetBlueprints)) {
                doneDay = day;
                if (vault == null) {
                    return new Result(scale, day, nodeCents, componentCents, questCents[0], questDay, cash[0], true,
                            interestCents, recent(incomeLog), 0);
                }
            }
            // With a vault, keep playing AFTER_DAYS more (buying nothing) to see savings earn next to income.
            if (doneDay > 0 && day == doneDay + AFTER_DAYS) {
                return new Result(scale, doneDay, nodeCents, componentCents, questCents[0], questDay, cash[0], true,
                        interestCents, recent(incomeLog), recent(interestLog));
            }
        }
        if (doneDay > 0) { // finished, but the days after ran past MAX_DAYS
            return new Result(scale, doneDay, nodeCents, componentCents, questCents[0], questDay, cash[0], true,
                    interestCents, recent(incomeLog), recent(interestLog));
        }
        return new Result(scale, MAX_DAYS, nodeCents, componentCents, questCents[0], questDay, cash[0], false,
                interestCents, recent(incomeLog), recent(interestLog));
    }

    /** Average of the last three days. */
    private static long recent(List<Long> log) {
        int n = Math.min(3, log.size());
        long sum = 0;
        for (int i = log.size() - n; i < log.size(); i++) sum += log.get(i);
        return n == 0 ? 0 : sum / n;
    }

    private static ProgressionEvent.Sale sell(Dealer d, String item, int qty, int day, boolean licensed, long[] cash) {
        double before = d.mid(item, day) / d.normalValue(item, day);
        long cents;
        try {
            cents = d.sell(item, qty, day, licensed).cents();
        } catch (RejectedException e) {
            return null;
        }
        cash[0] += cents;
        double after = d.mid(item, day) / d.normalValue(item, day);
        return new ProgressionEvent.Sale(item, d.catalog().spec(item).group(), qty, cents, before, after, day);
    }

    private static long quote(Dealer d, String item, int qty, int day, boolean licensed) {
        try {
            return d.quoteSell(item, qty, day, licensed).cents();
        } catch (RejectedException e) {
            return 0;
        }
    }

    private static long stockValue(Dealer d, Map<String, Double> stock, int day, boolean licensed) {
        long v = 0;
        for (Map.Entry<String, Double> e : stock.entrySet()) {
            int q = (int) Math.floor(e.getValue());
            if (q > 0) v += quote(d, e.getKey(), q, day, licensed);
        }
        return v;
    }

    /** Vanilla materials to keep back for unlocked, not-yet-crafted blueprints, in stock-item units. */
    private static Map<String, Integer> reserveFor(PlayerProgress p, Blueprints bps, Set<String> crafted) {
        Map<String, Integer> r = new LinkedHashMap<>();
        for (Blueprint bp : bps.all()) {
            if (!p.hasBlueprint(bp.result()) || crafted.contains(bp.result())) continue;
            for (Blueprint.Material m : bp.materials()) {
                if (m.item().startsWith("realisticmarkets:") || m.item().equals("minecraft:glass_pane")) continue;
                r.merge(vanillaSource(m.item()), (int) Math.ceil(vanillaUnits(m.item(), m.count())), Integer::sum);
            }
        }
        return r;
    }

    /** A vanilla material the profile never produces, bought from the Dealer when a blueprint needs it. */
    private static boolean buysFromDealer(Blueprint.Material m, Map<String, Double> stock, DealerCatalog catalog) {
        return !m.item().startsWith("realisticmarkets:") && !m.item().startsWith("#") && catalog.trades(m.item())
                && !"components".equals(catalog.spec(m.item()).group()) && !stock.containsKey(vanillaSource(m.item()));
    }

    private static boolean haveVanilla(Blueprint bp, Map<String, Double> stock, DealerCatalog catalog) {
        for (Blueprint.Material m : bp.materials()) {
            if (m.item().startsWith("realisticmarkets:") || m.item().equals("minecraft:glass_pane")) continue;
            if (buysFromDealer(m, stock, catalog)) continue;
            if (stock.getOrDefault(vanillaSource(m.item()), 0.0) < vanillaUnits(m.item(), m.count())) return false;
        }
        return true;
    }

    /** Planks come from oak logs (1 log = 4 planks), iron blocks from ingots (9 each); everything else is itself. */
    private static String vanillaSource(String material) {
        if (material.equals("minecraft:oak_planks") || material.equals("#minecraft:planks")) return "minecraft:oak_log";
        if (material.equals("minecraft:iron_block")) return "minecraft:iron_ingot";
        return material;
    }

    private static double vanillaUnits(String material, int count) {
        if (material.equals("minecraft:iron_block")) return count * 9.0;
        return vanillaSource(material).equals(material) ? count : count / 4.0;
    }

    static List<Source> loadProfile(String name) throws IOException {
        String path = "/profiles/" + name + ".csv";
        List<Source> out = new ArrayList<>();
        try (InputStream in = ProgressionSim.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalArgumentException("no profile " + path);
            BufferedReader br = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.strip();
                if (t.isEmpty() || t.startsWith("#") || t.startsWith("item,")) continue;
                String[] c = t.split(",");
                out.add(new Source(DealerCatalog.normalize(c[0]), Double.parseDouble(c[1]), Integer.parseInt(c[2]),
                        Integer.parseInt(c[3])));
            }
        }
        return out;
    }
}
