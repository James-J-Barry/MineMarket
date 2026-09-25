package com.realisticmarkets.mod.client;

import static com.realisticmarkets.mod.client.Panels.BLUE;
import static com.realisticmarkets.mod.client.Panels.GREEN;
import static com.realisticmarkets.mod.client.Panels.GREY;
import static com.realisticmarkets.mod.client.Panels.LIGHT_GREY;
import static com.realisticmarkets.mod.client.Panels.RED;

import com.realisticmarkets.mod.bonds.BondPapers;
import com.realisticmarkets.mod.menu.RecordsTerminalMenu;
import com.realisticmarkets.mod.registry.ModBlocks;
import com.realisticmarkets.mod.stocks.ShareCertificates;
import com.realisticmarkets.money.Money;
import com.realisticmarkets.records.Calendar;
import com.realisticmarkets.records.Ledger;
import com.realisticmarkets.records.NetWorth;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public class RecordsTerminalScreen extends AbstractContainerScreen<RecordsTerminalMenu> {
    private static final String[] TABS = {"Overview", "Holdings", "Income", "Calendar"};
    private final Button[] tabs = new Button[TABS.length];
    private Button prev, next;

    public RecordsTerminalScreen(RecordsTerminalMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, RecordsTerminalMenu.WIDTH, RecordsTerminalMenu.HEIGHT);
        titleLabelY = -10_000;
        inventoryLabelY = -10_000;
    }

    private Button button(String label, int id, int x, int y, int w) {
        return addRenderableWidget(Button.builder(Component.literal(label), b -> click(id)).bounds(leftPos + x, topPos + y, w, 14).build());
    }

    @Override
    protected void init() {
        super.init();
        for (int i = 0; i < TABS.length; i++) tabs[i] = button(TABS[i], i, 8 + i * 61, 5, 59);
        prev = button("<", RecordsTerminalMenu.BUTTON_PREV, 196, 187, 20);
        next = button(">", RecordsTerminalMenu.BUTTON_NEXT, 228, 187, 20);
        updateWidgets();
    }

    private void click(int id) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode != null) mc.gameMode.handleInventoryButtonClick(getMenu().containerId, id);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        updateWidgets();
    }

    private void updateWidgets() {
        if (prev == null) return;
        RecordsTerminalMenu m = getMenu();
        for (int i = 0; i < tabs.length; i++) tabs[i].active = m.tab() != i;
        boolean paged = m.owner() && m.tab() == RecordsTerminalMenu.TAB_HOLDINGS && m.pages() > 1;
        prev.visible = next.visible = paged;
        prev.active = m.page() > 0;
        next.active = m.page() < m.pages() - 1;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        Panels.panel(g, leftPos, topPos, imageWidth, imageHeight);
        Panels.well(g, leftPos + 7, topPos + 24, imageWidth - 14, imageHeight - 31);
        g.fill(leftPos + 7, topPos + 24, leftPos + imageWidth - 7, topPos + imageHeight - 7, 0xFFE8E4D8); // paper
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        RecordsTerminalMenu m = getMenu();
        if (!m.owner()) {
            g.text(font, "Unlock Digital Record Keeping at the Almanac", 12, 30, GREY, false);
            g.text(font, "to read your records here.", 12, 40, GREY, false);
            return;
        }
        switch (m.tab()) {
            case RecordsTerminalMenu.TAB_HOLDINGS -> holdings(g, m);
            case RecordsTerminalMenu.TAB_INCOME -> income(g, m);
            case RecordsTerminalMenu.TAB_CALENDAR -> calendar(g, m);
            default -> overview(g, m);
        }
    }

    private void right(GuiGraphicsExtractor g, String s, int rightX, int y, int color) {
        g.text(font, s, rightX - font.width(s), y, color, false);
    }

    private static String signed(long cents) {
        return (cents > 0 ? "+" : cents < 0 ? "-" : "") + Money.format(Math.abs(cents));
    }

    // ------------------------------------------------------------------ Overview

    private void overview(GuiGraphicsExtractor g, RecordsTerminalMenu m) {
        g.text(font, "Net worth, day " + m.day(), 12, 29, LIGHT_GREY, false);
        String total = (m.total() < 0 ? "-" : "") + Money.format(Math.abs(m.total()));
        g.text(font, total, 12, 40, m.total() < 0 ? RED : BLUE, false);
        right(g, "Assets " + Money.format(m.assets()), 244, 29, GREY);
        right(g, "Debts " + Money.format(m.debts()), 244, 40, m.debts() > 0 ? RED : LIGHT_GREY);
        if (m.links() == 0) {
            g.text(font, "No blocks linked yet. Right-click this terminal", 12, 58, GREY, false);
            g.text(font, "with a Record Link, then a Bank Vault, Safe", 12, 68, GREY, false);
            g.text(font, "Deposit Box or Trade Route Crate (16 links,", 12, 78, GREY, false);
            g.text(font, "64 blocks). Anything else stays off the books.", 12, 88, GREY, false);
            return;
        }
        int y = 56, col = 0;
        for (NetWorth.Kind k : NetWorth.Kind.values()) {
            long v = m.byKind(k);
            if (v == 0) continue;
            int x = 12 + col * 118;
            g.text(font, k.label(), x, y, GREY, false);
            right(g, (k.debt() ? "-" : "") + Money.format(v), x + 110, y, k.debt() ? RED : GREY);
            if (++col == 2) {
                col = 0;
                y += 10;
            }
        }
        g.text(font, m.links() + (m.links() == 1 ? " linked block" : " linked blocks"), 12, 98, LIGHT_GREY, false);
        chart(g, m, 12, 112, 232, 76);
    }

    /** The last 30 days of net worth, a dot a day joined by bars, scaled to the range shown. */
    private void chart(GuiGraphicsExtractor g, RecordsTerminalMenu m, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + h, 0xFFF6F3EA);
        g.fill(x, y + h - 1, x + w, y + h, 0xFFB0A890);
        int n = RecordsTerminalMenu.LINE_DAYS;
        long lo = Long.MAX_VALUE, hi = Long.MIN_VALUE;
        for (int i = 0; i < n; i++) {
            long v = m.lineDay(i);
            if (v == RecordsTerminalMenu.NO_DATA) continue;
            lo = Math.min(lo, v);
            hi = Math.max(hi, v);
        }
        if (lo == Long.MAX_VALUE) {
            g.text(font, "The 30-day line starts from today.", x + 4, y + h / 2 - 4, LIGHT_GREY, false);
            return;
        }
        if (hi == lo) {
            hi += 100;
            lo -= 100;
        }
        int top = y + 12, bottom = y + h - 4, prevY = -1;
        for (int i = 0; i < n; i++) {
            long v = m.lineDay(i);
            if (v == RecordsTerminalMenu.NO_DATA) continue;
            int px = x + 4 + i * (w - 8) / (n - 1);
            int py = bottom - (int) ((v - lo) * (bottom - top) / (double) (hi - lo));
            if (prevY >= 0) g.fill(px - 1, Math.min(prevY, py), px, Math.max(prevY, py) + 1, 0xFF7090C0);
            g.fill(px - 1, py - 1, px + 1, py + 1, 0xFF1F3F8F);
            prevY = py;
        }
        g.text(font, "30 days", x + 3, y + 2, LIGHT_GREY, false);
        right(g, "high " + Money.format(hi) + "  low " + Money.format(lo), x + w - 3, y + 2, LIGHT_GREY);
    }

    // ------------------------------------------------------------------ Holdings

    private String name(RecordsTerminalMenu m, int i) {
        ItemStack icon = m.rowIcon(i);
        return switch (m.rowKind(i)) {
            case CASH -> icon.is(com.realisticmarkets.mod.registry.ModItems.FORWARD_CONTRACT) ? "Forward deposit" : "Cash";
            case VAULT -> "Vault balance";
            case DEBTS -> icon.is(com.realisticmarkets.mod.registry.ModItems.MARGIN_CALL_NOTICE) ? "Clearing House debt" : "Loan";
            case FUTURES -> "Futures account";
            case SHARES -> ShareCertificates.read(icon).map(p -> p.ticker() + " " + ShareCertificates.COMPANIES.company(p.ticker()).name())
                    .orElse("Shares");
            case BONDS -> BondPapers.read(icon).map(p -> BondPapers.issuerName(p.bond().issuer()) + " bond, day " + p.bond().maturityDay())
                    .orElse("Bonds");
            case GOODS -> icon.isEmpty() ? "Loan collateral" : icon.is(ModBlocks.TRADE_ROUTE_CRATE.asItem()) ? "Shipment in transit"
                    : icon.getHoverName().getString();
            default -> icon.isEmpty() ? m.rowKind(i).label() : icon.getHoverName().getString();
        };
    }

    private void holdings(GuiGraphicsExtractor g, RecordsTerminalMenu m) {
        g.text(font, "Holding", 30, 28, LIGHT_GREY, false);
        g.text(font, "Where", 124, 28, LIGHT_GREY, false);
        right(g, "Value", 206, 28, LIGHT_GREY);
        right(g, "Gain", 246, 28, LIGHT_GREY);
        if (m.rowCount() == 0) {
            g.text(font, m.links() == 0 ? "Link a vault, box or crate to see holdings." : "Nothing of value in linked storage.",
                    12, 44, LIGHT_GREY, false);
            return;
        }
        for (int i = 0; i < m.rowCount(); i++) {
            int y = 38 + i * 16;
            ItemStack icon = m.rowIcon(i);
            if (!icon.isEmpty()) g.item(icon, 11, y);
            long q = m.rowQuantity(i);
            String label = (q > 1 ? q + " x " : "") + name(m, i);
            g.text(font, Panels.trim(font, label, 92), 30, y + 4, m.rowKind(i).debt() ? RED : GREY, false);
            g.text(font, m.rowLocation(i), 124, y + 4, LIGHT_GREY, false);
            right(g, (m.rowKind(i).debt() ? "-" : "") + Money.format(m.rowValue(i)), 206, y + 4, m.rowKind(i).debt() ? RED : GREY);
            long p = m.rowProfit(i);
            if (p != RecordsTerminalMenu.NO_DATA && m.rowKind(i) != NetWorth.Kind.CASH && m.rowKind(i) != NetWorth.Kind.VAULT) {
                right(g, signed(p), 246, y + 4, p >= 0 ? GREEN : RED);
            } else if (m.rowKind(i) == NetWorth.Kind.SHARES || m.rowKind(i) == NetWorth.Kind.BONDS) {
                right(g, "?", 246, y + 4, LIGHT_GREY); // bought elsewhere: no cost on record
            }
        }
        if (m.pages() > 1) g.text(font, "Page " + (m.page() + 1) + " of " + m.pages(), 12, 191, LIGHT_GREY, false);
    }

    // ------------------------------------------------------------------ Income

    private void income(GuiGraphicsExtractor g, RecordsTerminalMenu m) {
        if (!m.ledgerOpen()) {
            g.text(font, "Income is recorded from the day you unlock", 12, 30, GREY, false);
            g.text(font, "Digital Record Keeping.", 12, 40, GREY, false);
            return;
        }
        g.text(font, "Income by source", 12, 30, LIGHT_GREY, false);
        right(g, "7 days", 170, 30, LIGHT_GREY);
        right(g, "30 days", 240, 30, LIGHT_GREY);
        long week = 0, month = 0;
        int y = 44;
        for (Ledger.Source s : Ledger.Source.values()) {
            long w = m.income(s, false), mo = m.income(s, true);
            week += w;
            month += mo;
            g.text(font, s.label(), 12, y, GREY, false);
            right(g, s == Ledger.Source.TRADING_GAINS ? signed(w) : Money.format(w), 170, y, w < 0 ? RED : GREY);
            right(g, s == Ledger.Source.TRADING_GAINS ? signed(mo) : Money.format(mo), 240, y, mo < 0 ? RED : GREY);
            y += 13;
        }
        g.fill(12, y - 2, 244, y - 1, 0xFFB0A890);
        g.text(font, "Total", 12, y + 2, BLUE, false);
        right(g, Money.format(week), 170, y + 2, BLUE);
        right(g, Money.format(month), 240, y + 2, BLUE);
        g.text(font, "Trading gains count sales whose cost is on record.", 12, y + 20, LIGHT_GREY, false);
        g.text(font, "Sales are what you received, not profit.", 12, y + 30, LIGHT_GREY, false);
    }

    // ------------------------------------------------------------------ Calendar

    private void calendar(GuiGraphicsExtractor g, RecordsTerminalMenu m) {
        g.text(font, "Coming up (today is day " + m.day() + ")", 12, 28, LIGHT_GREY, false);
        for (int i = 0; i < m.calendarCount(); i++) {
            int y = 40 + i * 16;
            long d = m.calendarDay(i), in = d - m.day();
            g.text(font, "Day " + d, 12, y + 4, in <= 1 ? BLUE : GREY, false);
            g.text(font, in == 0 ? "today" : in == 1 ? "tomorrow" : "in " + in + " days", 52, y + 4, LIGHT_GREY, false);
            ItemStack icon = m.calendarIcon(i);
            if (!icon.isEmpty()) g.item(icon, 108, y);
            Calendar.Kind k = m.calendarKind(i);
            String what = switch (k) {
                case RATE_DECISION -> "Central bank rate decision";
                case MARGIN_CHECK -> "Margin check at dawn";
                case EARNINGS -> ShareCertificates.read(icon).map(p -> p.ticker() + " reports earnings").orElse("Earnings");
                case COUPON -> "Coupon" + BondPapers.read(icon).map(p -> ", " + BondPapers.issuerName(p.bond().issuer())).orElse("");
                case BOND_MATURITY -> "Matures" + BondPapers.read(icon).map(p -> ", " + BondPapers.issuerName(p.bond().issuer())).orElse("");
                case CD_MATURITY -> "CD matures";
                case FORWARD_DELIVERY -> "Deliver " + (icon.isEmpty() ? "goods" : icon.getHoverName().getString());
                case FUTURES_EXPIRY -> "Futures expire" + (icon.isEmpty() ? "" : ", " + icon.getHoverName().getString());
                case OPTION_EXPIRY -> "Expires: " + (icon.isEmpty() ? "option" : icon.getHoverName().getString());
            };
            g.text(font, Panels.trim(font, what, 90), 128, y + 4, GREY, false);
            if (m.calendarCents(i) > 0) right(g, Money.format(m.calendarCents(i)), 246, y + 4, GREEN);
        }
    }
}
