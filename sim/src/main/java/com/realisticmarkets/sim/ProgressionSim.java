package com.realisticmarkets.sim;

import com.realisticmarkets.dealer.Dealer;
import com.realisticmarkets.dealer.DealerCatalog;
import com.realisticmarkets.dealer.DealerParams;
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
 * Reports play time until all of Tier 1 is owned and crafted. Design target: about 2 hours.
 *
 * <p>Strategy (a player who has read the guides): sell everything once a day, except hold an item whose market
 * price is under 90% of normal; stockpile cobblestone to dump 256 at once for Flooding the Market; while
 * Diversify is open, hold a group until it's worth $20 in one go. Vanilla craft materials (leather, logs, iron)
 * are kept back once their blueprint is unlocked; glass panes are assumed on hand.
 */
public final class ProgressionSim {
    static final double MINUTES_PER_DAY = 20.0;
    static final int MAX_DAYS = 60;
    static final String DUMP_ITEM = "minecraft:cobblestone";
    static final double HOLD_BELOW = 0.90;
    static final long GROUP_TARGET_CENTS = 2050;

    record Source(String item, double perDay, int fromDay, int ramp) {
        double output(int day) {
            if (day < fromDay) return 0;
            if (ramp <= 0 || day >= fromDay + ramp) return perDay;
            return perDay * (1.0 / 3 + (2.0 / 3) * (day - fromDay) / ramp);
        }
    }

    record Result(double scale, int day, long nodeCents, long componentCents, long questCents,
                  Map<String, Integer> questDay, long cashEnd, boolean done) {
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
            base = run(sources, 1.0, w);
        }
        print(base);
        System.out.println();
        System.out.println("Sensitivity (all gathering rates scaled):");
        System.out.printf(Locale.ROOT, "%8s  %8s  %8s  %10s%n", "rates", "days", "hours", "comp share");
        for (double s : new double[] {0.25, 0.5, 0.75, 1.0, 1.5}) {
            Result r = run(sources, s, null);
            System.out.printf(Locale.ROOT, "%7.0f%%  %8s  %8s  %9.0f%%%n", s * 100,
                    r.done() ? String.valueOf(r.day()) : ">" + MAX_DAYS, r.done() ? String.format(Locale.ROOT, "%.1f", r.hours()) : "-",
                    r.componentShare() * 100);
        }
        System.out.printf(Locale.ROOT, "Target: about 2 hours; components noticeable but under ~25%%. CSV: %s%n", out.toAbsolutePath());
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

    static Result run(List<Source> sources, double scale, PrintWriter csv) {
        DealerCatalog catalog = DealerCatalog.loadDefault();
        Dealer dealer = new Dealer(catalog, DealerParams.defaults(), 7L);
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
                } else if (dealer.mid(item, day) / dealer.fairValue(item, day) < HOLD_BELOW) {
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
            List<UnlockNode> nodes = new ArrayList<>(tree.tier(1));
            nodes.sort(Comparator.comparingLong(UnlockNode::costCents));
            for (UnlockNode n : nodes) {
                if (p.canBuy(n, tree, cash[0])) {
                    cash[0] -= p.buy(n, tree, cash[0]);
                    nodeCents += n.costCents();
                }
            }
            for (Blueprint bp : blueprints.all()) {
                if (!p.hasBlueprint(bp.result()) || crafted.contains(bp.result())) continue;
                long need = 0;
                for (Blueprint.Material m : bp.materials()) {
                    if (catalog.trades(m.item()) && "components".equals(catalog.spec(m.item()).group())) {
                        need += dealer.quoteBuy(m.item(), m.count(), day, p.hasPerk("merchant_license")).cents();
                    }
                }
                if (need > cash[0] || !haveVanilla(bp, stock, catalog)) continue;
                for (Blueprint.Material m : bp.materials()) {
                    if (catalog.trades(m.item()) && "components".equals(catalog.spec(m.item()).group())) {
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
            }

            if (csv != null) {
                csv.printf(Locale.ROOT, "%d,%d,%d,%d,%d,%d%n", day, cash[0], soldToday, p.nodes().size(), crafted.size(),
                        p.completedQuests().size());
            }
            if (p.nodes().size() == tree.tier(1).size() && crafted.size() == blueprints.all().size()) {
                return new Result(scale, day, nodeCents, componentCents, questCents[0], questDay, cash[0], true);
            }
        }
        return new Result(scale, MAX_DAYS, nodeCents, componentCents, questCents[0], questDay, cash[0], false);
    }

    private static ProgressionEvent.Sale sell(Dealer d, String item, int qty, int day, boolean licensed, long[] cash) {
        double before = d.mid(item, day) / d.fairValue(item, day);
        long cents;
        try {
            cents = d.sell(item, qty, day, licensed).cents();
        } catch (RejectedException e) {
            return null;
        }
        cash[0] += cents;
        double after = d.mid(item, day) / d.fairValue(item, day);
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

    private static boolean haveVanilla(Blueprint bp, Map<String, Double> stock, DealerCatalog catalog) {
        for (Blueprint.Material m : bp.materials()) {
            if (m.item().startsWith("realisticmarkets:") || m.item().equals("minecraft:glass_pane")) continue;
            if (stock.getOrDefault(vanillaSource(m.item()), 0.0) < vanillaUnits(m.item(), m.count())) return false;
        }
        return true;
    }

    /** Planks come from oak logs (1 log = 4 planks); everything else is itself. */
    private static String vanillaSource(String material) {
        return material.equals("minecraft:oak_planks") || material.equals("#minecraft:planks") ? "minecraft:oak_log" : material;
    }

    private static double vanillaUnits(String material, int count) {
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
