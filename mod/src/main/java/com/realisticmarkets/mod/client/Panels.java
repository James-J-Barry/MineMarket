package com.realisticmarkets.mod.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Vanilla-style panel, slot and well drawing with fills, for screens without a texture. */
final class Panels {
    static final int GREY = 0xFF404040;
    static final int LIGHT_GREY = 0xFF707070;
    static final int GREEN = 0xFF1E6B2E;
    static final int RED = 0xFFA01010;
    static final int BLUE = 0xFF1F3F8F;

    private Panels() {}

    static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x + 1, y, x + w - 1, y + h, 0xFF000000);
        g.fill(x, y + 1, x + w, y + h - 1, 0xFF000000);
        g.fill(x + 1, y + 1, x + w - 1, y + h - 1, 0xFFFFFFFF);
        g.fill(x + 3, y + 3, x + w - 1, y + h - 1, 0xFF555555);
        g.fill(x + 3, y + 3, x + w - 3, y + h - 3, 0xFFC6C6C6);
    }

    static void well(GuiGraphicsExtractor g, int x, int y, int w, int h) {
        g.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0xFF373737);
        g.fill(x, y, x + w + 1, y + h + 1, 0xFFFFFFFF);
        g.fill(x, y, x + w, y + h, 0xFF8B8B8B);
    }

    /** Frame around an 16x16 item drawn at (itemX, itemY). */
    static void slot(GuiGraphicsExtractor g, int itemX, int itemY) {
        int x = itemX - 1, y = itemY - 1;
        g.fill(x, y, x + 18, y + 18, 0xFFFFFFFF);
        g.fill(x, y, x + 17, y + 17, 0xFF373737);
        g.fill(x + 1, y + 1, x + 17, y + 17, 0xFF8B8B8B);
    }

    static void inventory(GuiGraphicsExtractor g, int left, int top, int invY) {
        for (int r = 0; r < 3; r++) for (int c = 0; c < 9; c++) slot(g, left + 8 + c * 18, top + invY + r * 18);
        for (int c = 0; c < 9; c++) slot(g, left + 8 + c * 18, top + invY + 58);
    }

    static String trim(Font font, String s, int maxWidth) {
        if (font.width(s) <= maxWidth) return s;
        while (!s.isEmpty() && font.width(s + "..") > maxWidth) s = s.substring(0, s.length() - 1);
        return s + "..";
    }
}
