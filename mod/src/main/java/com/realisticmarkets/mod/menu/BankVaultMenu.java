package com.realisticmarkets.mod.menu;

import com.realisticmarkets.collateral.CollateralValuer;
import com.realisticmarkets.contracts.BankAccount;
import com.realisticmarkets.mod.bank.BankService;
import com.realisticmarkets.mod.bank.LoanNoteItem;
import com.realisticmarkets.mod.bank.PassbookItem;
import com.realisticmarkets.mod.block.BankVaultBlockEntity;
import com.realisticmarkets.mod.dealer.DealerService;
import com.realisticmarkets.mod.progression.ProgressionService;
import com.realisticmarkets.mod.registry.ModBlocks;
import com.realisticmarkets.mod.registry.ModItems;
import com.realisticmarkets.mod.registry.ModMenus;
import java.util.Optional;
import java.util.function.LongSupplier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Bank Vault screen. Account tab: a Passbook slot (the balance is shown only while your Passbook is in it),
 * Deposit all, Withdraw $1/$10/$100/all, print a Passbook. CDs tab: choose amount and term, issue; put a CD in the
 * slot to see its value and redeem it.
 */
public class BankVaultMenu extends AbstractContainerMenu {
    public static final int WIDTH = 176, HEIGHT = 196, INVENTORY_Y = 114;
    public static final int SLOT_X = 8, PASSBOOK_Y = 20, CD_SLOT_Y = 80;
    public static final int PASSBOOK_SLOT = 0, CD_SLOT = 1, COLLATERAL_START = 2, COLLATERAL_SLOTS = 4;
    public static final int INV_START = COLLATERAL_START + COLLATERAL_SLOTS, INV_END = INV_START + 36;
    public static final int COLLATERAL_Y = 20;

    public static final int TAB_ACCOUNT = 0, TAB_CDS = 1, TAB_LOANS = 2;
    public static final int BUTTON_TAB_ACCOUNT = 0, BUTTON_TAB_CDS = 1, BUTTON_DEPOSIT_ALL = 2;
    public static final int BUTTON_WITHDRAW_1 = 3, BUTTON_WITHDRAW_10 = 4, BUTTON_WITHDRAW_100 = 5, BUTTON_WITHDRAW_ALL = 6;
    public static final int BUTTON_PASSBOOK = 7;
    public static final int BUTTON_CD_MINUS_1000 = 8, BUTTON_CD_MINUS_100 = 9, BUTTON_CD_PLUS_100 = 10, BUTTON_CD_PLUS_1000 = 11;
    public static final int BUTTON_CD_TERM = 12, BUTTON_CD_ISSUE = 13, BUTTON_CD_REDEEM = 14;
    public static final int BUTTON_TAB_LOANS = 15, BUTTON_LOAN_MINUS_100 = 16, BUTTON_LOAN_MINUS_10 = 17;
    public static final int BUTTON_LOAN_PLUS_10 = 18, BUTTON_LOAN_PLUS_100 = 19, BUTTON_BORROW = 20;
    public static final int BUTTON_REPAY_10 = 21, BUTTON_REPAY_100 = 22, BUTTON_REPAY_ALL = 23, BUTTON_ADD_COLLATERAL = 24;

    private static final int D_TAB = 0, D_HAS_PASSBOOK = 1, D_BALANCE = 2, D_AMOUNT = 4, D_TERM = 6, D_HAS_PERK = 7;
    private static final int D_CD_VALUE = 8, D_PASSBOOKS = 10, D_DAY = 11;
    private static final int D_HAS_LOAN_PERK = 13, D_LOAN_OPEN = 14, D_OWED = 15, D_RATE = 17, D_COVERAGE = 18, D_CALL = 19;
    private static final int D_SLOT_VALUE = 20, D_MAX_LOAN = 22, D_QUALITY = 24, D_REFUSED = 25, D_LOAN_AMOUNT = 26;
    private static final int D_SLOT_RATE = 28, D_REMOTE = 29, D_SIZE = 30;
    private static final long MAX_SYNCED = (1L << 30) - 1;

    private final Container vaultSlots = new SimpleContainer(2 + COLLATERAL_SLOTS);
    private final ContainerData data = new SimpleContainerData(D_SIZE);
    private final ContainerLevelAccess access;
    private final Player player;
    private final BankVaultBlockEntity vault; // server only
    private final BankService bank;
    private final ProgressionService progression;
    private final DealerService dealer;
    private final LongSupplier day;
    private long lastWritten = Long.MIN_VALUE;
    private int ticks;
    private boolean remote; // an ATM or a Pocket ATM: the Account tab only

    public BankVaultMenu(int containerId, Inventory inv) {
        this(containerId, inv, null, ContainerLevelAccess.NULL, null, null, null, () -> 0);
    }

    public BankVaultMenu(int containerId, Inventory inv, BankVaultBlockEntity vault, ContainerLevelAccess access,
                         BankService bank, ProgressionService progression, DealerService dealer, LongSupplier day) {
        super(ModMenus.BANK_VAULT, containerId);
        this.player = inv.player;
        this.vault = vault;
        this.access = access;
        this.bank = bank;
        this.progression = progression;
        this.dealer = dealer;
        this.day = day;
        addSlot(new Slot(vaultSlots, 0, SLOT_X, PASSBOOK_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.is(ModItems.PASSBOOK);
            }

            @Override
            public boolean isActive() {
                return tab() == TAB_ACCOUNT;
            }
        });
        addSlot(new Slot(vaultSlots, 1, SLOT_X, CD_SLOT_Y) {
            @Override
            public boolean mayPlace(ItemStack stack) {
                return stack.is(ModItems.CERTIFICATE_OF_DEPOSIT);
            }

            @Override
            public boolean isActive() {
                return tab() == TAB_CDS;
            }
        });
        for (int i = 0; i < COLLATERAL_SLOTS; i++) {
            addSlot(new Slot(vaultSlots, COLLATERAL_START + i, SLOT_X + i * 18, COLLATERAL_Y) {
                @Override
                public boolean isActive() {
                    return tab() == TAB_LOANS;
                }
            });
        }
        addStandardInventorySlots(inv, 8, INVENTORY_Y);
        addDataSlots(data);
        if (bank != null) {
            setPair(D_AMOUNT, bank.params().cdMinimumCents());
            data.set(D_TERM, bank.params().cdTerms().getFirst().days());
            setPair(D_LOAN_AMOUNT, 10_000);
            refresh();
        }
    }

    /**
     * The account reached from an ATM (or, with {@code access} NULL, a Pocket ATM): the Account tab only; CDs and loans
     * stay at the vault.
     */
    public static BankVaultMenu remote(int containerId, Inventory inv, ContainerLevelAccess access, BankService bank,
                                       ProgressionService progression, DealerService dealer, LongSupplier day) {
        BankVaultMenu m = new BankVaultMenu(containerId, inv, null, access, bank, progression, dealer, day);
        m.remote = true;
        m.data.set(D_REMOTE, 1);
        if (bank != null) m.refresh();
        return m;
    }

    /** True at an ATM or Pocket ATM (the screen hides the CD and loan tabs). */
    public boolean isRemote() { return data.get(D_REMOTE) != 0; }

    // ---- reads (client and server)

    public int tab() { return data.get(D_TAB); }
    public boolean hasPassbook() { return data.get(D_HAS_PASSBOOK) != 0; }
    public long balanceCents() { return pair(D_BALANCE); }
    public long cdAmountCents() { return pair(D_AMOUNT); }
    public int cdTermDays() { return data.get(D_TERM); }
    public boolean hasCdPerk() { return data.get(D_HAS_PERK) != 0; }
    /** Value of the CD in the slot: -1 if empty, -2 if VOID or not genuine. */
    public long cdSlotValueCents() { return pair(D_CD_VALUE) - 2; }
    public int passbooksIssued() { return data.get(D_PASSBOOKS); }
    public long day() { return pair(D_DAY); }
    public boolean hasLoanPerk() { return data.get(D_HAS_LOAN_PERK) != 0; }
    public boolean loanOpen() { return data.get(D_LOAN_OPEN) != 0; }
    public long owedCents() { return pair(D_OWED); }
    /** Daily rate in thousandths of a percent: 753 = 0.753% a day. */
    public int rateMilliPct() { return data.get(D_RATE); }
    /** Coverage in percent (capped at 999). */
    public int coveragePct() { return data.get(D_COVERAGE); }
    public boolean marginCall() { return data.get(D_CALL) != 0; }
    public long slotValueCents() { return pair(D_SLOT_VALUE); }
    public long maxLoanCents() { return pair(D_MAX_LOAN); }
    /** Collateral quality Q x 100. */
    public int qualityPct() { return data.get(D_QUALITY); }
    public boolean slotRefused() { return data.get(D_REFUSED) != 0; }
    public long loanAmountCents() { return pair(D_LOAN_AMOUNT); }
    public int slotRateMilliPct() { return data.get(D_SLOT_RATE); }

    private long pair(int i) {
        return (long) data.get(i) | ((long) data.get(i + 1) << 15);
    }

    private void setPair(int i, long value) {
        long v = Math.min(Math.max(value, 0), MAX_SYNCED);
        data.set(i, (int) (v & 0x7FFF));
        data.set(i + 1, (int) ((v >> 15) & 0x7FFF));
    }

    // ---- server

    @Override
    public void broadcastChanges() {
        if (bank != null && ++ticks % 10 == 0) refresh();
        super.broadcastChanges();
    }

    private void refresh() {
        long today = day.getAsLong();
        bank.accrue(player, today, progression);
        BankAccount a = bank.account(player.getUUID(), today);
        ItemStack passbook = vaultSlots.getItem(PASSBOOK_SLOT);
        boolean has = !passbook.isEmpty();
        data.set(D_HAS_PASSBOOK, has ? 1 : 0);
        setPair(D_BALANCE, has || remote ? a.balanceCents() : 0); // an ATM shows the balance on its screen
        long stamp = a.balanceCents() * 31 + a.log().size() * 7L + today;
        if (has && stamp != lastWritten) {
            PassbookItem.write(passbook, player.getName().getString(), a, today, bank.vaultRate(today));
            lastWritten = stamp;
        }
        data.set(D_HAS_PERK, progression.progress(player).hasPerk(BankService.CD_PERK) ? 1 : 0);
        ItemStack cd = vaultSlots.getItem(CD_SLOT);
        long value = cd.isEmpty() ? -1 : bank.cdValue(cd, today) < 0 ? -2 : bank.cdValue(cd, today);
        setPair(D_CD_VALUE, value + 2);
        data.set(D_PASSBOOKS, a.passbooksIssued());
        setPair(D_DAY, today);
        refreshLoans(today);
        if (vault != null) vault.setLocked(!bank.mayBreakVault(player.getUUID(), player, today));
    }

    private void refreshLoans(long today) {
        bank.collectClosedLoan(player, today);
        data.set(D_HAS_LOAN_PERK, progression.progress(player).hasPerk(BankService.LOAN_PERK) ? 1 : 0);
        var loan = bank.loan(player.getUUID());
        data.set(D_LOAN_OPEN, loan.isPresent() ? 1 : 0);
        if (loan.isPresent() && dealer != null) {
            var v = loan.get().value(dealer.dealer(), today);
            setPair(D_OWED, loan.get().owedCents());
            data.set(D_RATE, (int) Math.round(loan.get().dailyRate() * 100_000));
            data.set(D_COVERAGE, (int) Math.min(999, Math.round(v.coverage(loan.get().owedCents()) * 100)));
            data.set(D_CALL, loan.get().underMarginCall() ? 1 : 0);
            Inventory inv = player.getInventory();
            for (int i = 0; i < inv.getContainerSize(); i++) {
                if (inv.getItem(i).is(ModItems.LOAN_NOTE)) LoanNoteItem.write(inv.getItem(i), loan.get(), v, today);
            }
        }
        if (dealer != null) {
            java.util.Map<String, Integer> items = new java.util.LinkedHashMap<>();
            long cash = 0;
            for (int i = 0; i < COLLATERAL_SLOTS; i++) {
                ItemStack st = vaultSlots.getItem(COLLATERAL_START + i);
                if (st.isEmpty()) continue;
                var d = ModItems.denominationOf(st);
                if (d != null) cash += d.cents() * st.getCount();
                else items.merge(DealerService.itemId(st), st.getCount(), Integer::sum);
            }
            var v = CollateralValuer.value(items, cash, dealer.dealer(), today);
            setPair(D_SLOT_VALUE, v.valueCents());
            setPair(D_MAX_LOAN, v.maxLoanCents());
            data.set(D_QUALITY, (int) Math.round(v.quality() * 100));
            data.set(D_REFUSED, v.refused().isEmpty() ? 0 : 1);
            data.set(D_SLOT_RATE, (int) Math.round(Math.max(0.0001, v.dailyRate() + bank.rateShift(today)) * 100_000));
        }
    }

    @Override
    public boolean clickMenuButton(Player p, int id) {
        if (bank == null) return false;
        long today = day.getAsLong();
        Optional<String> why = Optional.empty();
        boolean handled = true;
        if (remote && id != BUTTON_TAB_ACCOUNT && id != BUTTON_DEPOSIT_ALL && id != BUTTON_PASSBOOK
                && (id < BUTTON_WITHDRAW_1 || id > BUTTON_WITHDRAW_ALL)) {
            if (p instanceof net.minecraft.server.level.ServerPlayer sp) {
                sp.sendOverlayMessage(net.minecraft.network.chat.Component.literal("CDs and loans are at your Bank Vault"));
            }
            return false;
        }
        switch (id) {
            case BUTTON_TAB_ACCOUNT -> data.set(D_TAB, TAB_ACCOUNT);
            case BUTTON_TAB_CDS -> data.set(D_TAB, TAB_CDS);
            case BUTTON_DEPOSIT_ALL -> handled = bank.depositAll(p, today, progression) > 0;
            case BUTTON_WITHDRAW_1 -> why = bank.withdraw(p, 100, today, progression);
            case BUTTON_WITHDRAW_10 -> why = bank.withdraw(p, 1_000, today, progression);
            case BUTTON_WITHDRAW_100 -> why = bank.withdraw(p, 10_000, today, progression);
            case BUTTON_WITHDRAW_ALL -> why = bank.withdraw(p, -1, today, progression);
            case BUTTON_PASSBOOK -> why = bank.printPassbook(p, today);
            case BUTTON_CD_MINUS_1000 -> setPair(D_AMOUNT, Math.max(bank.params().cdMinimumCents(), cdAmountCents() - 100_000));
            case BUTTON_CD_MINUS_100 -> setPair(D_AMOUNT, Math.max(bank.params().cdMinimumCents(), cdAmountCents() - 10_000));
            case BUTTON_CD_PLUS_100 -> setPair(D_AMOUNT, cdAmountCents() + 10_000);
            case BUTTON_CD_PLUS_1000 -> setPair(D_AMOUNT, cdAmountCents() + 100_000);
            case BUTTON_CD_TERM -> {
                var terms = bank.params().cdTerms();
                int i = 0;
                while (i < terms.size() && terms.get(i).days() != cdTermDays()) i++;
                data.set(D_TERM, terms.get((i + 1) % terms.size()).days());
            }
            case BUTTON_CD_ISSUE -> why = bank.issueCd(p, cdAmountCents(), cdTermDays(), today, progression);
            case BUTTON_TAB_LOANS -> data.set(D_TAB, TAB_LOANS);
            case BUTTON_LOAN_MINUS_100 -> setPair(D_LOAN_AMOUNT, Math.max(1_000, loanAmountCents() - 10_000));
            case BUTTON_LOAN_MINUS_10 -> setPair(D_LOAN_AMOUNT, Math.max(1_000, loanAmountCents() - 1_000));
            case BUTTON_LOAN_PLUS_10 -> setPair(D_LOAN_AMOUNT, loanAmountCents() + 1_000);
            case BUTTON_LOAN_PLUS_100 -> setPair(D_LOAN_AMOUNT, loanAmountCents() + 10_000);
            case BUTTON_BORROW -> why = bank.openLoan(p, collateralView(), loanAmountCents(), today, dealer.dealer(), progression);
            case BUTTON_REPAY_10 -> why = bank.repayLoan(p, 1_000, today, progression);
            case BUTTON_REPAY_100 -> why = bank.repayLoan(p, 10_000, today, progression);
            case BUTTON_REPAY_ALL -> why = bank.repayLoan(p, -1, today, progression);
            case BUTTON_ADD_COLLATERAL -> why = bank.addCollateral(p, collateralView(), dealer.dealer());
            case BUTTON_CD_REDEEM -> {
                ItemStack cd = vaultSlots.getItem(CD_SLOT);
                why = cd.isEmpty() ? Optional.of("Put a certificate in the slot") : bank.redeemCd(p, cd, today, progression);
            }
            default -> handled = false;
        }
        if (why.isPresent()) {
            handled = false;
            if (p instanceof ServerPlayer sp) sp.sendOverlayMessage(Component.literal(why.get()));
        }
        lastWritten = Long.MIN_VALUE;
        refresh();
        return handled;
    }

    @Override
    public ItemStack quickMoveStack(Player p, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < INV_START) {
            if (!moveItemStackTo(stack, INV_START, INV_END, true)) return ItemStack.EMPTY;
        } else if (stack.is(ModItems.PASSBOOK) && tab() == TAB_ACCOUNT) {
            if (!moveItemStackTo(stack, PASSBOOK_SLOT, PASSBOOK_SLOT + 1, false)) return ItemStack.EMPTY;
        } else if (stack.is(ModItems.CERTIFICATE_OF_DEPOSIT) && tab() == TAB_CDS) {
            if (!moveItemStackTo(stack, CD_SLOT, CD_SLOT + 1, false)) return ItemStack.EMPTY;
        } else if (tab() == TAB_LOANS) {
            if (!moveItemStackTo(stack, COLLATERAL_START, COLLATERAL_START + COLLATERAL_SLOTS, false)) return ItemStack.EMPTY;
        } else {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.set(ItemStack.EMPTY);
        else slot.setChanged();
        return original;
    }

    @Override
    public void removed(Player p) {
        super.removed(p);
        if (!p.level().isClientSide()) clearContainer(p, vaultSlots);
    }

    @Override
    public boolean stillValid(Player p) {
        if (remote) return stillValid(access, p, ModBlocks.ATM);
        return stillValid(access, p, ModBlocks.BANK_VAULT) && (vault == null || vault.isOwner(p));
    }

    /** The four collateral slots as their own container. */
    private Container collateralView() {
        return new Container() {
            public int getContainerSize() { return COLLATERAL_SLOTS; }
            public boolean isEmpty() {
                for (int i = 0; i < COLLATERAL_SLOTS; i++) if (!getItem(i).isEmpty()) return false;
                return true;
            }
            public ItemStack getItem(int i) { return vaultSlots.getItem(COLLATERAL_START + i); }
            public ItemStack removeItem(int i, int n) { return vaultSlots.removeItem(COLLATERAL_START + i, n); }
            public ItemStack removeItemNoUpdate(int i) { return vaultSlots.removeItemNoUpdate(COLLATERAL_START + i); }
            public void setItem(int i, ItemStack st) { vaultSlots.setItem(COLLATERAL_START + i, st); }
            public void setChanged() { vaultSlots.setChanged(); }
            public boolean stillValid(Player pl) { return true; }
            public void clearContent() {
                for (int i = 0; i < COLLATERAL_SLOTS; i++) vaultSlots.setItem(COLLATERAL_START + i, ItemStack.EMPTY);
            }
        };
    }

    /** Server-side, for tests: the Passbook / CD slot container. */
    public Container vaultSlots() {
        return vaultSlots;
    }
}
