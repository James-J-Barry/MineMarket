package com.realisticmarkets.mod.client;

import static com.realisticmarkets.mod.client.Panels.BLUE;
import static com.realisticmarkets.mod.client.Panels.GREEN;
import static com.realisticmarkets.mod.client.Panels.GREY;
import static com.realisticmarkets.mod.client.Panels.LIGHT_GREY;
import static com.realisticmarkets.mod.client.Panels.RED;

import com.realisticmarkets.mod.menu.OptionsDeskMenu;
import com.realisticmarkets.mod.options.OptionPapers;
import com.realisticmarkets.money.Money;
import com.realisticmarkets.options.OptionDesk;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public class OptionsDeskScreen extends AbstractContainerScreen<OptionsDeskMenu> {
    private final Button[] underlyings = new Button[OptionPapers.UNDERLYINGS.size()];
    private final Button[] expiries = new Button[2];
    private final Button[] strikes = new Button[OptionsDeskMenu.STRIKES];
    private final Button[] qty = new Button[OptionsDeskMenu.QTY_STEPS.length];
    private final Button[] close = new Button[OptionsDeskMenu.MAX_HOLDINGS];
    private Button buyTab, holdTab, callButton, putButton, buy, collect;

    public OptionsDeskScreen(OptionsDeskMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, OptionsDeskMenu.WIDTH, OptionsDeskMenu.HEIGHT);
        inventoryLabelY = -10_000;
    }

    private Button button(String label, int id, int x, int y, int w) {
        return addRenderableWidget(Button.builder(Component.literal(label), b -> click(id)).bounds(leftPos + x, topPos + y, w, 13).build());
    }

    @Override
    protected void init() {
        super.init();
        buyTab = button("Buy", OptionsDeskMenu.TAB_BUY, 150, 3, 40);
        holdTab = button("Holdings", OptionsDeskMenu.TAB_HOLDINGS, 192, 3, 56);
        for (int i = 0; i < underlyings.length; i++) {
            String u = OptionPapers.UNDERLYINGS.get(i);
            String label = OptionDesk.isGood(u) ? OptionPapers.shortName(u).split(" ")[0] : u;
            underlyings[i] = button(label, OptionsDeskMenu.BUTTON_UNDERLYING_BASE + i, 8 + (i % 6) * 40, 18 + (i / 6) * 14, 39);
            underlyings[i].setTooltip(Tooltip.create(Component.literal("Options on " + OptionPapers.contractName(u) + " a contract")));
        }
        callButton = button("Call", OptionsDeskMenu.BUTTON_CALL, 8, 48, 36);
        putButton = button("Put", OptionsDeskMenu.BUTTON_PUT, 46, 48, 36);
        callButton.setTooltip(Tooltip.create(Component.literal("The right to buy at the strike: pays if the price ends above it")));
        putButton.setTooltip(Tooltip.create(Component.literal("The right to sell at the strike: pays if the price ends below it")));
        for (int e = 0; e < expiries.length; e++) expiries[e] = button("", OptionsDeskMenu.BUTTON_EXPIRY_BASE + e, 136 + e * 56, 48, 54);
        for (int i = 0; i < strikes.length; i++) strikes[i] = button("", OptionsDeskMenu.BUTTON_STRIKE_BASE + i, 8 + i * 48, 64, 46);
        for (int i = 0; i < qty.length; i++) {
            int step = OptionsDeskMenu.QTY_STEPS[i];
            qty[i] = button((step > 0 ? "+" : "") + step, OptionsDeskMenu.BUTTON_QTY_BASE + i, 8 + i * 26 + (i >= 2 ? 60 : 0), 196, 24);
        }
        buy = button("Buy", OptionsDeskMenu.BUTTON_BUY, 170, 196, 78);
        for (int i = 0; i < close.length; i++) close[i] = button("Sell", OptionsDeskMenu.BUTTON_CLOSE_BASE + i, 204, 34 + i * 22, 44);
        collect = button("Collect everything expired", OptionsDeskMenu.BUTTON_COLLECT, 8, 196, 160);
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
        if (buy == null) return;
        OptionsDeskMenu m = getMenu();
        boolean on = m.owner(), buying = on && m.tab() == OptionsDeskMenu.TAB_BUY, holding = on && !buying;
        buyTab.visible = holdTab.visible = on;
        buyTab.active = !buying;
        holdTab.active = !holding;
        for (int i = 0; i < underlyings.length; i++) {
            underlyings[i].visible = buying;
            underlyings[i].active = i != m.underlying();
        }
        callButton.visible = putButton.visible = buying;
        callButton.active = !m.call();
        putButton.active = m.call();
        for (int e = 0; e < expiries.length; e++) {
            expiries[e].visible = buying;
            expiries[e].active = e != m.expiryIndex();
            expiries[e].setMessage(Component.literal("day " + OptionDesk.expiries(m.day())[e]));
        }
        for (int i = 0; i < strikes.length; i++) {
            strikes[i].visible = buying && m.strike(i) > 0;
            strikes[i].active = i != m.strikeIndex();
            strikes[i].setMessage(Component.literal(Money.format(m.strike(i)).replace(".00", "")));
        }
        for (Button b : qty) b.visible = buying;
        buy.visible = buying;
        buy.active = m.cost() > 0;
        buy.setMessage(Component.literal("Buy for " + Money.format(m.cost())));
        for (int i = 0; i < close.length; i++) {
            close[i].visible = holding && i < m.holdingCount() && m.holdingState(i) != 1;
            close[i].setMessage(Component.literal(i < m.holdingCount() && m.holdingState(i) == 2 ? "Collect" : "Sell"));
        }
        collect.visible = holding;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        Panels.panel(g, leftPos, topPos, imageWidth, imageHeight);
    }

    private void right(GuiGraphicsExtractor g, String s, int rightX, int y, int color) {
        g.text(font, s, rightX - font.width(s), y, color, false);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        super.extractLabels(g, mouseX, mouseY);
        OptionsDeskMenu m = getMenu();
        if (!m.owner()) {
            g.text(font, "Unlock the Options Desk at the Almanac", 8, 30, GREY, false);
            g.text(font, "to trade calls and puts here.", 8, 40, GREY, false);
            return;
        }
        if (m.tab() == OptionsDeskMenu.TAB_BUY) buyTab(g, m);
        else holdings(g, m);
    }

    private void buyTab(GuiGraphicsExtractor g, OptionsDeskMenu m) {
        String u = m.underlyingCode();
        String what = OptionPapers.contractName(u);
        g.text(font, "Expiry", 100, 51, LIGHT_GREY, false);
        g.text(font, what + " now " + Money.format(m.spot()) + ", forward " + Money.format(m.forward()), 8, 82, GREY, false);
        g.text(font, "One " + (m.call() ? "call" : "put") + " costs " + Money.format(m.fair() > 0 ? m.cost() / Math.max(1, m.quantity()) : 0)
                + "; the desk buys back at " + Money.format(m.bid()), 8, 94, BLUE, false);
        int size = OptionDesk.contractSize(u);
        String unit = OptionDesk.isGood(u) ? OptionPapers.shortName(u).toLowerCase(Locale.ROOT) : u + " shares";
        g.text(font, String.format(Locale.ROOT, "Delta %.2f: moves like %d %s", m.delta(), Math.round(m.delta() * size), unit), 8, 108, GREY, false);
        g.text(font, "Loses " + Money.format(m.thetaCents()) + " a day if nothing moves; +" + Money.format(m.vegaCents())
                + " per point of vol", 8, 118, GREY, false);
        g.text(font, String.format(Locale.ROOT, "Priced at %.2f%% a day of volatility", m.volBp() / 100.0), 8, 128, LIGHT_GREY, false);
        // Payoff table: what one contract pays if the price ends at 70-130% of the forward.
        g.fill(8, 140, 248, 141, 0xFF8B8B8B);
        g.text(font, "Ends at", 8, 145, LIGHT_GREY, false);
        g.text(font, "Pays", 8, 157, LIGHT_GREY, false);
        g.text(font, "Profit", 8, 169, LIGHT_GREY, false);
        long each = m.cost() / Math.max(1, m.quantity());
        for (int i = 0; i < OptionsDeskMenu.PAYOFF_LEVELS.length; i++) {
            int x = 76 + i * 29;
            long level = Math.round(m.forward() * OptionsDeskMenu.PAYOFF_LEVELS[i]);
            right(g, shortMoney(level), x, 145, LIGHT_GREY);
            right(g, shortMoney(m.payoff(i)), x, 157, m.payoff(i) > 0 ? GREEN : LIGHT_GREY);
            long profit = m.payoff(i) - each;
            right(g, (profit < 0 ? "-" : "+") + shortMoney(Math.abs(profit)), x, 169, profit > 0 ? GREEN : RED);
        }
        g.text(font, "Profit is per contract after its price.", 8, 181, LIGHT_GREY, false);
        String n = m.quantity() + (m.quantity() == 1 ? " contract" : " contracts");
        g.text(font, n, 60 + (60 - font.width(n)) / 2, 199, GREY, false);
    }

    /** "$123" for whole dollars over $100, else "$1.23". */
    private static String shortMoney(long cents) {
        return cents >= 10_000 ? "$" + Math.round(cents / 100.0) : Money.format(cents);
    }

    private void holdings(GuiGraphicsExtractor g, OptionsDeskMenu m) {
        if (m.holdingCount() == 0) {
            g.text(font, "You carry no options.", 8, 30, LIGHT_GREY, false);
            return;
        }
        for (int i = 0; i < m.holdingCount(); i++) {
            int y = 34 + i * 22;
            ItemStack icon = m.holdingIcon(i);
            if (!icon.isEmpty()) g.item(icon, 8, y - 2);
            String name = m.holdingContracts(i) + " x " + (icon.isEmpty() ? "?" : icon.getHoverName().getString());
            g.text(font, Panels.trim(font, name, 140), 28, y, GREY, false);
            String value = switch (m.holdingState(i)) {
                case 2 -> "pays " + Money.format(m.holdingValue(i)) + " each";
                case 1 -> "expired: settles at dawn";
                default -> Money.format(m.holdingValue(i)) + " each";
            };
            g.text(font, value, 28, y + 9, m.holdingState(i) == 2 && m.holdingValue(i) > 0 ? GREEN : LIGHT_GREY, false);
        }
    }
}
