package com.bankofstarsector.intel;

import com.bankofstarsector.banking.BankAccount;
import com.bankofstarsector.core.BankData;
import com.bankofstarsector.core.BankSettings;
import com.bankofstarsector.core.Str;
import com.bankofstarsector.economy.ContractState;
import com.bankofstarsector.economy.DevelopmentContract;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.ButtonAPI;
import com.fs.starfarer.api.ui.IntelUIAPI;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Set;

/**
 * One development contract with the player on either side (0.4.0): an offer to accept or decline, then the
 * contract's progress until it closes. The contract itself lives in DevelopmentManager; this only shows it.
 */
public class DevelopmentIntel extends BaseIntelPlugin {

    public static final String BUTTON_ACCEPT = "dev_accept";
    public static final String BUTTON_DECLINE = "dev_decline";

    private final String contractId;
    /** Why the last Accept failed (UI only). */
    private transient String lastError;

    public DevelopmentIntel(String contractId) {
        this.contractId = contractId;
    }

    public String getContractId() { return contractId; }

    private DevelopmentContract contract() {
        return BankData.get().getDevelopmentManager().find(contractId);
    }

    /** Ends the intel of a contract that closed. */
    public static void endFor(String contractId) {
        if (Global.getSector() == null || Global.getSector().getIntelManager() == null) return;
        for (IntelInfoPlugin p : new ArrayList<IntelInfoPlugin>(Global.getSector().getIntelManager().getIntel(DevelopmentIntel.class))) {
            DevelopmentIntel d = (DevelopmentIntel) p;
            if (contractId.equals(d.contractId) && !d.isEnding() && !d.isEnded()) d.endAfterDelay();
        }
    }

    @Override
    public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
        DevelopmentContract c = contract();
        Color tc = getTitleColor(mode);
        if (c == null) {
            info.addPara(Str.get("dev.intel.titleClosed"), tc, 0f);
            return;
        }
        String key = c.state == ContractState.OFFERED ? "dev.intel.titleOffer" : "dev.intel.title";
        info.addPara("%s", 0f, tc, Str.f(key, c.industryName, c.marketName));
        String party = c.recipientIsPlayer() ? c.investorName() : c.recipientName();
        info.addPara("%s", 3f, Misc.getGrayColor(), Str.f("dev.intel.subtitle", party, c.state.getDisplayName()));
    }

    @Override
    public boolean hasSmallDescription() { return true; }

    @Override
    public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
        DevelopmentContract c = contract();
        Color hl = Misc.getHighlightColor(), neg = Misc.getNegativeHighlightColor();
        float pad = 10f;
        if (c == null) {
            info.addPara(Str.get("dev.intel.gone"), pad);
            return;
        }
        if (c.recipientIsPlayer()) {
            if (c.state == ContractState.OFFERED) {
                info.addPara(Str.get("dev.intel.offer"), pad, hl, c.investorName(), Misc.getDGSCredits(c.principal),
                    c.industryName, c.marketName, Misc.getDGSCredits(c.buildCost));
                addTerms(info, c, pad);
                info.addPara(Str.get("dev.intel.offerExpires"), pad, Misc.getGrayColor(), hl,
                    "" + Math.max(0, BankSettings.DEV_OFFER_DAYS - (int) Global.getSector().getClock().getElapsedDaysSince(c.offeredTs)));
                if (lastError != null) info.addPara("%s", pad, neg, Str.get(lastError));
                ButtonAPI accept = info.addButton(Str.get("dev.intel.accept"), BUTTON_ACCEPT, Misc.getBasePlayerColor(),
                    Misc.getDarkPlayerColor(), (int) Math.min(width, 200f), 20f, pad);
                accept.setShortcut(org.lwjgl.input.Keyboard.KEY_T, true);
                info.addButton(Str.get("dev.intel.decline"), BUTTON_DECLINE, Misc.getBasePlayerColor(),
                    Misc.getDarkPlayerColor(), (int) Math.min(width, 200f), 20f, 3f);
                return;
            }
            BankAccount loan = BankData.get().getLoanManager().findLoan(c.loanAccountId);
            float balance = loan != null && loan.isOpenLoan() ? loan.remainingBalance : 0f;
            switch (c.state) {
                case BUILDING:
                    info.addPara(Str.get("dev.intel.building"), pad, hl, c.industryName, c.marketName,
                        "" + Math.max(0, DevelopmentContract.daysUntil(c.deadlineTs)), Misc.getDGSCredits(balance));
                    break;
                case REPAYING:
                    info.addPara(Str.get("dev.intel.repaying"), pad, hl, Misc.getDGSCredits(balance), c.investorName(),
                        Misc.getDGSCredits(c.repaid));
                    break;
                case DEMAND:
                    info.addPara(Str.get("dev.intel.demand"), pad, neg, Misc.getDGSCredits(balance),
                        "" + Math.max(0, DevelopmentContract.daysUntil(c.demandDeadlineTs)), pct(c.penaltyRate));
                    break;
                case COLLECTION:
                    info.addPara(Str.get("dev.intel.collection"), pad, neg, Misc.getDGSCredits(balance));
                    break;
                default:
                    info.addPara("%s", pad, Misc.getGrayColor(), Str.get(c.outcome != null ? c.outcome : "dev.outcome.repaid"));
            }
            return;
        }
        // The player is the investor.
        info.addPara(Str.get("dev.intel.investor"), pad, hl, c.recipientName(), c.industryName, c.marketName,
            Misc.getDGSCredits(c.principal), pct(c.rate));
        info.addPara(Str.get("dev.intel.investorState"), pad, hl, c.state.getDisplayName(), Misc.getDGSCredits(c.owed),
            Misc.getDGSCredits(c.repaid));
        if (c.state == ContractState.BUILDING) {
            info.addPara(Str.get("dev.intel.investorDeadline"), 3f, hl, "" + Math.max(0, DevelopmentContract.daysUntil(c.deadlineTs)));
        }
        if (c.outcome != null && !c.state.isOpen()) info.addPara("%s", pad, Misc.getGrayColor(), Str.get(c.outcome));
    }

    /** The terms of an offer, also shown in the terminal and the branch office. */
    public static void addTerms(TooltipMakerAPI info, DevelopmentContract c, float pad) {
        Color hl = Misc.getHighlightColor();
        info.addPara(Str.get("dev.terms.build"), pad, hl, c.industryName, c.marketName, "" + (int) c.deadlineDays);
        info.addPara(Str.get("dev.terms.repay"), 3f, hl, pct(c.rate), "" + c.repayMonths);
        info.addPara(Str.get("dev.terms.missed"), 3f, Misc.getNegativeHighlightColor(), pct(c.penaltyRate), "" + BankSettings.DEV_DEMAND_DAYS);
        info.addPara(Str.get("dev.terms.late"), 3f, Misc.getGrayColor());
    }

    static String pct(float monthly) {
        return String.format("%.1f%%", monthly * 100f);
    }

    @Override
    public void buttonPressConfirmed(Object buttonId, IntelUIAPI ui) {
        if (BUTTON_ACCEPT.equals(buttonId)) {
            lastError = BankData.get().getDevelopmentManager().acceptOffer(contractId);
        } else if (BUTTON_DECLINE.equals(buttonId)) {
            BankData.get().getDevelopmentManager().declineOffer(contractId);
        }
        ui.updateUIForItem(this);
    }

    @Override
    public boolean doesButtonHaveConfirmDialog(Object buttonId) {
        return BUTTON_ACCEPT.equals(buttonId);
    }

    @Override
    public void createConfirmationPrompt(Object buttonId, TooltipMakerAPI prompt) {
        DevelopmentContract c = contract();
        if (c == null) return;
        prompt.addPara(Str.get("dev.confirm.title"), Misc.getHighlightColor(), 0f);
        prompt.addPara(Str.get("dev.intel.offer"), 10f, Misc.getHighlightColor(), c.investorName(), Misc.getDGSCredits(c.principal),
            c.industryName, c.marketName, Misc.getDGSCredits(c.buildCost));
        addTerms(prompt, c, 10f);
    }

    @Override
    public String getConfirmText(Object buttonId) {
        return Str.get("confirm.sign");
    }

    @Override
    public String getCancelText(Object buttonId) {
        return Str.get("confirm.cancel");
    }

    @Override
    public Set<String> getIntelTags(SectorMapAPI map) {
        Set<String> tags = super.getIntelTags(map);
        tags.add("Economy");
        return tags;
    }

    @Override
    public String getIcon() {
        DevelopmentContract c = contract();
        FactionAPI f = c == null ? null : Global.getSector().getFaction(c.recipientIsPlayer() ? c.investorId : c.recipientId);
        return f != null ? f.getCrest() : Global.getSettings().getSpriteName("intel", "monthly_income_report");
    }

    @Override
    public FactionAPI getFactionForUIColors() {
        DevelopmentContract c = contract();
        FactionAPI f = c == null ? null : Global.getSector().getFaction(c.recipientIsPlayer() ? c.investorId : c.recipientId);
        return f != null ? f : super.getFactionForUIColors();
    }

    @Override
    public boolean isImportant() {
        DevelopmentContract c = contract();
        return c != null && (c.state == ContractState.OFFERED || c.state == ContractState.DEMAND || c.state == ContractState.COLLECTION);
    }
}
