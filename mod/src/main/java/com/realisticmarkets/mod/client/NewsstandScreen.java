package com.realisticmarkets.mod.client;

import static com.realisticmarkets.mod.client.Panels.GREEN;
import static com.realisticmarkets.mod.client.Panels.GREY;
import static com.realisticmarkets.mod.client.Panels.LIGHT_GREY;
import static com.realisticmarkets.mod.client.Panels.RED;

import com.realisticmarkets.dealer.DealerCatalog;
import com.realisticmarkets.dealer.WorldEvents;
import com.realisticmarkets.rates.CentralBank;
import com.realisticmarkets.mod.menu.NewsstandMenu;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** The Newsstand's board: a newspaper page of the last few days' market stories. */
public class NewsstandScreen extends AbstractContainerScreen<NewsstandMenu> {
    private static final int PAPER = 0xFFEFE8D6, INK = 0xFF2A2A2A, HEADLINE_TODAY = 0xFF7A1010;
    /** Same catalog the server reads, so type indices line up; the seed doesn't matter for looking up targets. */
    private static final WorldEvents EVENTS = WorldEvents.loadDefault(DealerCatalog.loadDefault(), 0);

    public NewsstandScreen(NewsstandMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, NewsstandMenu.WIDTH, NewsstandMenu.HEIGHT);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        Panels.panel(g, leftPos, topPos, imageWidth, imageHeight);
        g.fill(leftPos + 7, topPos + 20, leftPos + imageWidth - 7, topPos + imageHeight - 7, PAPER);
        g.fill(leftPos + 7, topPos + 32, leftPos + imageWidth - 7, topPos + 33, INK);
    }

    private static String when(int age) {
        return age == 0 ? "Today" : age == 1 ? "Yesterday" : age + " days ago";
    }

    private static String names(WorldEvents.Type t) {
        StringBuilder sb = new StringBuilder();
        for (String id : EVENTS.affects(t)) {
            if (!sb.isEmpty()) sb.append(", ");
            sb.append(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(id))).getHoverName().getString());
        }
        return sb.toString();
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        NewsstandMenu m = getMenu();
        g.text(font, title, 8, 7, GREY, false);
        String masthead = "The Overworld Gazette";
        g.text(font, masthead, (imageWidth - font.width(masthead)) / 2, 23, INK, false);
        String dated = "Day " + m.day();
        g.text(font, dated, imageWidth - 10 - font.width(dated), 7, LIGHT_GREY, false);
        int w = imageWidth - 22;
        if (!m.owner()) {
            g.text(font, "Unlock the Newsstand at the Almanac", 11, 42, INK, false);
            g.text(font, "to read the market news.", 11, 52, INK, false);
            return;
        }
        int y = 38, shown = m.storyCount();
        String rate = String.format(java.util.Locale.ROOT, "%.2f%% a day", m.rateMilliPct() / 1000.0);
        if (m.rateMove() != null) {
            CentralBank.Move move = m.rateMove();
            String age = when(m.rateAge());
            String head = "Central bank " + switch (move) {
                case RAISE -> "raises its rate to ";
                case CUT -> "cuts its rate to ";
                case HOLD -> "holds its rate at ";
            } + rate;
            g.text(font, Panels.trim(font, head, w - font.width(age) - 6), 11, y, m.rateAge() == 0 ? HEADLINE_TODAY : INK, false);
            g.text(font, age, imageWidth - 11 - font.width(age), y, LIGHT_GREY, false);
            String expect = switch (move) {
                case RAISE -> "Savings pay more; bond prices lower";
                case CUT -> "Savings pay less; bond prices higher";
                case HOLD -> "No change for savings or bonds";
            };
            g.text(font, Panels.trim(font, expect, w), 11, y + 10,
                    move == CentralBank.Move.RAISE ? GREEN : move == CentralBank.Move.CUT ? RED : LIGHT_GREY, false);
            y += 22;
            shown = Math.min(shown, NewsstandMenu.MAX_STORIES - 1);
        } else if (m.rateMilliPct() > 0) {
            g.text(font, "Central bank rate: " + rate, 11, y, LIGHT_GREY, false);
            y += 12;
            shown = Math.min(shown, NewsstandMenu.MAX_STORIES - 1);
        }
        if (shown == 0 && m.rateMove() == null) {
            g.text(font, "A quiet few days: no market news.", 11, y + 4, LIGHT_GREY, false);
            return;
        }
        List<WorldEvents.Type> types = EVENTS.types();
        for (int i = 0; i < shown; i++) {
            int ti = m.storyType(i);
            if (ti < 0 || ti >= types.size()) continue;
            WorldEvents.Type t = types.get(ti);
            String age = when(m.storyAge(i));
            g.text(font, Panels.trim(font, t.headline(), w - font.width(age) - 6), 11, y,
                    m.storyAge(i) == 0 ? HEADLINE_TODAY : INK, false);
            g.text(font, age, imageWidth - 11 - font.width(age), y, LIGHT_GREY, false);
            String expect = (t.shock() > 0 ? "Expect higher: " : "Expect lower: ") + names(t);
            g.text(font, Panels.trim(font, expect, w), 11, y + 10, t.shock() > 0 ? GREEN : RED, false);
            y += 22;
        }
    }
}
