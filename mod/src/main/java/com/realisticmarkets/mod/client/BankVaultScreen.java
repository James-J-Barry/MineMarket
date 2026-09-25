package com.realisticmarkets.mod.client;

import static com.realisticmarkets.mod.client.Panels.GREEN;
import static com.realisticmarkets.mod.client.Panels.GREY;
import static com.realisticmarkets.mod.client.Panels.LIGHT_GREY;
import static com.realisticmarkets.mod.client.Panels.RED;

import com.realisticmarkets.mod.menu.BankVaultMenu;
import com.realisticmarkets.money.Money;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

public class BankVaultScreen extends AbstractContainerScreen<BankVaultMenu> {
    private Button accountTab, cdTab, loanTab, passbook;
    private final List<Button> accountButtons = new ArrayList<>();
    private final List<Button> cdButtons = new ArrayList<>();
    private final List<Button> borrowButtons = new ArrayList<>();
    private final List<Button> repayButtons = new ArrayList<>();
    private Button term;

    public BankVaultScreen(BankVaultMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title, BankVaultMenu.WIDTH, BankVaultMenu.HEIGHT);
        inventoryLabelY = BankVaultMenu.INVENTORY_Y - 11;
    }

    private Button button(String label, int id, int x, int y, int w) {
        return addRenderableWidget(Button.builder(Component.literal(label), b -> click(id))
                .bounds(leftPos + x, topPos + y, w, 14).build());
    }

    @Override
    protected void init() {
        super.init();
        accountButtons.clear();
        cdButtons.clear();
        borrowButtons.clear();
        repayButtons.clear();
        accountTab = button("Account", BankVaultMenu.BUTTON_TAB_ACCOUNT, 62, 3, 42);
        cdTab = button("CDs", BankVaultMenu.BUTTON_TAB_CDS, 106, 3, 28);
        loanTab = button("Loans", BankVaultMenu.BUTTON_TAB_LOANS, 136, 3, 34);
        borrowButtons.add(button("-100", BankVaultMenu.BUTTON_LOAN_MINUS_100, 8, 64, 28));
        borrowButtons.add(button("-10", BankVaultMenu.BUTTON_LOAN_MINUS_10, 38, 64, 26));
        borrowButtons.add(button("+10", BankVaultMenu.BUTTON_LOAN_PLUS_10, 66, 64, 26));
        borrowButtons.add(button("+100", BankVaultMenu.BUTTON_LOAN_PLUS_100, 94, 64, 28));
        borrowButtons.add(button("Borrow", BankVaultMenu.BUTTON_BORROW, 124, 64, 44));
        repayButtons.add(button("Repay $10", BankVaultMenu.BUTTON_REPAY_10, 8, 64, 52));
        repayButtons.add(button("$100", BankVaultMenu.BUTTON_REPAY_100, 62, 64, 40));
        repayButtons.add(button("All", BankVaultMenu.BUTTON_REPAY_ALL, 104, 64, 40));
        repayButtons.add(button("Add slots as collateral", BankVaultMenu.BUTTON_ADD_COLLATERAL, 8, 82, 136));
        accountButtons.add(button("Deposit all cash", BankVaultMenu.BUTTON_DEPOSIT_ALL, 8, 42, 100));
        accountButtons.add(button("-$1", BankVaultMenu.BUTTON_WITHDRAW_1, 8, 62, 36));
        accountButtons.add(button("-$10", BankVaultMenu.BUTTON_WITHDRAW_10, 46, 62, 36));
        accountButtons.add(button("-$100", BankVaultMenu.BUTTON_WITHDRAW_100, 84, 62, 40));
        accountButtons.add(button("All", BankVaultMenu.BUTTON_WITHDRAW_ALL, 126, 62, 42));
        passbook = button("Passbook", BankVaultMenu.BUTTON_PASSBOOK, 8, 84, 160);
        accountButtons.add(passbook);
        cdButtons.add(button("-1k", BankVaultMenu.BUTTON_CD_MINUS_1000, 8, 32, 30));
        cdButtons.add(button("-100", BankVaultMenu.BUTTON_CD_MINUS_100, 40, 32, 30));
        cdButtons.add(button("+100", BankVaultMenu.BUTTON_CD_PLUS_100, 72, 32, 30));
        cdButtons.add(button("+1k", BankVaultMenu.BUTTON_CD_PLUS_1000, 104, 32, 30));
        term = button("7 days", BankVaultMenu.BUTTON_CD_TERM, 8, 50, 56);
        cdButtons.add(term);
        cdButtons.add(button("Issue CD", BankVaultMenu.BUTTON_CD_ISSUE, 66, 50, 60));
        cdButtons.add(button("Redeem", BankVaultMenu.BUTTON_CD_REDEEM, 28, 81, 48));
        updateWidgets();
    }

    private void click(int id) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.gameMode != null) mc.gameMode.handleInventoryButtonClick(getMenu().containerId, id);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        updateWidgets();
    }

    private void updateWidgets() {
        if (accountTab == null) return;
        BankVaultMenu m = getMenu();
        boolean account = m.tab() == BankVaultMenu.TAB_ACCOUNT;
        boolean cds = m.tab() == BankVaultMenu.TAB_CDS;
        boolean loans = m.tab() == BankVaultMenu.TAB_LOANS;
        accountTab.active = !account;
        cdTab.active = !cds;
        loanTab.active = !loans;
        cdTab.visible = loanTab.visible = !m.isRemote();
        for (Button b : accountButtons) b.visible = account;
        for (Button b : cdButtons) b.visible = cds && m.hasCdPerk();
        for (Button b : borrowButtons) b.visible = loans && m.hasLoanPerk() && !m.loanOpen();
        for (Button b : repayButtons) b.visible = loans && m.hasLoanPerk() && m.loanOpen();
        passbook.setMessage(Component.literal(m.passbooksIssued() == 0 ? "Get your Passbook (free)"
                : "New Passbook (1 Ledger Paper)"));
        term.setMessage(Component.literal(m.cdTermDays() + " days"));
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(g, mouseX, mouseY, partialTick);
        Panels.panel(g, leftPos, topPos, imageWidth, imageHeight);
        BankVaultMenu m = getMenu();
        if (m.tab() == BankVaultMenu.TAB_ACCOUNT) {
            Panels.slot(g, leftPos + BankVaultMenu.SLOT_X, topPos + BankVaultMenu.PASSBOOK_Y);
        } else if (m.tab() == BankVaultMenu.TAB_CDS) {
            if (m.hasCdPerk()) Panels.slot(g, leftPos + BankVaultMenu.SLOT_X, topPos + BankVaultMenu.CD_SLOT_Y);
        } else if (m.hasLoanPerk()) {
            for (int i = 0; i < BankVaultMenu.COLLATERAL_SLOTS; i++) {
                Panels.slot(g, leftPos + BankVaultMenu.SLOT_X + i * 18, topPos + BankVaultMenu.COLLATERAL_Y);
            }
        }
        Panels.inventory(g, leftPos, topPos, BankVaultMenu.INVENTORY_Y);
    }

    private void drawLoans(GuiGraphicsExtractor g, BankVaultMenu m) {
        if (!m.hasLoanPerk()) {
            g.text(font, "Unlock the Loan Note", 8, 22, LIGHT_GREY, false);
            g.text(font, "at the Almanac.", 8, 32, LIGHT_GREY, false);
            return;
        }
        int x = 84;
        String worth = "Worth " + Money.format(m.slotValueCents());
        g.text(font, worth, x, 20, GREY, false);
        if (m.loanOpen()) {
            g.text(font, "Owe " + Money.format(m.owedCents()), x, 30, GREY, false);
            g.text(font, String.format(java.util.Locale.ROOT, "%.2f%%/day", m.rateMilliPct() / 1000.0), 8, 42, GREY, false);
            int cov = m.coveragePct();
            g.text(font, m.marginCall() ? "MARGIN CALL: " + cov + "%" : "Coverage " + (cov >= 999 ? "999+" : cov) + "%",
                    70, 42, m.marginCall() || cov < 110 ? RED : GREEN, false);
            g.text(font, "Needs 110%. Repay from balance.", 8, 52, LIGHT_GREY, false);
            return;
        }
        if (m.slotRefused()) {
            g.text(font, "Won't take something here", 8, 42, RED, false);
        } else {
            g.text(font, "Max " + Money.format(m.maxLoanCents()), x, 30, GREEN, false);
            g.text(font, "Quality " + m.qualityPct() + "%  "
                    + String.format(java.util.Locale.ROOT, "%.2f%%/day", m.slotRateMilliPct() / 1000.0), 8, 42, GREY, false);
        }
        g.text(font, "Borrow " + Money.format(m.loanAmountCents()) + " (1 Security Paper)", 8, 53,
                m.loanAmountCents() <= m.maxLoanCents() ? GREY : RED, false);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor g, int mouseX, int mouseY) {
        super.extractLabels(g, mouseX, mouseY);
        BankVaultMenu m = getMenu();
        if (m.tab() == BankVaultMenu.TAB_ACCOUNT) {
            if (m.hasPassbook() || m.isRemote()) {
                g.text(font, "Balance " + Money.format(m.balanceCents()), 30, 25, GREEN, false);
            } else {
                g.text(font, "Put your Passbook here", 30, 21, LIGHT_GREY, false);
                g.text(font, "to see your balance", 30, 31, LIGHT_GREY, false);
            }
            g.text(font, "Withdraw", 110, 45, LIGHT_GREY, false);
            return;
        }
        if (m.tab() == BankVaultMenu.TAB_LOANS) {
            drawLoans(g, m);
            return;
        }
        if (!m.hasCdPerk()) {
            g.text(font, "Unlock Certificates of Deposit", 8, 22, LIGHT_GREY, false);
            g.text(font, "at the Almanac.", 8, 32, LIGHT_GREY, false);
            return;
        }
        g.text(font, "New CD: " + Money.format(m.cdAmountCents()), 8, 21, GREY, false);
        g.text(font, "Costs 1 Security Paper", 8, 68, LIGHT_GREY, false);
        long v = m.cdSlotValueCents();
        String line = v == -1 ? "Redeem a CD here" : v == -2 ? "VOID: already redeemed" : "Pays " + Money.format(v) + " now";
        g.text(font, line, 80, 85, v == -2 ? RED : v >= 0 ? GREEN : LIGHT_GREY, false);
    }
}
