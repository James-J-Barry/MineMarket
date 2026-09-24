package com.realisticmarkets.mod.client;

import static com.realisticmarkets.mod.client.Panels.GREEN;
import static com.realisticmarkets.mod.client.Panels.GREY;
import static com.realisticmarkets.mod.client.Panels.LIGHT_GREY;
import static com.realisticmarkets.mod.client.Panels.RED;

import com.realisticmarkets.equities.CompanyCatalog;
import com.realisticmarkets.equities.CompanyNews;
import com.realisticmarkets.mod.menu.NewsfeedMenu;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** The Electronic Newsfeed: a terminal-style list of the last few days' company stories. */
public class NewsfeedScreen extends AbstractContainerScreen<NewsfeedMenu> {
    private static final int SCREEN = 0xFF102018, TEXT = 0xFFB8F0C0, DIM = 0xFF5F8F6A;
    private static final List<CompanyNews.Type> TYPES = CompanyNews.loadTypes();
    private static final CompanyCatalog COMPANIES = CompanyCatalog.loadDefault();

    public NewsfeedScreen(NewsfeedMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, NewsfeedMenu.WIDTH, NewsfeedMenu.HEIGHT);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        Panels.panel(g, leftPos, topPos, imageWidth, imageHeight);
        g.fill(leftPos + 7, topPos + 20, leftPos + imageWidth - 7, topPos + imageHeight - 7, SCREEN);
    }

    private static String when(int age) {
        return age == 0 ? "Today" : age == 1 ? "Yesterday" : age + " days ago";
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        NewsfeedMenu m = getMenu();
        g.text(font, title, 8, 6, GREY, false);
        String dated = "Day " + m.day();
        g.text(font, dated, imageWidth - 10 - font.width(dated), 6, LIGHT_GREY, false);
        int w = imageWidth - 22;
        if (!m.owner()) {
            g.text(font, "Unlock the Electronic Newsfeed at the", 11, 26, TEXT, false);
            g.text(font, "Almanac to read company news.", 11, 36, TEXT, false);
            return;
        }
        if (m.storyCount() == 0) {
            g.text(font, "No company news in the last few days.", 11, 26, DIM, false);
            return;
        }
        int y = 25;
        for (int i = 0; i < m.storyCount(); i++) {
            int ti = m.storyType(i);
            if (ti < 0 || ti >= TYPES.size()) continue;
            CompanyNews.Type t = TYPES.get(ti);
            String age = when(m.storyAge(i));
            g.text(font, Panels.trim(font, t.headline(), w - font.width(age) - 6), 11, y, m.storyAge(i) == 0 ? TEXT : DIM, false);
            g.text(font, age, imageWidth - 11 - font.width(age), y, DIM, false);
            String line = t.ticker() + " (" + COMPANIES.company(t.ticker()).name() + "): "
                    + (t.effect() > 0 ? "good for business" : "bad for business");
            g.text(font, Panels.trim(font, line, w), 11, y + 10, t.effect() > 0 ? GREEN : RED, false);
            y += 23;
        }
    }
}
