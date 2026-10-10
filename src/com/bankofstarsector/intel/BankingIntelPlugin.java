package com.bankofstarsector.intel;

import com.bankofstarsector.banking.*;
import com.bankofstarsector.collection.BankruptcyManager;
import com.bankofstarsector.core.BankData;
import com.bankofstarsector.core.BankSettings;
import com.bankofstarsector.core.Str;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.*;
import com.fs.starfarer.api.util.Misc;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class BankingIntelPlugin extends BaseIntelPlugin {

    private static final Color GOLD = new Color(212, 175, 55);
    private static final Color DARK_NAVY = new Color(20, 30, 60);

    private String currentTab = TAB_OVERVIEW;
    private String pendingLoanType = null;   // kept for save compatibility
    private String pendingInvestType = null; // kept for save compatibility

    public static final String TAB_OVERVIEW = "tab_overview";
    public static final String TAB_LOANS = "tab_loans";
    public static final String TAB_INVESTMENTS = "tab_investments";
    public static final String TAB_CREDIT = "tab_credit";
    public static final String TAB_HISTORY = "tab_history";

    private static String pct(float fraction, String fmt) {
        return String.format(fmt, fraction * 100);
    }

    @Override
    public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
        Color c = getTitleColor(mode);
        info.addPara(Str.get("terminal.title"), c, 0f);

        BankData data = BankData.get();
        CreditScoreManager csm = data.getCreditScoreManager();
        float pad = 3f;

        info.addPara(Str.get("terminal.list.score"), pad, Misc.getGrayColor(),
            Misc.getHighlightColor(), csm.getScoreText(), csm.getBracket());

        float debt = data.getLoanManager().getTotalDebt();
        float invested = data.getInvestmentManager().getTotalValue();
        if (debt > 0) {
            info.addPara(Str.get("terminal.list.debt"), pad, Misc.getGrayColor(),
                Misc.getNegativeHighlightColor(), Misc.getDGSCredits(debt));
        }
        if (invested > 0) {
            info.addPara(Str.get("terminal.list.investments"), pad, Misc.getGrayColor(),
                Misc.getPositiveHighlightColor(), Misc.getDGSCredits(invested));
        }

        BankruptcyManager.BankruptcyState bState = data.getBankruptcyManager().getState();
        if (bState != BankruptcyManager.BankruptcyState.NONE) {
            info.addPara("%s", pad, Misc.getNegativeHighlightColor(), Str.f("terminal.list.bankruptcy", BankruptcyManager.stateName(bState)));
        }
    }

    @Override
    public boolean hasLargeDescription() { return true; }

    @Override
    public void createLargeDescription(CustomPanelAPI panel, float width, float height) {
        float opad = 10f;
        TooltipMakerAPI outer = panel.createUIElement(width, height, true);

        addTabBar(outer, width, opad);
        outer.addSpacer(opad);

        switch (currentTab) {
            case TAB_OVERVIEW:     renderOverview(outer, width, opad); break;
            case TAB_LOANS:        renderLoans(outer, width, opad); break;
            case TAB_INVESTMENTS:  renderInvestments(outer, width, opad); break;
            case TAB_CREDIT:       renderCreditScore(outer, width, opad); break;
            case TAB_HISTORY:      renderHistory(outer, width, opad); break;
        }

        panel.addUIElement(outer);
    }

    private void addTabBar(TooltipMakerAPI info, float width, float opad) {
        info.addSectionHeading(Str.get("terminal.title"), GOLD, DARK_NAVY, Alignment.MID, opad);

        String[] tabIds = {TAB_OVERVIEW, TAB_LOANS, TAB_INVESTMENTS, TAB_CREDIT, TAB_HISTORY};
        for (String tabId : tabIds) {
            Color btnColor = tabId.equals(currentTab) ? GOLD : Misc.getBasePlayerColor();
            info.addButton(Str.get("terminal." + tabId), tabId, btnColor, DARK_NAVY,
                Alignment.MID, CutStyle.NONE, width / tabIds.length - 4, 24f, 3f);
        }
    }

    private void heading(TooltipMakerAPI info, String key, float opad) {
        info.addSectionHeading(Str.get(key), Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(), Alignment.MID, opad);
    }

    // ========== TAB RENDERING ==========

    private void renderOverview(TooltipMakerAPI info, float width, float opad) {
        BankData data = BankData.get();
        CreditScoreManager csm = data.getCreditScoreManager();
        LoanManager lm = data.getLoanManager();
        InvestmentManager im = data.getInvestmentManager();

        float credits = Global.getSector().getPlayerFleet().getCargo().getCredits().get();
        float netWorth = data.getNetWorth();

        heading(info, "terminal.overview.summary", opad);
        info.addPara(Str.get("terminal.overview.netWorth"), opad, Misc.getHighlightColor(), Misc.getDGSCredits(netWorth));
        info.addPara(Str.get("terminal.overview.credits"), opad, Misc.getHighlightColor(), Misc.getDGSCredits(credits));
        info.addPara(Str.get("terminal.overview.totalDebt"), opad,
            lm.getTotalDebt() > 0 ? Misc.getNegativeHighlightColor() : Misc.getGrayColor(),
            Misc.getDGSCredits(lm.getTotalDebt()));
        info.addPara(Str.get("terminal.overview.totalInvestments"), opad, Misc.getPositiveHighlightColor(),
            Misc.getDGSCredits(im.getTotalValue()));
        if (lm.getTotalHeldFunds() > 0f) {
            info.addPara(Str.get("terminal.overview.held"), opad, Misc.getPositiveHighlightColor(),
                Misc.getDGSCredits(lm.getTotalHeldFunds()));
        }
        BankAccount line = lm.getCreditLine();
        if (line != null) {
            info.addPara(Str.get("terminal.overview.line"), opad, utilizationColor(line.getUtilization()),
                Misc.getDGSCredits(line.remainingBalance), Misc.getDGSCredits(line.creditLimit),
                String.format("%.0f%%", line.getUtilization() * 100));
        }

        info.addSpacer(opad);
        heading(info, "terminal.credit.heading", opad);
        Color scoreColor = scoreColor(csm.getScore());
        info.addPara(Str.get("terminal.overview.score"), opad, scoreColor, csm.getScoreText(), csm.getBracket());
        info.addPara(Str.get("terminal.overview.maxLoans"), opad, Misc.getHighlightColor(), "" + csm.getMaxLoans());

        info.addSpacer(opad);
        heading(info, "terminal.overview.cashFlow", opad);
        float monthlyPayments = lm.getTotalMonthlyPayment();
        float monthlyReturns = im.getMonthlyProjectedReturn();
        float netCashFlow = monthlyReturns - monthlyPayments;

        info.addPara(Str.get("terminal.overview.loanPayments"), opad, Misc.getNegativeHighlightColor(),
            Misc.getDGSCredits(monthlyPayments));
        float late = lm.getTotalLate();
        float dueNow = lm.getTotalCurrentBills();
        if (dueNow > 1f) {
            info.addPara(Str.get("terminal.overview.dueNow"), opad, Misc.getHighlightColor(), Misc.getDGSCredits(dueNow));
        }
        if (late > 1f) {
            info.addPara(Str.get("terminal.overview.pastDue"), opad, Misc.getNegativeHighlightColor(), Misc.getDGSCredits(late));
        }
        info.addPara(Str.get("terminal.overview.autopay"), opad,
            data.isAutopayEnabled() ? Misc.getPositiveHighlightColor() : Misc.getNegativeHighlightColor(),
            Str.get(data.isAutopayEnabled() ? "common.on" : "common.off"));
        info.addButton(Str.get(data.isAutopayEnabled() ? "terminal.overview.autopayOff" : "terminal.overview.autopayOn"),
            "toggle_autopay", Misc.getBasePlayerColor(), DARK_NAVY, Alignment.MID, CutStyle.NONE, 200, 22f, 3f);
        info.addPara(Str.get("terminal.overview.returns"), opad, Misc.getPositiveHighlightColor(),
            Misc.getDGSCredits(monthlyReturns));
        info.addPara(Str.get("terminal.overview.netCashFlow"), opad,
            netCashFlow >= 0 ? Misc.getPositiveHighlightColor() : Misc.getNegativeHighlightColor(),
            (netCashFlow < 0 ? "-" : "") + Misc.getDGSCredits(Math.abs(netCashFlow)));

        // Sector conditions
        InterestEngine engine = data.getInterestEngine();
        float warSurcharge = engine.getWarSurcharge();
        float disruption = engine.getMarketDisruptionModifier();

        info.addSpacer(opad);
        heading(info, "terminal.sector.heading", opad);
        info.addPara(Str.get("terminal.sector.wars"), opad,
            warSurcharge > 0 ? Misc.getNegativeHighlightColor() : Misc.getHighlightColor(),
            "" + engine.getWarCount(), pct(warSurcharge, "%.0f"));
        String d = pct(disruption, "%.1f");
        info.addPara(Str.get("terminal.sector.disruption"), opad,
            disruption > 0 ? Misc.getNegativeHighlightColor() : Misc.getHighlightColor(), d, d, d, d);

        // Sovereign debt ledger (NPC factions borrowing from the Confederation)
        final java.util.Map<String, Float> debts = data.getAssetSeizureManager().getAllFactionDebts();
        if (!debts.isEmpty()) {
            info.addSpacer(opad);
            heading(info, "terminal.sovereign.heading", opad);
            java.util.List<String> ids = new java.util.ArrayList<String>(debts.keySet());
            java.util.Collections.sort(ids, new java.util.Comparator<String>() {
                public int compare(String a, String b) { return Float.compare(debts.get(b), debts.get(a)); }
            });
            for (int i = 0; i < ids.size() && i < 6; i++) {
                com.fs.starfarer.api.campaign.FactionAPI f = Global.getSector().getFaction(ids.get(i));
                info.addPara("%s: %s", 3f, f != null ? f.getBaseUIColor() : Misc.getHighlightColor(),
                    f != null ? f.getDisplayName() : ids.get(i), Misc.getDGSCredits(debts.get(ids.get(i))));
            }
            float total = data.getAssetSeizureManager().getTotalSovereignDebt();
            info.addPara(Str.get("terminal.sovereign.bonus"), opad, Misc.getHighlightColor(),
                pct(engine.getSovereignYieldBonus(total), "%.2f"));
        }

        // Bankruptcy status
        BankruptcyManager.BankruptcyState bState = data.getBankruptcyManager().getState();
        if (bState != BankruptcyManager.BankruptcyState.NONE) {
            info.addSpacer(opad);
            info.addSectionHeading(Str.get("terminal.bankruptcy.status"), Misc.getNegativeHighlightColor(),
                Misc.getDarkPlayerColor(), Alignment.MID, opad);
            BankruptcyManager bm = data.getBankruptcyManager();
            info.addPara(Str.get("terminal.bankruptcy.state"), opad, Misc.getNegativeHighlightColor(),
                BankruptcyManager.stateName(bState));
            info.addPara(Str.get("terminal.bankruptcy.loansIn"), opad, Misc.getHighlightColor(), "" + bm.getNoLoansDaysRemaining());
            info.addPara(Str.get("terminal.bankruptcy.investIn"), opad, Misc.getHighlightColor(), "" + bm.getNoInvestDaysRemaining());
            info.addPara(Str.get("terminal.bankruptcy.recoveryIn"), opad, Misc.getHighlightColor(), "" + bm.getRecoveryDaysRemaining());
        }
    }

    private static Color scoreColor(int score) {
        return score >= 750 ? Misc.getPositiveHighlightColor() :
               score >= 650 ? Misc.getHighlightColor() :
               score >= 500 ? GOLD : Misc.getNegativeHighlightColor();
    }

    private void renderLoans(TooltipMakerAPI info, float width, float opad) {
        BankData data = BankData.get();
        LoanManager lm = data.getLoanManager();
        CreditScoreManager csm = data.getCreditScoreManager();
        InterestEngine engine = data.getInterestEngine();

        renderCreditLine(info, opad);
        info.addSpacer(opad);

        heading(info, "terminal.loans.active", opad);

        List<BankAccount> activeLoans = lm.getActiveLoans();
        for (java.util.Iterator<BankAccount> it = activeLoans.iterator(); it.hasNext(); ) {
            if (it.next().loanType.isRevolving()) it.remove();
        }
        if (activeLoans.isEmpty()) {
            info.addPara(Str.get("terminal.loans.none"), Misc.getGrayColor(), opad);
        } else {
            for (BankAccount loan : activeLoans) {
                Color statusColor = loan.status == LoanStatus.ACTIVE ?
                    Misc.getPositiveHighlightColor() : Misc.getNegativeHighlightColor();

                info.addPara(Str.get("terminal.loans.row"), opad, statusColor,
                    loan.loanType.getDisplayName(),
                    Misc.getDGSCredits(loan.remainingBalance),
                    pct(loan.monthlyRate, "%.1f"),
                    loan.getStatusDisplay());

                info.addPara(Str.get("terminal.loans.next"), 3f, Misc.getHighlightColor(),
                    Misc.getDGSCredits(loan.getMonthlyPayment()), "" + loan.monthsElapsed, "" + loan.termMonths);
                if (loan.heldFunds > 0f) {
                    info.addPara(Str.get("terminal.loans.held"), 3f, Misc.getPositiveHighlightColor(),
                        Misc.getDGSCredits(loan.heldFunds));
                }

                if (loan.amountPastDue > 1f) {
                    info.addPara(Str.get("terminal.loans.due"), 3f,
                        loan.getLateAmount() > 1f ? Misc.getNegativeHighlightColor() : Misc.getHighlightColor(),
                        Misc.getDGSCredits(loan.amountPastDue), Misc.getDGSCredits(loan.getLateAmount()),
                        "" + loan.missedPayments);
                    info.addButton(Str.f("terminal.loans.payDue", Misc.getDGSCredits(loan.amountPastDue)),
                        "loan_paypastdue_" + loan.accountId,
                        Misc.getNegativeHighlightColor(), DARK_NAVY, Alignment.MID, CutStyle.NONE, 200, 24f, 3f);
                } else {
                    info.addButton(Str.f("terminal.loans.prepay", Misc.getDGSCredits(loan.getMonthlyPayment())),
                        "loan_paymin_" + loan.accountId,
                        Misc.getBasePlayerColor(), DARK_NAVY, Alignment.MID, CutStyle.NONE, 200, 24f, 3f);
                }
                info.addButton(Str.f("terminal.loans.payOff", Misc.getDGSCredits(loan.remainingBalance)),
                    "loan_payoff_" + loan.accountId,
                    Misc.getBasePlayerColor(), DARK_NAVY, Alignment.MID, CutStyle.NONE, 200, 24f, 3f);
                info.addSpacer(opad / 2);
            }
        }

        info.addSpacer(opad);
        heading(info, "terminal.loans.available", opad);

        boolean bankruptcyLock = !data.getBankruptcyManager().canTakeLoans();
        if (data.getCollectionManager().isBankingRestricted()) {
            info.addPara(Str.get("terminal.loans.restrictedOverdue"), Misc.getNegativeHighlightColor(), opad);
            return;
        }
        if (bankruptcyLock) {
            info.addPara(Str.get("terminal.loans.restrictedBankruptcy"), Misc.getNegativeHighlightColor(), opad);
        }

        // The credit-builder loan comes first, and is the only offer during the bankruptcy lockout.
        List<LoanType> offers = new ArrayList<LoanType>();
        offers.add(LoanType.BUILDER);
        if (!bankruptcyLock) {
            for (LoanType type : LoanType.values()) if (!type.isBuilder() && !type.isRevolving()) offers.add(type);
        }
        for (LoanType type : offers) {
            String why = lm.whyNot(type, data);
            // Players with a good score don't need it; don't clutter their list.
            if (type.isBuilder() && "terminal.loans.reasonBuilderScore".equals(why)) continue;
            boolean canTake = why == null;
            float maxAmount = type.getMaxAmountForScore(csm.getScore());
            float effectiveRate = engine.calculateEffectiveLoanRate(type, csm.getScore());

            Color typeColor = canTake ? Misc.getHighlightColor() : Misc.getGrayColor();
            info.addPara("%s", opad, typeColor, type.getDisplayName());
            if (type.isBuilder()) {
                info.addPara(Str.get("terminal.loans.builderTerms"), 3f, Misc.getGrayColor(),
                    Misc.getDGSCredits(BankSettings.BUILDER_MIN_AMOUNT), Misc.getDGSCredits(maxAmount),
                    pct(effectiveRate, "%.1f"), "" + type.getTermMonths(), "" + BankSettings.BUILDER_MAX_SCORE);
            } else {
                info.addPara(Str.get("terminal.loans.terms"), 3f, Misc.getGrayColor(),
                    Misc.getDGSCredits(maxAmount), pct(effectiveRate, "%.1f"),
                    "" + type.getTermMonths(), "" + type.minCreditScore);
            }
            info.addPara("  %s", 3f, Misc.getGrayColor(), type.getDescription());

            if (canTake) {
                float[] pcts = {0.25f, 0.50f, 0.75f, 1.0f};
                for (float p : pcts) {
                    float amount = loanAmount(type, maxAmount, p);
                    info.addButton(Str.f("terminal.loans.take", Misc.getDGSCredits(amount)),
                        "loan_take_" + type.name() + "_" + (int) (p * 100),
                        Misc.getBasePlayerColor(), DARK_NAVY, Alignment.MID, CutStyle.NONE, 160, 22f, 2f);
                }
            } else {
                info.addPara("  [%s]", 3f, Misc.getNegativeHighlightColor(), Str.get(why));
            }
            info.addSpacer(opad / 2);
        }
    }

    private static Color utilizationColor(float u) {
        return u <= 0.3f ? Misc.getPositiveHighlightColor() : u <= 0.75f ? Misc.getHighlightColor() : Misc.getNegativeHighlightColor();
    }

    /** Credit line: the offer, or the open line with its statement, draws and payments. */
    private void renderCreditLine(TooltipMakerAPI info, float opad) {
        BankData data = BankData.get();
        LoanManager lm = data.getLoanManager();
        CreditScoreManager csm = data.getCreditScoreManager();
        heading(info, "terminal.line.heading", opad);

        BankAccount line = lm.getCreditLine();
        if (line == null) {
            info.addPara("%s", opad, Misc.getGrayColor(), LoanType.CREDIT_LINE.getDescription());
            String why = lm.whyNot(LoanType.CREDIT_LINE, data);
            if (why != null) {
                info.addPara("  [%s]", 3f, Misc.getNegativeHighlightColor(), Str.f(why, BankSettings.LINE_MIN_SCORE));
                return;
            }
            float limit = LoanManager.lineLimitFor(csm);
            float rate = data.getInterestEngine().calculateEffectiveLoanRate(LoanType.CREDIT_LINE, csm.getScore());
            info.addPara(Str.get("terminal.line.offer"), 3f, Misc.getHighlightColor(), Misc.getDGSCredits(limit),
                pct(rate, "%.1f"), pct(BankSettings.LINE_MIN_PAYMENT_PCT, "%.0f"), Misc.getDGSCredits(BankSettings.LINE_MIN_PAYMENT_FLOOR));
            info.addButton(Str.f("terminal.line.open", Misc.getDGSCredits(limit)), "line_open",
                Misc.getBasePlayerColor(), DARK_NAVY, Alignment.MID, CutStyle.NONE, 240, 24f, opad / 2);
            return;
        }

        float u = line.getUtilization();
        info.addPara(Str.get("terminal.line.limitRow"), opad, utilizationColor(u),
            Misc.getDGSCredits(line.remainingBalance), Misc.getDGSCredits(line.creditLimit),
            Misc.getDGSCredits(line.getAvailableCredit()), String.format("%.0f%%", u * 100));
        info.addPara(Str.get("terminal.line.statementRow"), 3f, Misc.getHighlightColor(),
            Misc.getDGSCredits(line.statementBalance), Misc.getDGSCredits(line.amountPastDue),
            pct(line.monthlyRate, "%.1f"), line.getStatusDisplay());
        if (line.getStatementRemaining() > 1f) {
            info.addPara(Str.get("terminal.line.graceRisk"), 3f, Misc.getHighlightColor(),
                Misc.getDGSCredits(line.getStatementRemaining()));
        } else if (line.remainingBalance > 1f) {
            info.addPara("%s", 3f, Misc.getPositiveHighlightColor(), Str.get("terminal.line.graceOk"));
        }
        if (line.lastInterest > 1f) {
            info.addPara(Str.get("terminal.line.interest"), 3f, Misc.getNegativeHighlightColor(),
                Misc.getDGSCredits(line.lastInterest));
        }
        if (line.getLateAmount() > 1f) {
            info.addPara(Str.get("terminal.loans.due"), 3f, Misc.getNegativeHighlightColor(),
                Misc.getDGSCredits(line.amountPastDue), Misc.getDGSCredits(line.getLateAmount()), "" + line.missedPayments);
        }
        if (line.creditLimit <= 0f) {
            info.addPara("%s", 3f, Misc.getNegativeHighlightColor(), Str.get("terminal.line.frozen"));
        }
        info.addPara(Str.get("terminal.line.autopay"), 3f, Misc.getHighlightColor(),
            Str.get(line.autopayFull ? "terminal.line.autopayFull" : "terminal.line.autopayMin"));

        boolean canDraw = line.status == LoanStatus.ACTIVE && line.getAvailableCredit() >= 1f
            && !data.getCollectionManager().isBankingRestricted();
        if (canDraw) {
            for (float p : new float[]{0.10f, 0.25f, 0.50f, 1.0f}) {
                info.addButton(Str.f("terminal.line.draw", Misc.getDGSCredits(line.getAvailableCredit() * p)),
                    "line_draw_" + line.accountId + "_" + (int) (p * 100),
                    Misc.getBasePlayerColor(), DARK_NAVY, Alignment.MID, CutStyle.NONE, 160, 22f, 2f);
            }
        }
        if (line.amountPastDue > 1f) {
            info.addButton(Str.f("terminal.line.payMin", Misc.getDGSCredits(line.amountPastDue)),
                "loan_paypastdue_" + line.accountId,
                line.getLateAmount() > 1f ? Misc.getNegativeHighlightColor() : Misc.getBasePlayerColor(),
                DARK_NAVY, Alignment.MID, CutStyle.NONE, 240, 24f, 3f);
        }
        if (line.getStatementRemaining() > 1f) {
            info.addButton(Str.f("terminal.line.payStatement", Misc.getDGSCredits(line.getStatementRemaining())),
                "line_paystatement_" + line.accountId,
                Misc.getBasePlayerColor(), DARK_NAVY, Alignment.MID, CutStyle.NONE, 240, 24f, 3f);
        }
        if (line.remainingBalance > 1f) {
            info.addButton(Str.f("terminal.line.payAll", Misc.getDGSCredits(line.remainingBalance)),
                "loan_payoff_" + line.accountId,
                Misc.getBasePlayerColor(), DARK_NAVY, Alignment.MID, CutStyle.NONE, 240, 24f, 3f);
        }
        info.addButton(Str.get(line.autopayFull ? "terminal.line.autopayToMin" : "terminal.line.autopayToFull"),
            "line_autopay_" + line.accountId,
            Misc.getBasePlayerColor(), DARK_NAVY, Alignment.MID, CutStyle.NONE, 240, 24f, 3f);
        if (line.remainingBalance <= 1f && line.status == LoanStatus.ACTIVE) {
            info.addButton(Str.get("terminal.line.close"), "line_close_" + line.accountId,
                Misc.getNegativeHighlightColor(), DARK_NAVY, Alignment.MID, CutStyle.NONE, 240, 24f, 3f);
        }
    }

    /** Amount offered on a "take" button: a share of the maximum, never below the credit-builder minimum. */
    private static float loanAmount(LoanType type, float maxAmount, float share) {
        float amount = maxAmount * share;
        return type.isBuilder() ? Math.max(BankSettings.BUILDER_MIN_AMOUNT, amount) : amount;
    }

    private void renderInvestments(TooltipMakerAPI info, float width, float opad) {
        BankData data = BankData.get();
        InvestmentManager im = data.getInvestmentManager();

        heading(info, "terminal.invest.portfolio", opad);

        List<BankAccount> activeInvestments = im.getActiveInvestments();
        if (activeInvestments.isEmpty()) {
            info.addPara(Str.get("terminal.invest.none"), Misc.getGrayColor(), opad);
        } else {
            for (BankAccount inv : activeInvestments) {
                float returnPct = inv.investedAmount > 0 ?
                    ((inv.currentValue - inv.investedAmount) / inv.investedAmount) * 100 : 0;

                info.addPara(Str.get("terminal.invest.row"), opad, Misc.getHighlightColor(),
                    inv.investmentType.getDisplayName(),
                    Misc.getDGSCredits(inv.currentValue),
                    String.format("%+.1f", returnPct),
                    inv.getStatusDisplay());

                if (!inv.isLocked()) {
                    info.addButton(Str.f("terminal.invest.withdraw", Misc.getDGSCredits(inv.currentValue)),
                        "invest_withdraw_" + inv.accountId,
                        Misc.getBasePlayerColor(), DARK_NAVY, Alignment.MID, CutStyle.NONE, 200, 24f, 3f);
                } else {
                    info.addButton(Str.get("terminal.invest.withdrawEarly"),
                        "invest_withdraw_" + inv.accountId,
                        Misc.getNegativeHighlightColor(), DARK_NAVY, Alignment.MID, CutStyle.NONE, 200, 24f, 3f);
                }
                info.addSpacer(opad / 2);
            }
        }

        info.addSpacer(opad);
        heading(info, "terminal.invest.available", opad);

        if (!data.getBankruptcyManager().canInvest()) {
            info.addPara(Str.get("terminal.invest.restricted"), Misc.getNegativeHighlightColor(), opad);
        } else {
            float playerCredits = Global.getSector().getPlayerFleet().getCargo().getCredits().get();

            for (InvestmentType type : InvestmentType.values()) {
                boolean canAfford = playerCredits >= type.minInvestment;
                Color typeColor = canAfford ? Misc.getHighlightColor() : Misc.getGrayColor();

                info.addPara("%s", opad, typeColor, type.getDisplayName());
                info.addPara(Str.get("terminal.invest.terms"), 3f, Misc.getGrayColor(),
                    pct(type.baseMonthlyReturn, "%.1f"),
                    pct(type.volatility, "%.1f"),
                    type.lockMonths > 0 ? Str.f("common.months", type.lockMonths) : Str.get("common.none"),
                    Misc.getDGSCredits(type.minInvestment));
                info.addPara("  %s", 3f, Misc.getGrayColor(), type.getDescription());

                if (canAfford) {
                    float[] amounts = {type.minInvestment, type.minInvestment * 2,
                                       type.minInvestment * 5, type.minInvestment * 10};
                    for (float amt : amounts) {
                        if (amt <= playerCredits) {
                            info.addButton(Str.f("terminal.invest.invest", Misc.getDGSCredits(amt)),
                                "invest_buy_" + type.name() + "_" + (int) amt,
                                Misc.getBasePlayerColor(), DARK_NAVY, Alignment.MID, CutStyle.NONE, 160, 22f, 2f);
                        }
                    }
                } else {
                    info.addPara("  [%s]", 3f, Misc.getNegativeHighlightColor(), Str.get("terminal.invest.noCredits"));
                }
                info.addSpacer(opad / 2);
            }
        }
    }

    private void renderCreditScore(TooltipMakerAPI info, float width, float opad) {
        BankData data = BankData.get();
        CreditScoreManager csm = data.getCreditScoreManager();
        CreditBureau.Result r = csm.getReport();

        heading(info, "terminal.credit.heading", opad);
        Color scoreColor = csm.hasScore() ? scoreColor(csm.getScore()) : Misc.getGrayColor();
        info.addPara(Str.get("terminal.credit.current"), opad, scoreColor, csm.getScoreText());
        info.addPara(Str.get("terminal.credit.bracket"), opad, scoreColor, csm.getBracket());
        info.addPara(Str.get("terminal.credit.modifier"), opad, Misc.getHighlightColor(),
            String.format("%+.0f%%", csm.getRateModifier() * 100));
        if (!csm.hasScore()) {
            info.addPara("%s", opad, Misc.getGrayColor(), Str.f("terminal.credit.thinFile", BankSettings.MIN_SCORING_MONTHS));
        }
        info.addPara(Str.get("terminal.credit.monthly"), opad, Misc.getGrayColor());

        // Score factors with the published FICO weights
        info.addSpacer(opad);
        heading(info, "terminal.credit.factors", opad);
        factor(info, "factor.payment", 35, r.payment, CreditBureau.PAYMENT_MAX);
        factor(info, "factor.amounts", 30, r.amounts, CreditBureau.AMOUNTS_MAX);
        factor(info, "factor.length", 15, r.length, CreditBureau.LENGTH_MAX);
        factor(info, "factor.newCredit", 10, r.newCredit, CreditBureau.NEW_MAX);
        factor(info, "factor.mix", 10, r.mix, CreditBureau.MIX_MAX);

        // Reason codes, as an adverse-action notice would list them
        if (!r.reasons.isEmpty()) {
            info.addSpacer(opad);
            heading(info, "terminal.credit.reasons", opad);
            int n = 1;
            for (String key : r.reasons) {
                info.addPara("%s", 3f, Misc.getNegativeHighlightColor(), n++ + ". " + Str.get(key));
            }
        }

        // Credit report summary
        info.addSpacer(opad);
        heading(info, "terminal.credit.report", opad);
        Color hl = Misc.getHighlightColor();
        info.addPara(Str.get("terminal.credit.accounts"), opad, hl, "" + r.openAccounts, "" + r.closedAccounts,
            String.format("%.0f", r.oldestMonths), String.format("%.0f", r.averageMonths));
        info.addPara(Str.get("terminal.credit.paymentRecord"), 3f, hl, "" + r.monthsOnTime,
            "" + r.late30, "" + r.late60, "" + r.late90, "" + r.late120);
        info.addPara(Str.get("terminal.credit.derogatory"), 3f,
            r.chargeOffs + r.repossessions > 0 || r.bankruptcyOnFile ? Misc.getNegativeHighlightColor() : hl,
            "" + r.chargeOffs, "" + r.repossessions, Str.get(r.bankruptcyOnFile ? "common.yes" : "common.no"));
        info.addPara(Str.get("terminal.credit.newCredit"), 3f, hl, "" + r.inquiries12, "" + r.accountsOpened12,
            String.format("%.0f%%", r.balanceRatio * 100));
        if (r.hasRevolving()) {
            info.addPara(Str.get("terminal.credit.utilization"), 3f, utilizationColor(r.utilization),
                String.format("%.0f%%", r.utilization * 100), Misc.getDGSCredits(r.revolvingBalance),
                Misc.getDGSCredits(r.revolvingLimit));
        }

        // Brackets (pricing and limits)
        info.addSpacer(opad);
        heading(info, "terminal.credit.brackets", opad);
        info.addPara(Str.get("terminal.credit.excellent"), opad, Misc.getPositiveHighlightColor(), Str.get("credit.bracket.excellent"));
        info.addPara(Str.get("terminal.credit.good"), opad, Misc.getHighlightColor(), Str.get("credit.bracket.good"));
        info.addPara(Str.get("terminal.credit.fair"), opad, GOLD, Str.get("credit.bracket.fair"));
        info.addPara(Str.get("terminal.credit.poor"), opad, Misc.getNegativeHighlightColor(), Str.get("credit.bracket.poor"));
        info.addPara(Str.get("terminal.credit.none"), opad, Misc.getGrayColor(), Str.get("credit.bracket.none"));

        List<Integer> history = csm.getScoreHistory();
        if (!history.isEmpty()) {
            info.addSpacer(opad);
            info.addSectionHeading(Str.f("terminal.credit.trend", history.size()),
                Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(), Alignment.MID, opad);
            StringBuilder trend = new StringBuilder();
            for (int i = history.size() - 1; i >= 0; i--) {
                if (trend.length() > 0) trend.append(" -> ");
                trend.append(history.get(i) > 0 ? String.valueOf(history.get(i)) : "--");
            }
            info.addPara("%s", opad, Misc.getHighlightColor(), trend.toString());
        }

        info.addSpacer(opad);
        heading(info, "terminal.credit.tips", opad);
        for (String tip : new String[]{"tipOnTime", "tipLate", "tipInquiries", "tipAge", "tipBalance", "tipUtilization", "tipPayoff", "tipMix"}) {
            info.addPara(Str.get("terminal.credit." + tip), 3f);
        }
        BankruptcyManager bm = data.getBankruptcyManager();
        if (bm.canFileBankruptcy(data)) {
            String keep = pct(1f - BankSettings.BANKRUPTCY_DEBT_REDUCTION, "%.0f") + "%";
            String months = Str.f("common.months", BankSettings.BANKRUPTCY_NO_LOANS_MONTHS);
            info.addSpacer(opad * 2);
            info.addSectionHeading(Str.get("terminal.bankruptcy.heading"), Misc.getNegativeHighlightColor(),
                Misc.getDarkPlayerColor(), Alignment.MID, opad);
            info.addPara(Str.get("terminal.bankruptcy.explain"), opad, Misc.getNegativeHighlightColor(),
                keep, "" + BankSettings.CREDIT_SCORE_MIN, months, "50%");
            info.addButton(Str.get("terminal.bankruptcy.file"), "bankruptcy_file",
                Misc.getNegativeHighlightColor(), DARK_NAVY, Alignment.MID, CutStyle.NONE, 200, 28f, opad);
        }
    }

    /** One factor row: name, published weight, points earned, and a rating. */
    private void factor(TooltipMakerAPI info, String key, int weight, float points, float max) {
        float q = max <= 0 ? 0 : points / max;
        String rating = q >= 0.9f ? "rating.excellent" : q >= 0.75f ? "rating.good" : q >= 0.5f ? "rating.fair" : "rating.poor";
        Color c = q >= 0.9f ? Misc.getPositiveHighlightColor() : q >= 0.75f ? Misc.getHighlightColor()
            : q >= 0.5f ? GOLD : Misc.getNegativeHighlightColor();
        info.addPara(Str.get("terminal.credit.factorRow"), 3f, c, Str.get(key), weight + "%",
            String.format("%.0f/%.0f", points, max), Str.get(rating));
    }

    private void renderHistory(TooltipMakerAPI info, float width, float opad) {
        BankData data = BankData.get();
        heading(info, "terminal.history.heading", opad);

        List<BankData.TransactionRecord> history = data.getTransactionHistory();
        if (history.isEmpty()) {
            info.addPara(Str.get("terminal.history.none"), Misc.getGrayColor(), opad);
        } else {
            for (BankData.TransactionRecord record : history) {
                Color amountColor = record.amount >= 0 ?
                    Misc.getPositiveHighlightColor() : Misc.getNegativeHighlightColor();
                String amountStr = record.amount != 0 ? Misc.getDGSCredits(Math.abs(record.amount)) : "--";
                String label = Str.get("tx." + record.type);
                if (label.startsWith("tx.")) label = record.type; // unknown type from an older save
                info.addPara("[%s] %s - %s", opad, amountColor, label, amountStr, record.description);
            }
        }
    }

    // ========== BUTTON HANDLING ==========

    @Override
    public void buttonPressConfirmed(Object buttonId, IntelUIAPI ui) {
        String id = buttonId.toString();

        if (id.startsWith("tab_")) {
            currentTab = id;
            ui.updateUIForItem(this);
            return;
        }

        BankData data = BankData.get();

        if (id.startsWith("loan_paymin_")) {
            String accountId = id.substring("loan_paymin_".length());
            BankAccount loan = data.getLoanManager().findLoan(accountId);
            if (loan != null) data.getLoanManager().makePayment(accountId, loan.getMonthlyPayment());
            ui.updateUIForItem(this);
            return;
        }

        if (id.startsWith("loan_paypastdue_")) {
            data.getLoanManager().payPastDue(id.substring("loan_paypastdue_".length()));
            ui.updateUIForItem(this);
            return;
        }

        if ("toggle_autopay".equals(id)) {
            data.setAutopayEnabled(!data.isAutopayEnabled());
            ui.updateUIForItem(this);
            return;
        }

        if (id.startsWith("loan_payoff_")) {
            data.getLoanManager().payOff(id.substring("loan_payoff_".length()));
            ui.updateUIForItem(this);
            return;
        }

        if ("line_open".equals(id)) {
            data.getLoanManager().openCreditLine(data);
            ui.updateUIForItem(this);
            return;
        }

        if (id.startsWith("line_draw_")) {
            String rest = id.substring("line_draw_".length());
            int lastUnderscore = rest.lastIndexOf('_');
            String accountId = rest.substring(0, lastUnderscore);
            int p = Integer.parseInt(rest.substring(lastUnderscore + 1));
            BankAccount line = data.getLoanManager().findLoan(accountId);
            if (line != null) data.getLoanManager().drawCreditLine(accountId, line.getAvailableCredit() * (p / 100f));
            ui.updateUIForItem(this);
            return;
        }

        if (id.startsWith("line_paystatement_")) {
            data.getLoanManager().payStatement(id.substring("line_paystatement_".length()));
            ui.updateUIForItem(this);
            return;
        }

        if (id.startsWith("line_autopay_")) {
            BankAccount line = data.getLoanManager().findLoan(id.substring("line_autopay_".length()));
            if (line != null) line.autopayFull = !line.autopayFull;
            ui.updateUIForItem(this);
            return;
        }

        if (id.startsWith("line_close_")) {
            data.getLoanManager().closeCreditLine(id.substring("line_close_".length()));
            ui.updateUIForItem(this);
            return;
        }

        if (id.startsWith("loan_take_")) {
            String rest = id.substring("loan_take_".length());
            int lastUnderscore = rest.lastIndexOf('_');
            String typeName = rest.substring(0, lastUnderscore);
            int p = Integer.parseInt(rest.substring(lastUnderscore + 1));

            LoanType type = LoanType.valueOf(typeName);
            int score = data.getCreditScoreManager().getScore();
            if (data.getLoanManager().whyNot(type, data) != null) {
                ui.updateUIForItem(this);
                return;
            }
            float amount = loanAmount(type, type.getMaxAmountForScore(score), p / 100f);
            float effectiveRate = data.getInterestEngine().calculateEffectiveLoanRate(type, score);
            data.getLoanManager().takeLoan(type, amount, effectiveRate);
            ui.updateUIForItem(this);
            return;
        }

        if (id.startsWith("invest_withdraw_")) {
            data.getInvestmentManager().withdraw(id.substring("invest_withdraw_".length()));
            ui.updateUIForItem(this);
            return;
        }

        if (id.startsWith("invest_buy_")) {
            String rest = id.substring("invest_buy_".length());
            int lastUnderscore = rest.lastIndexOf('_');
            String typeName = rest.substring(0, lastUnderscore);
            int amount = Integer.parseInt(rest.substring(lastUnderscore + 1));
            InvestmentType type = InvestmentType.valueOf(typeName);
            if (data.getBankruptcyManager().canInvest()) {
                data.getInvestmentManager().invest(type, (float) amount);
            }
            ui.updateUIForItem(this);
            return;
        }

        if ("bankruptcy_file".equals(id)) {
            data.getBankruptcyManager().fileBankruptcy(data);
            ui.updateUIForItem(this);
        }
    }

    @Override
    public boolean doesButtonHaveConfirmDialog(Object buttonId) {
        return "bankruptcy_file".equals(buttonId) || super.doesButtonHaveConfirmDialog(buttonId);
    }

    @Override
    public void createConfirmationPrompt(Object buttonId, TooltipMakerAPI prompt) {
        if (!"bankruptcy_file".equals(buttonId)) {
            super.createConfirmationPrompt(buttonId, prompt);
            return;
        }
        prompt.addPara(Str.get("terminal.bankruptcy.confirmTitle"), Misc.getNegativeHighlightColor(), 0f);
        prompt.addPara("%s", 10f, Misc.getTextColor(), Str.f("terminal.bankruptcy.confirmText", pct(BankSettings.BANKRUPTCY_DEBT_REDUCTION, "%.0f"), BankSettings.BANKRUPTCY_NO_LOANS_MONTHS));
    }

    @Override
    public String getConfirmText(Object buttonId) {
        return "bankruptcy_file".equals(buttonId) ? Str.get("terminal.bankruptcy.confirmButton") : super.getConfirmText(buttonId);
    }

    @Override
    public Set<String> getIntelTags(SectorMapAPI map) {
        Set<String> tags = super.getIntelTags(map);
        tags.add("Economy");
        return tags;
    }

    @Override
    public String getIcon() {
        // credits.png does not exist in vanilla; use the registered income report icon.
        return Global.getSettings().getSpriteName("intel", "monthly_income_report");
    }

    @Override
    public boolean isHidden() { return false; }

    @Override
    public IntelSortTier getSortTier() { return IntelSortTier.TIER_2; }
}
