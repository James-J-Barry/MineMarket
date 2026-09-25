package com.realisticmarkets.mod.client;

import static com.realisticmarkets.mod.client.Panels.BLUE;
import static com.realisticmarkets.mod.client.Panels.GREY;
import static com.realisticmarkets.mod.client.Panels.LIGHT_GREY;

import com.realisticmarkets.mod.menu.VolatilityBoardMenu;
import com.realisticmarkets.mod.options.OptionPapers;
import com.realisticmarkets.money.Money;
import com.realisticmarkets.options.OptionDesk;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class VolatilityBoardScreen extends AbstractContainerScreen<VolatilityBoardMenu> {
    private static final int CHART_X = 12, CHART_Y = 52, CHART_W = 232, CHART_H = 100;
    private final Button[] underlyings = new Button[OptionPapers.UNDERLYINGS.size()];

    public VolatilityBoardScreen(VolatilityBoardMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, VolatilityBoardMenu.WIDTH, VolatilityBoardMenu.HEIGHT);
        inventoryLabelY = -10_000;
    }

    @Override
    protected void init() {
        super.init();
        for (int i = 0; i < underlyings.length; i++) {
            String u = OptionPapers.UNDERLYINGS.get(i);
            int id = VolatilityBoardMenu.BUTTON_UNDERLYING_BASE + i;
            underlyings[i] = addRenderableWidget(Button.builder(Component.literal(OptionDesk.isGood(u) ? OptionPapers.shortName(u).split(" ")[0] : u),
                    b -> click(id)).bounds(leftPos + 8 + (i % 6) * 40, topPos + 18 + (i / 6) * 14, 39, 13).build());
        }
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
        for (int i = 0; i < underlyings.length; i++) {
            underlyings[i].visible = getMenu().owner();
            underlyings[i].active = i != getMenu().underlying();
        }
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        Panels.panel(g, leftPos, topPos, imageWidth, imageHeight);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        super.extractLabels(g, mouseX, mouseY);
        VolatilityBoardMenu m = getMenu();
        if (!m.owner()) {
            g.text(font, "Unlock the Volatility Board at the Almanac.", 8, 30, GREY, false);
            return;
        }
        int n = VolatilityBoardMenu.LEVELS.length, max = m.realizedBp();
        for (int i = 0; i < n; i++) max = Math.max(max, m.smileBp(i));
        int top = max * 5 / 4 + 1;
        g.fill(CHART_X, CHART_Y, CHART_X + CHART_W, CHART_Y + CHART_H, 0xFF20242A);
        int base = CHART_Y + CHART_H - 12;
        int realizedY = base - (int) ((long) m.realizedBp() * (CHART_H - 20) / top);
        g.fill(CHART_X + 2, realizedY, CHART_X + CHART_W - 2, realizedY + 1, 0xFFB0B0B0); // realized: the floor of the smile
        int bw = (CHART_W - 8) / n;
        for (int i = 0; i < n; i++) {
            int h = (int) ((long) m.smileBp(i) * (CHART_H - 20) / top);
            int x = CHART_X + 4 + i * bw;
            g.fill(x + 1, base - h, x + bw - 1, base, VolatilityBoardMenu.LEVELS[i] == 1.0 ? 0xFF7FD17F : 0xFF6FA8DC);
            String l = Math.round(VolatilityBoardMenu.LEVELS[i] * 100) + "";
            g.text(font, l, x + (bw - font.width(l)) / 2, base + 2, 0xFFB0B0B0, false);
        }
        String u = OptionPapers.UNDERLYINGS.get(m.underlying());
        g.text(font, OptionPapers.shortName(u) + ": implied volatility by strike (% of the forward)", CHART_X + 3, CHART_Y + 3, 0xFFE0E0E0, false);
        g.text(font, String.format(Locale.ROOT, "Realized over 20 days: %.2f%% a day (the grey line)", m.realizedBp() / 100.0), 8, 158,
                GREY, false);
        g.text(font, String.format(Locale.ROOT, "At the money %.2f%%, at 70%% or 130%% %.2f%%", m.smileBp(n / 2) / 100.0, m.smileBp(0) / 100.0),
                8, 168, GREY, false);
        long[] exp = OptionDesk.expiries(m.day());
        g.text(font, "At-the-money call: " + Money.format(m.atm(0)) + " to day " + exp[0] + ", " + Money.format(m.atm(1)) + " to day " + exp[1],
                8, 180, BLUE, false);
    }
}
