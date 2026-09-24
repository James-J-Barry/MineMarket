package com.realisticmarkets.mod.menu;

import com.realisticmarkets.contracts.BankAccount;
import com.realisticmarkets.mod.bank.BankService;
import com.realisticmarkets.mod.bank.PassbookItem;
import com.realisticmarkets.mod.block.BankVaultBlockEntity;
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
    public static final int PASSBOOK_SLOT = 0, CD_SLOT = 1, INV_START = 2, INV_END = INV_START + 36;

    public static final int TAB_ACCOUNT = 0, TAB_CDS = 1;
    public static final int BUTTON_TAB_ACCOUNT = 0, BUTTON_TAB_CDS = 1, BUTTON_DEPOSIT_ALL = 2;
    public static final int BUTTON_WITHDRAW_1 = 3, BUTTON_WITHDRAW_10 = 4, BUTTON_WITHDRAW_100 = 5, BUTTON_WITHDRAW_ALL = 6;
    public static final int BUTTON_PASSBOOK = 7;
    public static final int BUTTON_CD_MINUS_1000 = 8, BUTTON_CD_MINUS_100 = 9, BUTTON_CD_PLUS_100 = 10, BUTTON_CD_PLUS_1000 = 11;
    public static final int BUTTON_CD_TERM = 12, BUTTON_CD_ISSUE = 13, BUTTON_CD_REDEEM = 14;

    private static final int D_TAB = 0, D_HAS_PASSBOOK = 1, D_BALANCE = 2, D_AMOUNT = 4, D_TERM = 6, D_HAS_PERK = 7;
    private static final int D_CD_VALUE = 8, D_PASSBOOKS = 10, D_DAY = 11, D_SIZE = 13;
    private static final long MAX_SYNCED = (1L << 30) - 1;

    private final Container vaultSlots = new SimpleContainer(2);
    private final ContainerData data = new SimpleContainerData(D_SIZE);
    private final ContainerLevelAccess access;
    private final Player player;
    private final BankVaultBlockEntity vault; // server only
    private final BankService bank;
    private final ProgressionService progression;
    private final LongSupplier day;
    private long lastWritten = Long.MIN_VALUE;
    private int ticks;

    public BankVaultMenu(int containerId, Inventory inv) {
        this(containerId, inv, null, ContainerLevelAccess.NULL, null, null, () -> 0);
    }

    public BankVaultMenu(int containerId, Inventory inv, BankVaultBlockEntity vault, ContainerLevelAccess access,
                         BankService bank, ProgressionService progression, LongSupplier day) {
        super(ModMenus.BANK_VAULT, containerId);
        this.player = inv.player;
        this.vault = vault;
        this.access = access;
        this.bank = bank;
        this.progression = progression;
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
        addStandardInventorySlots(inv, 8, INVENTORY_Y);
        addDataSlots(data);
        if (bank != null) {
            setPair(D_AMOUNT, bank.params().cdMinimumCents());
            data.set(D_TERM, bank.params().cdTerms().getFirst().days());
            refresh();
        }
    }

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
        setPair(D_BALANCE, has ? a.balanceCents() : 0);
        long stamp = a.balanceCents() * 31 + a.log().size() * 7L + today;
        if (has && stamp != lastWritten) {
            PassbookItem.write(passbook, player.getName().getString(), a, today, bank.params());
            lastWritten = stamp;
        }
        data.set(D_HAS_PERK, progression.progress(player).hasPerk(BankService.CD_PERK) ? 1 : 0);
        ItemStack cd = vaultSlots.getItem(CD_SLOT);
        long value = cd.isEmpty() ? -1 : bank.cdValue(cd, today) < 0 ? -2 : bank.cdValue(cd, today);
        setPair(D_CD_VALUE, value + 2);
        data.set(D_PASSBOOKS, a.passbooksIssued());
        setPair(D_DAY, today);
        if (vault != null) vault.setLocked(!a.isEmpty());
    }

    @Override
    public boolean clickMenuButton(Player p, int id) {
        if (bank == null) return false;
        long today = day.getAsLong();
        Optional<String> why = Optional.empty();
        boolean handled = true;
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
        return stillValid(access, p, ModBlocks.BANK_VAULT) && (vault == null || vault.isOwner(p));
    }

    /** Server-side, for tests: the Passbook / CD slot container. */
    public Container vaultSlots() {
        return vaultSlots;
    }
}
