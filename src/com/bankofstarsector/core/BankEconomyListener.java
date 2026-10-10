package com.bankofstarsector.core;

import com.bankofstarsector.banking.*;
import com.bankofstarsector.collection.AssetSeizureManager;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.MonthlyReport;
import com.fs.starfarer.api.campaign.econ.MonthlyReport.FDNode;
import com.fs.starfarer.api.campaign.listeners.EconomyTickListener;
import com.fs.starfarer.api.impl.campaign.shared.SharedData;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI.TooltipCreator;
import com.fs.starfarer.api.util.Misc;

import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Month-end banking, in step with the vanilla economy.
 *
 * Starsector's CoreScript.reportEconomyMonthEnd() sums the current MonthlyReport and applies
 * the total to the player's credits (any shortfall becomes report debt). So, on the last
 * economy tick of the month, the bank books its charges as report nodes - exactly like the
 * vanilla commission stipend (Fleet node) and the Diktat fuel accord fee (Colonies node) -
 * and the game itself moves the money at month end.
 */
public class BankEconomyListener implements EconomyTickListener {

    private static final Logger log = Logger.getLogger(BankEconomyListener.class);

    public void reportEconomyTick(int iterIndex) {
        int numIter = Global.getSettings().getInt("economyIterPerMonth");
        if (iterIndex != numIter - 1) return;
        try {
            processMonth();
        } catch (Exception e) {
            log.error("BOS: month-end processing failed", e);
        }
    }

    public void reportEconomyMonthEnd() {
    }

    private void processMonth() {
        BankData data = BankData.get();
        InterestEngine engine = data.getInterestEngine();
        LoanManager lm = data.getLoanManager();

        MonthlyReport report = SharedData.getData().getCurrentReport();
        report.computeTotals();
        float credits = Global.getSector().getPlayerFleet().getCargo().getCredits().get();
        float available = credits + report.getRoot().totalIncome - report.getRoot().totalUpkeep;

        // 1. Interest + this month's installment
        float billed = lm.billMonth(engine);

        // 2a. Default judgment: investments held by the bank are seized first
        float seized = data.getAssetSeizureManager().seizeInvestments(data);

        // 2b. Garnishment on colony income while still in default
        FDNode colonies = report.getNode(MonthlyReport.OUTPOSTS);
        float colonyNet = colonies.totalIncome - colonies.totalUpkeep;
        float garnish = data.getAssetSeizureManager().computeGarnishment(data, colonyNet);
        if (garnish > 0f) {
            getGarnishNode(report).upkeep += garnish;
            available -= garnish;
            data.getAssetSeizureManager().applyToDefaultedLoans(data, garnish, "GARNISH");
        }

        // 3. Autopay: settle past-due installments while the books allow it
        float autopaid = 0f;
        if (data.isAutopayEnabled()) {
            List<BankAccount> open = lm.getActiveLoans();
            // Worst loans first: defaulted, then overdue, then current.
            Collections.sort(open, new Comparator<BankAccount>() {
                public int compare(BankAccount a, BankAccount b) {
                    return b.status.ordinal() - a.status.ordinal();
                }
            });
            for (BankAccount loan : open) {
                if (loan.amountPastDue <= 0f || available <= 0f) continue;
                float pay = Math.min(loan.amountPastDue, available);
                available -= pay;
                autopaid += pay;
                lm.applyPayment(loan, pay, "AUTOPAY");
            }
            if (autopaid > 0f) getLoanNode(report).upkeep += autopaid;
        }

        // 4. Whatever is still unpaid is a missed payment
        lm.markMissedPayments();
        float stillDue = lm.getTotalLate();
        float dueNextMonth = lm.getTotalCurrentBills();

        // 5. Sovereign debt market, investments, credit score, bankruptcy
        data.getAssetSeizureManager().simulateSovereignDebts(data);
        data.getInvestmentManager().advanceMonth(engine);
        boolean allCurrent = stillDue <= 1f;
        // Monthly reporting cycle: the bureau reads balances, delinquencies and closures now.
        data.getCreditScoreManager().advanceMonth(lm);
        data.getBankruptcyManager().advanceMonth(data);

        lm.cleanupPaidLoans();
        data.getInvestmentManager().cleanupEmptyInvestments();

        if (billed > 0f || garnish > 0f || seized > 0f || stillDue > 1f) {
            String msg = com.bankofstarsector.core.Str.f("statement.paid", Misc.getDGSCredits(autopaid));
            if (seized > 0f) msg += com.bankofstarsector.core.Str.f("statement.seized", Misc.getDGSCredits(seized));
            if (garnish > 0f) msg += com.bankofstarsector.core.Str.f("statement.garnished", Misc.getDGSCredits(garnish));
            if (dueNextMonth > 1f) msg += com.bankofstarsector.core.Str.f("statement.dueNext", Misc.getDGSCredits(dueNextMonth));
            if (stillDue > 1f) msg += com.bankofstarsector.core.Str.f("statement.pastDue", Misc.getDGSCredits(stillDue));
            Global.getSector().getCampaignUI().addMessage(msg,
                stillDue > 1f ? Misc.getNegativeHighlightColor() : Misc.getTextColor());
        }
        log.info("BOS: month end - billed " + billed + ", autopaid " + autopaid + ", garnished " + garnish
            + ", past due " + stillDue + ", score " + data.getCreditScoreManager().getScore());
    }

    private static FDNode getLoanNode(MonthlyReport report) {
        FDNode fleetNode = report.getNode(MonthlyReport.FLEET);
        if (fleetNode.name == null) {
            fleetNode.name = "Fleet";
            fleetNode.custom = MonthlyReport.FLEET;
            fleetNode.tooltipCreator = report.getMonthlyReportTooltip();
        }
        FDNode node = report.getNode(fleetNode, "bos_loan_installments");
        if (node.name == null) {
            node.name = com.bankofstarsector.core.Str.get("report.installments");
            node.icon = crest();
            node.tooltipCreator = tooltip(com.bankofstarsector.core.Str.get("report.installments.tooltip"));
        }
        return node;
    }

    private static FDNode getGarnishNode(MonthlyReport report) {
        FDNode colonies = report.getNode(MonthlyReport.OUTPOSTS);
        if (colonies.name == null) {
            colonies.name = "Colonies";
            colonies.custom = MonthlyReport.OUTPOSTS;
            colonies.tooltipCreator = report.getMonthlyReportTooltip();
        }
        FDNode node = report.getNode(colonies, "bos_garnishment");
        if (node.name == null) {
            node.name = com.bankofstarsector.core.Str.get("report.garnishment");
            node.icon = crest();
            node.tooltipCreator = tooltip(com.bankofstarsector.core.Str.get("report.garnishment.tooltip"));
        }
        return node;
    }

    private static String crest() {
        FactionAPI pbc = Global.getSector().getFaction("pbc");
        return pbc != null ? pbc.getCrest() : null;
    }

    private static TooltipCreator tooltip(final String text) {
        return new TooltipCreator() {
            public boolean isTooltipExpandable(Object tooltipParam) { return false; }
            public float getTooltipWidth(Object tooltipParam) { return 450; }
            public void createTooltip(TooltipMakerAPI tooltip, boolean expanded, Object tooltipParam) {
                tooltip.addPara("%s", 0f, com.fs.starfarer.api.util.Misc.getTextColor(), text);
            }
        };
    }
}
