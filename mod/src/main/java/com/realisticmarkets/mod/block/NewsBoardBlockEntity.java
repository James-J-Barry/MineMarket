package com.realisticmarkets.mod.block;

import com.realisticmarkets.dealer.WorldEvents;
import com.realisticmarkets.mod.bank.BankService;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModBlockEntities;
import java.util.Locale;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A News Board: the Newsstand's front page on a wall, for its owner once they own the Newsstand: the central bank's
 * rate and the last two days' market stories, green for goods expected up, red for down.
 */
public class NewsBoardBlockEntity extends DisplayBlockEntity {
    public NewsBoardBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.NEWS_BOARD, pos, state);
    }

    public void refreshNow() {
        if (level != null && level.getServer() != null) refresh(level.getServer());
    }

    @Override
    protected void refresh(MinecraftServer server) {
        DealerService dealer = get(DealerService::get);
        if (dealer == null) return;
        double now = dealer.day(server);
        long today = (long) Math.floor(now);
        Lines l = new Lines();
        ProgressionService prog = get(ProgressionService::get);
        var player = owner() == null ? null : server.getPlayerList().getPlayer(owner());
        boolean reader = prog == null || player == null || prog.progress(player).hasNode("newsstand");
        BankService bank = get(BankService::get);
        if (bank != null) l.add("Central bank rate", String.format(Locale.ROOT, "%.2f%% a day", bank.centralBank().rate(now) * 100), GOLD);
        WorldEvents ev = dealer.events();
        if (!reader) {
            l.add("Unlock the Newsstand to read", "", DIM).add("the market news here", "", DIM);
        } else if (ev != null) {
            var stories = ev.recent(now + 0.5, 2);
            if (stories.isEmpty()) l.add("A quiet day: no market news", "", DIM);
            for (var e : stories) l.add((e.day() == today ? "" : "(yesterday) ") + e.type().headline(), "", e.type().shock() > 0 ? UP : DOWN);
        }
        show("The Overworld Gazette, day " + today, l);
    }

    static <T> T get(Supplier<T> s) {
        try {
            return s.get();
        } catch (IllegalStateException notRunning) {
            return null;
        }
    }
}
