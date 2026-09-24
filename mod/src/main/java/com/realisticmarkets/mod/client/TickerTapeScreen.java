package com.realisticmarkets.mod.client;

import static com.realisticmarkets.mod.client.Panels.GREY;
import static com.realisticmarkets.mod.client.Panels.LIGHT_GREY;

import com.realisticmarkets.exchange.PriceChart;
import com.realisticmarkets.exchange.PriceHistory;
import com.realisticmarkets.mod.menu.TickerTapeMenu;
import com.realisticmarkets.money.Money;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/**
 * The Ticker Tape's screen: a row of Floor books, and the chosen book's week as quarter-day points (a close line over
 * each quarter's high-low bar), traded volume underneath, and the week's numbers.
 */
public class TickerTapeScreen extends AbstractContainerScreen<TickerTapeMenu> {
    private static final int ROW_X = 14, ROW_Y = 18;
    private static final int PLOT_X = 48, PLOT_Y = 56, PLOT_H = 96, STEP = 8, VOL_Y = 170, VOL_H = 20;
    private static final int PAPER = 0xFFF3EEDC, INK = 0xFF2A2A2A, GRIDLINE = 0xFFDDD5BD, RANGE = 0xFFA8A090;
    private static final int UP = 0xFF1E6B2E, DOWN = 0xFFA01010, VOLUME = 0xFF8C9BB0;

    public TickerTapeScreen(TickerTapeMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, TickerTapeMenu.WIDTH, TickerTapeMenu.HEIGHT);
    }

    private void click(int id) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode != null) mc.gameMode.handleInventoryButtonClick(getMenu().containerId, id);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        double x = event.x() - leftPos - ROW_X, y = event.y() - topPos - ROW_Y;
        if (x >= 0 && y >= 0 && y < 18 && x < TickerTapeMenu.BOOKS.size() * 18) {
            click(TickerTapeMenu.BUTTON_BOOK_BASE + (int) (x / 18));
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    private static ItemStack icon(int book) {
        return new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(TickerTapeMenu.BOOKS.get(book).item())));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        int x = leftPos, y = topPos;
        Panels.panel(g, x, y, imageWidth, imageHeight);
        int n = TickerTapeMenu.BOOKS.size();
        Panels.well(g, x + ROW_X, y + ROW_Y, n * 18, 18);
        int sel = getMenu().book();
        g.fill(x + ROW_X + sel * 18, y + ROW_Y, x + ROW_X + sel * 18 + 18, y + ROW_Y + 18, 0xFFC6D7F0);
        g.fill(x + 7, y + 40, x + imageWidth - 7, y + imageHeight - 7, PAPER);
    }

    /** Pixel row for a price inside the plot (panel-relative). */
    private static int py(long price, long lo, long hi) {
        if (hi == lo) return PLOT_Y + PLOT_H / 2;
        return PLOT_Y + PLOT_H - (int) Math.round((price - lo) * (double) PLOT_H / (hi - lo));
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        g.text(font, title, 8, 6, GREY, false);
        TickerTapeMenu m = getMenu();
        for (int i = 0; i < TickerTapeMenu.BOOKS.size(); i++) g.item(icon(i), ROW_X + i * 18 + 1, ROW_Y + 1);
        PriceChart c = m.chart();
        String name = icon(m.book()).getHoverName().getString();
        g.text(font, name, 12, 44, INK, false);
        if (c.empty()) {
            g.text(font, "No trades on this book in the last week.", 12, PLOT_Y + 20, LIGHT_GREY, false);
            return;
        }
        String change = String.format(Locale.ROOT, "%+.1f%% this week", c.change() * 100);
        String head = "Last " + Money.format(c.last()) + "   " + change;
        g.text(font, head, imageWidth - 12 - font.width(head), 44, c.change() >= 0 ? UP : DOWN, false);

        long lo = c.min(), hi = c.max();
        long pad = Math.max(1, (hi - lo) / 8);
        lo = Math.max(0, lo - pad);
        hi += pad;
        int right = PLOT_X + PriceChart.POINTS * STEP;
        // Price gridlines with labels, and day dividers with day numbers.
        for (int k = 0; k <= 3; k++) {
            long price = lo + (hi - lo) * k / 3;
            int gy = py(price, lo, hi);
            g.fill(PLOT_X, gy, right, gy + 1, GRIDLINE);
            String label = Money.format(price);
            g.text(font, label, PLOT_X - 3 - font.width(label), gy - 4, INK, false);
        }
        int perDay = PriceHistory.PERIODS_PER_DAY * STEP;
        for (int d = 0; d <= PriceChart.DAYS; d++) {
            int gx = PLOT_X + d * perDay;
            g.fill(gx, PLOT_Y, gx + 1, VOL_Y + VOL_H, GRIDLINE);
            if (d < PriceChart.DAYS) {
                long dayNo = c.toDay() - PriceChart.DAYS + 1 + d;
                String label = d == PriceChart.DAYS - 1 ? "Today" : String.valueOf(dayNo);
                g.text(font, label, gx + (perDay - font.width(label)) / 2, PLOT_Y + PLOT_H + 4, INK, false);
            }
        }
        // High-low bars, then the close line on top.
        int line = c.change() >= 0 ? UP : DOWN;
        for (int i = 0; i < PriceChart.POINTS; i++) {
            if (c.close()[i] == PriceChart.NONE || c.volume()[i] == 0) continue;
            int cx = PLOT_X + i * STEP + STEP / 2;
            g.fill(cx, py(c.high()[i], lo, hi), cx + 1, py(c.low()[i], lo, hi) + 1, RANGE);
        }
        int prev = -1;
        for (int i = 0; i < PriceChart.POINTS; i++) {
            if (c.close()[i] == PriceChart.NONE) continue;
            int x = PLOT_X + i * STEP, y = py(c.close()[i], lo, hi);
            if (prev >= 0) g.fill(x, Math.min(prev, y), x + 1, Math.max(prev, y) + 1, line);
            g.fill(x, y, x + STEP, y + 1, line);
            prev = y;
        }
        // Volume bars.
        long maxVol = Math.max(1, c.maxVolume());
        for (int i = 0; i < PriceChart.POINTS; i++) {
            int h = (int) Math.round(c.volume()[i] * (double) VOL_H / maxVol);
            if (h > 0) g.fill(PLOT_X + i * STEP + 1, VOL_Y + VOL_H - h, PLOT_X + i * STEP + STEP - 1, VOL_Y + VOL_H, VOLUME);
        }
        g.text(font, "Traded", PLOT_X - 3 - font.width("Traded"), VOL_Y + 6, LIGHT_GREY, false);
        String foot = "Week low " + Money.format(c.min()) + ", high " + Money.format(c.max()) + ", "
                + c.totalVolume() + " traded";
        g.text(font, Panels.trim(font, foot, imageWidth - 24), 12, VOL_Y + VOL_H + 4, INK, false);
    }
}
