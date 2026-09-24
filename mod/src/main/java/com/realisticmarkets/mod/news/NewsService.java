package com.realisticmarkets.mod.news;

import com.realisticmarkets.dealer.WorldEvents;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModItems;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

/**
 * The Newsstand: once a day, a player who owns the Newsstand upgrade collects the morning paper. The news breaks at
 * dawn in the paper and reaches the market at midday ({@link WorldEvents#DELAY_DAYS}), which is the reader's edge.
 * Which day each player last took a paper is kept in memory only: a second copy after a restart says the same thing.
 */
public final class NewsService {
    public static final String NODE = "newsstand";
    private static final NewsService INSTANCE = new NewsService();

    private final Map<UUID, Long> lastPaper = new HashMap<>();

    public static NewsService get() {
        return INSTANCE;
    }

    /** A fresh instance, for GameTests. */
    public static NewsService forTest() {
        return new NewsService();
    }

    /** Gives {@code player} today's paper. Returns empty on success, else why not. */
    public Optional<String> collect(Player player, ProgressionService prog, DealerService dealer, double day) {
        if (!prog.progress(player).hasNode(NODE)) return Optional.of("Unlock the Newsstand at the Almanac to read its paper");
        WorldEvents ev = dealer.events();
        if (ev == null) return Optional.of("No news reaches this world");
        long today = (long) Math.floor(day);
        Long last = lastPaper.get(player.getUUID());
        if (last != null && last == today) return Optional.of("You have today's paper. The next one comes at dawn.");
        lastPaper.put(player.getUUID(), today);
        player.getInventory().placeItemBackInInventory(paper(ev.edition(today), today));
        return Optional.empty();
    }

    /** The Newspaper item for one day's edition. */
    public static ItemStack paper(List<WorldEvents.Story> stories, long day) {
        ItemStack s = new ItemStack(ModItems.NEWSPAPER);
        s.set(DataComponents.CUSTOM_NAME, Component.literal("Overworld Gazette, day " + day).withStyle(st -> st.withItalic(false)));
        List<Component> lines = new java.util.ArrayList<>();
        if (stories.isEmpty()) lines.add(line("A quiet day: no market news.", ChatFormatting.GRAY));
        for (WorldEvents.Story story : stories) {
            lines.add(line(story.headline(), ChatFormatting.GOLD));
            StringBuilder names = new StringBuilder();
            for (String id : story.items()) {
                if (!names.isEmpty()) names.append(", ");
                names.append(new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(id))).getHoverName().getString());
            }
            lines.add(line((story.up() ? "Expect higher: " : "Expect lower: ") + names,
                    story.up() ? ChatFormatting.DARK_GREEN : ChatFormatting.RED));
        }
        lines.add(line("The market hears at midday.", ChatFormatting.DARK_GRAY));
        s.set(DataComponents.LORE, new ItemLore(lines));
        CompoundTag tag = new CompoundTag();
        tag.putLong("day", day);
        tag.putInt("stories", stories.size());
        s.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
        return s;
    }

    private static Component line(String text, ChatFormatting color) {
        return Component.literal(text).withStyle(st -> st.withColor(color).withItalic(false));
    }
}
