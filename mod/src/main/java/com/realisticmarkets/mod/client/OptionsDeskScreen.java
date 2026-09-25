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
    private final Button[] writeQty = new Button[OptionsDeskMenu.QTY_STEPS.length];
    private final Button[] close = new Button[OptionsDeskMenu.MAX_HOLDINGS];
    private final Button[] topUp = new Button[OptionsDeskMenu.MAX_WRITTEN];
    private Button buyTab, writeTab, holdTab, callButton, putButton, buy, write, collect, collectReturns;

    public OptionsDeskScreen(OptionsDeskMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, OptionsDeskMenu.WIDTH, OptionsDeskMenu.HEIGHT);
        inventoryLabelX = OptionsDeskMenu.INVENTORY_X;
        inventoryLabelY = OptionsDeskMenu.INVENTORY_Y - 10;
    }

    private Button button(String label, int id, int x, int y, int w) {
        return addRenderableWidget(Button.builder(Component.literal(label), b -> click(id)).bounds(leftPos + x, topPos + y, w, 13).build());
    }

    @Override
    protected void init() {
        super.init();
        buyTab = button("Buy", OptionsDeskMenu.TAB_BUY, 110, 3, 34);
        writeTab = button("Write", OptionsDeskMenu.TAB_WRITE, 146, 3, 40);
        holdTab = button("Holdings", OptionsDeskMenu.TAB_HOLDINGS, 188, 3, 60);
        writeTab.setTooltip(Tooltip.create(Component.literal("Sell an option to the desk against collateral: earn the premium, carry the risk")));
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
            String l = (step > 0 ? "+" : "") + step;
            qty[i] = button(l, OptionsDeskMenu.BUTTON_QTY_BASE + i, 8 + i * 26 + (i >= 2 ? 60 : 0), 214, 24);
            writeQty[i] = button(l, OptionsDeskMenu.BUTTON_QTY_BASE + i, 8 + i * 26 + (i >= 2 ? 60 : 0), 136, 24);
        }
        buy = button("Buy", OptionsDeskMenu.BUTTON_BUY, 170, 214, 78);
        write = button("Write", OptionsDeskMenu.BUTTON_WRITE, 170, 136, 78);
        for (int i = 0; i < close.length; i++) close[i] = button("Sell", OptionsDeskMenu.BUTTON_CLOSE_BASE + i, 204, 32 + i * 20, 44);
        for (int i = 0; i < topUp.length; i++) {
            topUp[i] = button("Top up", OptionsDeskMenu.BUTTON_TOPUP_BASE + i, 200, 130 + i * 16, 48);
            topUp[i].setTooltip(Tooltip.create(Component.literal("Add all the cash you carry to this option's collateral")));
        }
        collectReturns = button("Collect collateral", OptionsDeskMenu.BUTTON_COLLECT_RETURNS, 148, 184, 100);
        collect = button("Collect everything expired", OptionsDeskMenu.BUTTON_COLLECT, 8, 214, 160);
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
        boolean on = m.owner();
        boolean buying = on && m.tab() == OptionsDeskMenu.TAB_BUY, writing = on && m.tab() == OptionsDeskMenu.TAB_WRITE;
        boolean holding = on && m.tab() == OptionsDeskMenu.TAB_HOLDINGS, picking = buying || writing;
        buyTab.visible = writeTab.visible = holdTab.visible = on;
        buyTab.active = !buying;
        writeTab.active = !writing;
        holdTab.active = !holding;
        for (int i = 0; i < underlyings.length; i++) {
            underlyings[i].visible = picking;
            underlyings[i].active = i != m.underlying() && (buying || OptionDesk.isGood(OptionPapers.UNDERLYINGS.get(i)));
        }
        callButton.visible = putButton.visible = picking;
        callButton.active = !m.call();
        putButton.active = m.call();
        for (int e = 0; e < expiries.length; e++) {
            expiries[e].visible = picking;
            expiries[e].active = e != m.expiryIndex();
            expiries[e].setMessage(Component.literal("day " + OptionDesk.expiries(m.day())[e]));
        }
        for (int i = 0; i < strikes.length; i++) {
            strikes[i].visible = picking && m.strike(i) > 0;
            strikes[i].active = i != m.strikeIndex();
            strikes[i].setMessage(Component.literal(Money.format(m.strike(i)).replace(".00", "")));
        }
        for (Button b : qty) b.visible = buying;
        for (Button b : writeQty) b.visible = writing;
        buy.visible = buying;
        buy.active = m.cost() > 0;
        buy.setMessage(Component.literal("Buy for " + Money.format(m.cost())));
        write.visible = writing;
        write.active = m.writeEnough() && m.writePremium() > 0;
        write.setMessage(Component.literal(m.writePremium() > 0 ? "Write for " + Money.format(m.writePremium()) : "Write"));
        for (int i = 0; i < close.length; i++) {
            close[i].visible = holding && i < m.holdingCount() && m.holdingState(i) != 1;
            close[i].setMessage(Component.literal(i < m.holdingCount() && m.holdingState(i) == 2 ? "Collect" : "Sell"));
        }
        for (int i = 0; i < topUp.length; i++) topUp[i].visible = holding && i < m.writtenCount() && m.writtenUnderCall(i);
        collectReturns.visible = holding && (m.returnCash() > 0 || m.returnItems() > 0);
        collect.visible = holding;
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        Panels.panel(g, leftPos, topPos, imageWidth, imageHeight);
        if (getMenu().owner() && getMenu().tab() == OptionsDeskMenu.TAB_WRITE) {
            for (int i = 0; i < OptionsDeskMenu.COLLATERAL_SLOTS; i++) {
                Panels.slot(g, leftPos + OptionsDeskMenu.COLLATERAL_X + i * 18, topPos + OptionsDeskMenu.COLLATERAL_Y);
            }
            Panels.inventory(g, leftPos, topPos, OptionsDeskMenu.INVENTORY_X, OptionsDeskMenu.INVENTORY_Y);
        }
    }

    private void right(GuiGraphicsExtractor g, String s, int rightX, int y, int color) {
        g.text(font, s, rightX - font.width(s), y, color, false);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        OptionsDeskMenu m = getMenu();
        g.text(font, title, titleLabelX, titleLabelY, GREY, false);
        if (!m.owner()) {
            g.text(font, "Unlock the Options Desk at the Almanac", 8, 30, GREY, false);
            g.text(font, "to trade calls and puts here.", 8, 40, GREY, false);
            return;
        }
        switch (m.tab()) {
            case OptionsDeskMenu.TAB_WRITE -> {
                g.text(font, playerInventoryTitle, inventoryLabelX, inventoryLabelY, GREY, false);
                writeTab(g, m);
            }
            case OptionsDeskMenu.TAB_HOLDINGS -> holdings(g, m);
            default -> buyTab(g, m);
        }
    }

    private void buyTab(GuiGraphicsExtractor g, OptionsDeskMenu m) {
        String u = m.underlyingCode();
        g.text(font, "Expiry", 100, 51, LIGHT_GREY, false);
        g.text(font, OptionPapers.contractName(u) + " now " + Money.format(m.spot()) + ", forward " + Money.format(m.forward()), 8, 82,
                GREY, false);
        g.text(font, "One " + (m.call() ? "call" : "put") + " costs " + Money.format(m.cost() / Math.max(1, m.quantity()))
                + "; the desk buys back at " + Money.format(m.bid()), 8, 94, BLUE, false);
        greeks(g, m, 108);
        // Payoff table: what one contract pays if the price ends at 70-130% of the forward.
        g.fill(8, 150, 248, 151, 0xFF8B8B8B);
        g.text(font, "Ends at", 8, 155, LIGHT_GREY, false);
        g.text(font, "Pays", 8, 167, LIGHT_GREY, false);
        g.text(font, "Profit", 8, 179, LIGHT_GREY, false);
        long each = m.cost() / Math.max(1, m.quantity());
        for (int i = 0; i < OptionsDeskMenu.PAYOFF_LEVELS.length; i++) {
            int x = 76 + i * 29;
            right(g, shortMoney(Math.round(m.forward() * OptionsDeskMenu.PAYOFF_LEVELS[i])), x, 155, LIGHT_GREY);
            right(g, shortMoney(m.payoff(i)), x, 167, m.payoff(i) > 0 ? GREEN : LIGHT_GREY);
            long profit = m.payoff(i) - each;
            right(g, (profit < 0 ? "-" : "+") + shortMoney(Math.abs(profit)), x, 179, profit > 0 ? GREEN : RED);
        }
        g.text(font, "Profit is per contract, after its price.", 8, 193, LIGHT_GREY, false);
        String n = m.quantity() + (m.quantity() == 1 ? " contract" : " contracts");
        g.text(font, n, 60 + (60 - font.width(n)) / 2, 217, GREY, false);
    }

    private void greeks(GuiGraphicsExtractor g, OptionsDeskMenu m, int y) {
        String u = m.underlyingCode();
        int size = OptionDesk.contractSize(u);
        String unit = OptionDesk.isGood(u) ? OptionPapers.shortName(u).toLowerCase(Locale.ROOT) : u + " shares";
        g.text(font, String.format(Locale.ROOT, "Delta %.2f: moves like %d %s", m.delta(), Math.round(m.delta() * size), unit), 8, y, GREY, false);
        g.text(font, "Loses " + Money.format(m.thetaCents()) + " a day if nothing moves; +" + Money.format(m.vegaCents())
                + " per point of vol", 8, y + 10, GREY, false);
        g.text(font, String.format(Locale.ROOT, "Priced at %.2f%% a day of volatility", m.volBp() / 100.0), 8, y + 20, LIGHT_GREY, false);
    }

    private void writeTab(GuiGraphicsExtractor g, OptionsDeskMenu m) {
        if (!OptionDesk.isGood(m.underlyingCode())) {
            g.text(font, "Options on shares can't be written here.", 8, 106, RED, false);
            return;
        }
        g.text(font, "Collateral", 176, 88, LIGHT_GREY, false);
        if (m.writePremium() == 0 && m.writeRequired() == 0 && m.writeCollateral() == 0) {
            g.text(font, "Put collateral in the slots: the goods themselves", 8, 106, GREY, false);
            g.text(font, "cover a call; cash or other goods back the rest.", 8, 116, GREY, false);
        } else {
            g.text(font, "The desk pays " + Money.format(m.writePremium()) + " for " + m.quantity() + " "
                    + (m.call() ? "call" : "put") + (m.quantity() == 1 ? "" : "s"), 8, 104, BLUE, false);
            g.text(font, String.format(Locale.ROOT, "Covered %d%%, collateral quality %.2f", Math.round(m.writeCovered() * 100),
                    m.writeQuality()), 8, 114, GREY, false);
            boolean ok = m.writeEnough();
            g.text(font, "Collateral " + Money.format(m.writeCollateral()) + " of " + Money.format(m.writeRequired()) + " needed",
                    8, 124, ok ? GREY : RED, false);
        }
        String n = m.quantity() + (m.quantity() == 1 ? " contract" : " contracts");
        g.text(font, n, 60 + (60 - font.width(n)) / 2, 139, GREY, false);
    }

    /** "$123" for whole dollars over $100, else "$1.23". */
    private static String shortMoney(long cents) {
        return cents >= 10_000 ? "$" + Math.round(cents / 100.0) : Money.format(cents);
    }

    private void holdings(GuiGraphicsExtractor g, OptionsDeskMenu m) {
        g.text(font, "Options you hold", 8, 20, LIGHT_GREY, false);
        if (m.holdingCount() == 0) g.text(font, "None.", 8, 34, LIGHT_GREY, false);
        for (int i = 0; i < m.holdingCount(); i++) {
            int y = 34 + i * 20;
            ItemStack icon = m.holdingIcon(i);
            if (!icon.isEmpty()) g.item(icon, 8, y - 3);
            g.text(font, Panels.trim(font, m.holdingContracts(i) + " x " + (icon.isEmpty() ? "?" : icon.getHoverName().getString()), 168),
                    28, y - 2, GREY, false);
            String value = switch (m.holdingState(i)) {
                case 2 -> "pays " + Money.format(m.holdingValue(i)) + " each";
                case 1 -> "expired: settles at dawn";
                default -> Money.format(m.holdingValue(i)) + " each";
            };
            g.text(font, value, 28, y + 7, m.holdingState(i) == 2 && m.holdingValue(i) > 0 ? GREEN : LIGHT_GREY, false);
        }
        g.fill(8, 114, 248, 115, 0xFF8B8B8B);
        g.text(font, "Options you wrote", 8, 118, LIGHT_GREY, false);
        if (m.writtenCount() == 0) g.text(font, "None.", 8, 132, LIGHT_GREY, false);
        for (int i = 0; i < m.writtenCount(); i++) {
            int y = 132 + i * 16;
            ItemStack icon = m.writtenIcon(i);
            String line = m.writtenContracts(i) + " x " + (icon.isEmpty() ? "?" : icon.getHoverName().getString()) + ", premium "
                    + Money.format(m.writtenPremium(i));
            g.text(font, Panels.trim(font, line, m.writtenUnderCall(i) ? 186 : 236), 8, y, m.writtenUnderCall(i) ? RED : GREY, false);
        }
        if (m.returnCash() > 0 || m.returnItems() > 0) {
            g.text(font, "Waiting: " + Money.format(m.returnCash()) + (m.returnItems() > 0 ? " and " + m.returnItems() + " goods" : ""),
                    8, 187, GREEN, false);
        }
    }
}
