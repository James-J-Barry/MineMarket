package com.realisticmarkets.mod.block;

import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.records.RecordsService;
import com.realisticmarkets.mod.registry.ModBlockEntities;
import com.realisticmarkets.money.Money;
import com.realisticmarkets.records.Calendar;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * A Ledger Display: one Records Terminal's headline numbers on a wall (net worth, income today and this week, what's
 * due next). Linked with a Record Link: pick the terminal, then right-click the display.
 */
public class LedgerDisplayBlockEntity extends DisplayBlockEntity {
    private BlockPos terminal; // null: not linked

    public LedgerDisplayBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.LEDGER_DISPLAY, pos, state);
    }

    public BlockPos terminal() { return terminal; }

    public void link(BlockPos pos) {
        terminal = pos == null ? null : pos.immutable();
        setChanged();
        if (level != null && level.getServer() != null) refresh(level.getServer());
    }

    @Override
    protected void refresh(MinecraftServer server) {
        Lines l = new Lines();
        RecordsService records = NewsBoardBlockEntity.get(RecordsService::get);
        DealerService dealer = NewsBoardBlockEntity.get(DealerService::get);
        ProgressionService prog = NewsBoardBlockEntity.get(ProgressionService::get);
        if (records == null || dealer == null) return;
        var player = owner() == null ? null : server.getPlayerList().getPlayer(owner());
        if (player != null && prog != null && !prog.progress(player).hasNode(RecordsService.NODE)) {
            show("Ledger", l.add("Unlock Digital Record Keeping", "", DIM));
            return;
        }
        if (terminal == null || level == null || !(level.getBlockEntity(terminal) instanceof RecordsTerminalBlockEntity t)
                || t.owner() == null || !t.owner().equals(owner())) {
            show("Ledger", l.add("Link me to your Records Terminal", "", DIM).add("with a Record Link", "", DIM));
            return;
        }
        double day = dealer.day(server);
        long today = (long) Math.floor(day);
        var view = records.view(t, owner(), day);
        String account = owner().toString();
        long total = view.netWorth().total();
        l.add("Net worth", (total < 0 ? "-" : "") + Money.format(Math.abs(total)), total < 0 ? DOWN : GOLD);
        long debts = view.netWorth().debts();
        if (debts > 0) l.add("Debts", "-" + Money.format(debts), DOWN);
        long day1 = records.ledger().totalIncome(account, today, 1), week = records.ledger().totalIncome(account, today, 7);
        l.add("Income today", signed(day1), day1 >= 0 ? UP : DOWN);
        l.add("Last 7 days", signed(week), week >= 0 ? UP : DOWN);
        var next = view.calendar().upcoming(today, 1);
        if (!next.isEmpty()) {
            Calendar.Entry e = next.get(0);
            l.add("Next: " + e.kind().label(), "day " + e.day(), WHITE);
        }
        show("Ledger, day " + today, l);
    }

    private static String signed(long cents) {
        return (cents < 0 ? "-" : "+") + Money.format(Math.abs(cents));
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        super.saveAdditional(out);
        if (terminal != null) out.putLong("Terminal", terminal.asLong());
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        super.loadAdditional(in);
        long t = in.getLongOr("Terminal", Long.MIN_VALUE);
        terminal = t == Long.MIN_VALUE ? null : BlockPos.of(t);
    }
}
