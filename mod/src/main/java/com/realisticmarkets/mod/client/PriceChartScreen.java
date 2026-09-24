package com.realisticmarkets.mod.client;

import com.realisticmarkets.exchange.PriceChart;
import com.realisticmarkets.exchange.PriceHistory;
import com.realisticmarkets.mod.item.PriceChartItem;
import com.realisticmarkets.money.Money;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

/** A printed Price Chart, opened from the item: quarter-day closes as a line, each quarter's high-low as a bar. */
public class PriceChartScreen extends Screen {
    private static final int W = 264, H = 170, PLOT_X = 38, PLOT_Y = 26, STEP = 8, PLOT_H = 110;
    private static final int PAPER = 0xFFF3EEDC, INK = 0xFF2A2A2A, GRID = 0xFFD8D0B8, RANGE = 0xFF9A9A9A;
    private static final int UP = 0xFF1E6B2E, DOWN = 0xFFA01010;
    private final PriceChart chart;
    private final String name;

    public PriceChartScreen(ItemStack stack) {
        super(Component.literal("Price Chart"));
        this.chart = PriceChartItem.read(stack).orElseThrow();
        this.name = PriceChartItem.name(stack);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private int y(long price, long lo, long hi) {
        if (hi == lo) return PLOT_Y + PLOT_H / 2;
        return PLOT_Y + PLOT_H - (int) Math.round((price - lo) * (double) PLOT_H / (hi - lo));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(g, mouseX, mouseY, partialTick);
        int x0 = (width - W) / 2, y0 = (height - H) / 2;
        g.fill(x0 - 2, y0 - 2, x0 + W + 2, y0 + H + 2, 0xFF5A4A30);
        g.fill(x0, y0, x0 + W, y0 + H, PAPER);
        String head = name + ", days " + (chart.toDay() - PriceChart.DAYS + 1) + "-" + chart.toDay();
        g.text(font, head, x0 + 8, y0 + 8, INK, false);
        if (chart.empty()) {
            g.text(font, "No trades that week.", x0 + 8, y0 + 40, INK, false);
            return;
        }
        long lo = chart.min(), hi = chart.max();
        long pad = Math.max(1, (hi - lo) / 10);
        lo = Math.max(0, lo - pad);
        hi = hi + pad;
        int px = x0 + PLOT_X, py = y0;
        // Grid: day dividers and three price lines with labels.
        for (int d = 0; d <= PriceChart.DAYS; d++) {
            int gx = px + d * PriceHistory.PERIODS_PER_DAY * STEP;
            g.fill(gx, py + PLOT_Y, gx + 1, py + PLOT_Y + PLOT_H, GRID);
            if (d < PriceChart.DAYS) {
                String label = "" + (chart.toDay() - PriceChart.DAYS + 1 + d);
                g.text(font, label, gx + (PriceHistory.PERIODS_PER_DAY * STEP - font.width(label)) / 2, py + PLOT_Y + PLOT_H + 4, INK, false);
            }
        }
        for (int k = 0; k <= 2; k++) {
            long price = lo + (hi - lo) * k / 2;
            int gy = py + y(price, lo, hi);
            g.fill(px, gy, px + PriceChart.POINTS * STEP, gy + 1, GRID);
            String label = Money.format(price);
            g.text(font, label, px - 3 - font.width(label), gy - 4, INK, false);
        }
        int line = chart.last() >= chart.first() ? UP : DOWN;
        int prevY = -1;
        for (int i = 0; i < PriceChart.POINTS; i++) {
            if (chart.close()[i] == PriceChart.NONE) continue;
            int cx = px + i * STEP;
            int top = py + y(chart.high()[i], lo, hi), bottom = py + y(chart.low()[i], lo, hi);
            g.fill(cx + STEP / 2, top, cx + STEP / 2 + 1, bottom + 1, RANGE);
            int cy = py + y(chart.close()[i], lo, hi);
            if (prevY >= 0) g.fill(cx, Math.min(prevY, cy), cx + 1, Math.max(prevY, cy) + 1, line);
            g.fill(cx, cy, cx + STEP, cy + 1, line);
            prevY = cy;
        }
        String foot = "Last " + Money.format(chart.last()) + "   Week low " + Money.format(chart.min()) + ", high "
                + Money.format(chart.max());
        g.text(font, foot, x0 + 8, y0 + H - 12, INK, false);
    }
}
