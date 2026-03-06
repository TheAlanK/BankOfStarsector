package com.bankofstarsector.intel;

import com.bankofstarsector.banking.*;
import com.bankofstarsector.collection.BankruptcyManager;
import com.bankofstarsector.core.BankData;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.*;
import com.fs.starfarer.api.util.Misc;

import java.awt.Color;
import java.util.List;
import java.util.Set;

public class BankingIntelPlugin extends BaseIntelPlugin {

    private static final Color GOLD = new Color(212, 175, 55);
    private static final Color DARK_NAVY = new Color(20, 30, 60);

    private String currentTab = TAB_OVERVIEW;
    private String pendingLoanType = null;
    private String pendingInvestType = null;

    public static final String TAB_OVERVIEW = "tab_overview";
    public static final String TAB_LOANS = "tab_loans";
    public static final String TAB_INVESTMENTS = "tab_investments";
    public static final String TAB_CREDIT = "tab_credit";
    public static final String TAB_HISTORY = "tab_history";

    @Override
    public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
        Color c = getTitleColor(mode);
        info.addPara("PBC Banking Terminal", c, 0f);

        BankData data = BankData.get();
        CreditScoreManager csm = data.getCreditScoreManager();
        float pad = 3f;

        info.addPara("Credit Score: %s (%s)", pad, Misc.getGrayColor(),
            Misc.getHighlightColor(),
            "" + csm.getScore(), csm.getBracket());

        float debt = data.getLoanManager().getTotalDebt();
        float invested = data.getInvestmentManager().getTotalValue();
        if (debt > 0) {
            info.addPara("Debt: %s", pad, Misc.getGrayColor(),
                Misc.getNegativeHighlightColor(), Misc.getDGSCredits(debt));
        }
        if (invested > 0) {
            info.addPara("Investments: %s", pad, Misc.getGrayColor(),
                Misc.getPositiveHighlightColor(), Misc.getDGSCredits(invested));
        }

        BankruptcyManager.BankruptcyState bState = data.getBankruptcyManager().getState();
        if (bState != BankruptcyManager.BankruptcyState.NONE) {
            info.addPara("BANKRUPTCY: " + bState.name(), Misc.getNegativeHighlightColor(), pad);
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
        info.addSectionHeading("PBC Banking Terminal",
            GOLD, DARK_NAVY, Alignment.MID, opad);

        String[] tabs = {"Overview", "Loans", "Investments", "Credit Score", "History"};
        String[] tabIds = {TAB_OVERVIEW, TAB_LOANS, TAB_INVESTMENTS, TAB_CREDIT, TAB_HISTORY};

        for (int i = 0; i < tabs.length; i++) {
            Color btnColor = tabIds[i].equals(currentTab) ? GOLD : Misc.getBasePlayerColor();
            info.addButton(tabs[i], tabIds[i], btnColor, DARK_NAVY,
                (Alignment) Alignment.MID, CutStyle.NONE, width / tabs.length - 4, 24f, 3f);
        }
    }

    // ========== TAB RENDERING ==========

    private void renderOverview(TooltipMakerAPI info, float width, float opad) {
        BankData data = BankData.get();
        CreditScoreManager csm = data.getCreditScoreManager();
        LoanManager lm = data.getLoanManager();
        InvestmentManager im = data.getInvestmentManager();

        float credits = Global.getSector().getPlayerFleet().getCargo().getCredits().get();
        float netWorth = data.getNetWorth();

        info.addSectionHeading("Account Summary", Misc.getBasePlayerColor(),
            Misc.getDarkPlayerColor(), Alignment.MID, opad);

        info.addPara("Net Worth: %s", opad, Misc.getHighlightColor(),
            Misc.getDGSCredits(netWorth));
        info.addPara("Credits on Hand: %s", opad, Misc.getHighlightColor(),
            Misc.getDGSCredits(credits));
        info.addPara("Total Debt: %s", opad,
            lm.getTotalDebt() > 0 ? Misc.getNegativeHighlightColor() : Misc.getGrayColor(),
            Misc.getDGSCredits(lm.getTotalDebt()));
        info.addPara("Total Investments: %s", opad, Misc.getPositiveHighlightColor(),
            Misc.getDGSCredits(im.getTotalValue()));

        info.addSpacer(opad);
        info.addSectionHeading("Credit Score", Misc.getBasePlayerColor(),
            Misc.getDarkPlayerColor(), Alignment.MID, opad);

        Color scoreColor = csm.getScore() >= 750 ? Misc.getPositiveHighlightColor() :
                          csm.getScore() >= 650 ? Misc.getHighlightColor() :
                          csm.getScore() >= 500 ? GOLD : Misc.getNegativeHighlightColor();
        info.addPara("Score: %s (%s)", opad, scoreColor,
            "" + csm.getScore(), csm.getBracket());
        info.addPara("Max Simultaneous Loans: %s", opad, Misc.getHighlightColor(),
            "" + csm.getMaxLoans());

        info.addSpacer(opad);
        info.addSectionHeading("Monthly Cash Flow", Misc.getBasePlayerColor(),
            Misc.getDarkPlayerColor(), Alignment.MID, opad);

        float monthlyPayments = lm.getTotalMonthlyPayment();
        float monthlyReturns = im.getMonthlyProjectedReturn();
        float netCashFlow = monthlyReturns - monthlyPayments;

        info.addPara("Loan Payments: %s/mo", opad, Misc.getNegativeHighlightColor(),
            Misc.getDGSCredits(monthlyPayments));
        info.addPara("Investment Returns: %s/mo (est.)", opad, Misc.getPositiveHighlightColor(),
            Misc.getDGSCredits(monthlyReturns));
        info.addPara("Net Cash Flow: %s/mo", opad,
            netCashFlow >= 0 ? Misc.getPositiveHighlightColor() : Misc.getNegativeHighlightColor(),
            Misc.getDGSCredits(Math.abs(netCashFlow)));

        // Sector conditions
        InterestEngine engine = data.getInterestEngine();
        float warSurcharge = engine.getWarSurcharge();
        float disruption = engine.getMarketDisruptionModifier();

        if (warSurcharge > 0 || disruption > 0) {
            info.addSpacer(opad);
            info.addSectionHeading("Sector Conditions", Misc.getBasePlayerColor(),
                Misc.getDarkPlayerColor(), Alignment.MID, opad);
            if (warSurcharge > 0) {
                info.addPara("War Surcharge: +%s%%", opad, Misc.getNegativeHighlightColor(),
                    String.format("%.0f", warSurcharge * 100));
            }
            if (disruption > 0) {
                info.addPara("Market Disruption: -%s%% investment returns", opad,
                    Misc.getNegativeHighlightColor(),
                    String.format("%.0f", disruption * 100));
            }
        }

        // Bankruptcy status
        BankruptcyManager.BankruptcyState bState = data.getBankruptcyManager().getState();
        if (bState != BankruptcyManager.BankruptcyState.NONE) {
            info.addSpacer(opad);
            info.addSectionHeading("Bankruptcy Status", Misc.getNegativeHighlightColor(),
                Misc.getDarkPlayerColor(), Alignment.MID, opad);
            info.addPara("State: %s", opad, Misc.getNegativeHighlightColor(), bState.name());
            int days = data.getBankruptcyManager().getRecoveryDaysRemaining();
            info.addPara("Full Recovery In: %s days", opad, Misc.getHighlightColor(), "" + days);
        }
    }

    private void renderLoans(TooltipMakerAPI info, float width, float opad) {
        BankData data = BankData.get();
        LoanManager lm = data.getLoanManager();
        CreditScoreManager csm = data.getCreditScoreManager();
        InterestEngine engine = data.getInterestEngine();

        // Active loans
        info.addSectionHeading("Active Loans", Misc.getBasePlayerColor(),
            Misc.getDarkPlayerColor(), Alignment.MID, opad);

        List<BankAccount> activeLoans = lm.getActiveLoans();
        if (activeLoans.isEmpty()) {
            info.addPara("No active loans.", Misc.getGrayColor(), opad);
        } else {
            for (BankAccount loan : activeLoans) {
                Color statusColor = loan.status == LoanStatus.ACTIVE ?
                    Misc.getPositiveHighlightColor() : Misc.getNegativeHighlightColor();

                info.addPara("%s  |  Balance: %s  |  Rate: %s%%/mo  |  %s", opad,
                    statusColor,
                    loan.loanType.displayName,
                    Misc.getDGSCredits(loan.remainingBalance),
                    String.format("%.1f", loan.monthlyRate * 100),
                    loan.getStatusDisplay());

                info.addPara("  Monthly Payment: %s  |  Months Elapsed: %s/%s",
                    3f, Misc.getHighlightColor(),
                    Misc.getDGSCredits(loan.getMonthlyPayment()),
                    "" + loan.monthsElapsed, "" + loan.termMonths);

                // Pay minimum button
                info.addButton("Pay Minimum (" + Misc.getDGSCredits(loan.getMonthlyPayment()) + ")",
                    "loan_paymin_" + loan.accountId,
                    Misc.getBasePlayerColor(), DARK_NAVY,
                    Alignment.MID, CutStyle.NONE, 200, 24f, 3f);

                // Pay off button
                info.addButton("Pay Off (" + Misc.getDGSCredits(loan.remainingBalance) + ")",
                    "loan_payoff_" + loan.accountId,
                    Misc.getBasePlayerColor(), DARK_NAVY,
                    Alignment.MID, CutStyle.NONE, 200, 24f, 3f);

                info.addSpacer(opad / 2);
            }
        }

        // Available loans
        info.addSpacer(opad);
        info.addSectionHeading("Available Loans", Misc.getBasePlayerColor(),
            Misc.getDarkPlayerColor(), Alignment.MID, opad);

        boolean canTakeAny = data.getBankruptcyManager().canTakeLoans();
        if (!canTakeAny) {
            info.addPara("Banking access restricted due to bankruptcy recovery.",
                Misc.getNegativeHighlightColor(), opad);
        } else if (data.getCollectionManager().isBankingRestricted()) {
            info.addPara("New loan applications restricted due to overdue payments.",
                Misc.getNegativeHighlightColor(), opad);
        } else {
            for (LoanType type : LoanType.values()) {
                boolean canTake = lm.canTakeLoan(type, csm.getScore(), csm.getMaxLoans())
                    && data.getBankruptcyManager().canTakeLoanType(type);
                float maxAmount = type.getMaxAmountForScore(csm.getScore());
                float effectiveRate = engine.calculateEffectiveLoanRate(type, csm.getScore());

                Color typeColor = canTake ? Misc.getHighlightColor() : Misc.getGrayColor();
                info.addPara("%s", opad, typeColor, type.displayName);
                info.addPara("  Max: %s  |  Rate: %s%%/mo  |  Term: %s months  |  Min Score: %s",
                    3f, Misc.getGrayColor(),
                    Misc.getDGSCredits(maxAmount),
                    String.format("%.1f", effectiveRate * 100),
                    "" + type.termMonths,
                    "" + type.minCreditScore);
                info.addPara("  %s", 3f, Misc.getGrayColor(), type.description);

                if (canTake) {
                    // Offer at different percentages
                    float[] pcts = {0.25f, 0.50f, 0.75f, 1.0f};
                    for (float pct : pcts) {
                        float amount = maxAmount * pct;
                        info.addButton("Take " + Misc.getDGSCredits(amount),
                            "loan_take_" + type.name() + "_" + (int)(pct * 100),
                            Misc.getBasePlayerColor(), DARK_NAVY,
                            Alignment.MID, CutStyle.NONE, 160, 22f, 2f);
                    }
                } else {
                    String reason = csm.getScore() < type.minCreditScore ?
                        "Insufficient credit score" :
                        "Maximum active loans reached";
                    info.addPara("  [%s]", 3f, Misc.getNegativeHighlightColor(), reason);
                }
                info.addSpacer(opad / 2);
            }
        }
    }

    private void renderInvestments(TooltipMakerAPI info, float width, float opad) {
        BankData data = BankData.get();
        InvestmentManager im = data.getInvestmentManager();

        // Active investments
        info.addSectionHeading("Portfolio", Misc.getBasePlayerColor(),
            Misc.getDarkPlayerColor(), Alignment.MID, opad);

        List<BankAccount> activeInvestments = im.getActiveInvestments();
        if (activeInvestments.isEmpty()) {
            info.addPara("No active investments.", Misc.getGrayColor(), opad);
        } else {
            for (BankAccount inv : activeInvestments) {
                float returnPct = inv.investedAmount > 0 ?
                    ((inv.currentValue - inv.investedAmount) / inv.investedAmount) * 100 : 0;
                Color returnColor = returnPct >= 0 ?
                    Misc.getPositiveHighlightColor() : Misc.getNegativeHighlightColor();

                info.addPara("%s  |  Value: %s  |  Return: %s%%  |  %s", opad,
                    Misc.getHighlightColor(),
                    inv.investmentType.displayName,
                    Misc.getDGSCredits(inv.currentValue),
                    String.format("%+.1f", returnPct),
                    inv.getStatusDisplay());

                if (!inv.isLocked()) {
                    info.addButton("Withdraw (" + Misc.getDGSCredits(inv.currentValue) + ")",
                        "invest_withdraw_" + inv.accountId,
                        Misc.getBasePlayerColor(), DARK_NAVY,
                        Alignment.MID, CutStyle.NONE, 200, 24f, 3f);
                } else {
                    info.addButton("Withdraw (Early, Penalty)",
                        "invest_withdraw_" + inv.accountId,
                        Misc.getNegativeHighlightColor(), DARK_NAVY,
                        Alignment.MID, CutStyle.NONE, 200, 24f, 3f);
                }
                info.addSpacer(opad / 2);
            }
        }

        // Available investments
        info.addSpacer(opad);
        info.addSectionHeading("Available Investments", Misc.getBasePlayerColor(),
            Misc.getDarkPlayerColor(), Alignment.MID, opad);

        boolean canInvest = data.getBankruptcyManager().canInvest();
        if (!canInvest) {
            info.addPara("Investment access restricted during bankruptcy recovery.",
                Misc.getNegativeHighlightColor(), opad);
        } else {
            float playerCredits = Global.getSector().getPlayerFleet().getCargo().getCredits().get();

            for (InvestmentType type : InvestmentType.values()) {
                boolean canAfford = playerCredits >= type.minInvestment;
                Color typeColor = canAfford ? Misc.getHighlightColor() : Misc.getGrayColor();

                info.addPara("%s", opad, typeColor, type.displayName);
                info.addPara("  Return: %s%%/mo  |  Volatility: %s%%  |  Lock: %s  |  Min: %s",
                    3f, Misc.getGrayColor(),
                    String.format("%.1f", type.baseMonthlyReturn * 100),
                    String.format("%.1f", type.volatility * 100),
                    type.lockMonths > 0 ? type.lockMonths + " months" : "None",
                    Misc.getDGSCredits(type.minInvestment));
                info.addPara("  %s", 3f, Misc.getGrayColor(), type.description);

                if (canAfford) {
                    float[] amounts = {type.minInvestment, type.minInvestment * 2,
                                       type.minInvestment * 5, type.minInvestment * 10};
                    for (float amt : amounts) {
                        if (amt <= playerCredits) {
                            info.addButton("Invest " + Misc.getDGSCredits(amt),
                                "invest_buy_" + type.name() + "_" + (int)amt,
                                Misc.getBasePlayerColor(), DARK_NAVY,
                                Alignment.MID, CutStyle.NONE, 160, 22f, 2f);
                        }
                    }
                } else {
                    info.addPara("  [Insufficient credits]", 3f, Misc.getNegativeHighlightColor());
                }
                info.addSpacer(opad / 2);
            }
        }
    }

    private void renderCreditScore(TooltipMakerAPI info, float width, float opad) {
        BankData data = BankData.get();
        CreditScoreManager csm = data.getCreditScoreManager();

        info.addSectionHeading("Credit Score", Misc.getBasePlayerColor(),
            Misc.getDarkPlayerColor(), Alignment.MID, opad);

        Color scoreColor = csm.getScore() >= 750 ? Misc.getPositiveHighlightColor() :
                          csm.getScore() >= 650 ? Misc.getHighlightColor() :
                          csm.getScore() >= 500 ? GOLD : Misc.getNegativeHighlightColor();

        info.addPara("Current Score: %s", opad, scoreColor, "" + csm.getScore());
        info.addPara("Bracket: %s", opad, scoreColor, csm.getBracket());
        info.addPara("Rate Modifier: %s", opad, Misc.getHighlightColor(),
            String.format("%+.0f%%", csm.getRateModifier() * 100));

        // Score brackets explained
        info.addSpacer(opad);
        info.addSectionHeading("Score Brackets", Misc.getBasePlayerColor(),
            Misc.getDarkPlayerColor(), Alignment.MID, opad);

        info.addPara("750-850 (Excellent): 5 loans, -15%% rates, all types + Sovereign", opad,
            Misc.getPositiveHighlightColor(), "Excellent");
        info.addPara("650-749 (Good): 3 loans, standard rates, all types", opad,
            Misc.getHighlightColor(), "Good");
        info.addPara("500-649 (Fair): 2 loans, +20%% rates, up to Corporate", opad,
            GOLD, "Fair");
        info.addPara("300-499 (Poor): 1 loan, +50%% rates, Emergency/Small only", opad,
            Misc.getNegativeHighlightColor(), "Poor");

        // Score history
        List<Integer> history = csm.getScoreHistory();
        if (!history.isEmpty()) {
            info.addSpacer(opad);
            info.addSectionHeading("Score Trend (Last " + history.size() + " Months)",
                Misc.getBasePlayerColor(), Misc.getDarkPlayerColor(), Alignment.MID, opad);

            StringBuilder trend = new StringBuilder();
            for (int i = history.size() - 1; i >= 0; i--) {
                if (trend.length() > 0) trend.append(" -> ");
                trend.append(history.get(i));
            }
            info.addPara(trend.toString(), opad, Misc.getHighlightColor());
        }

        // Tips
        info.addSpacer(opad);
        info.addSectionHeading("Improving Your Score", Misc.getBasePlayerColor(),
            Misc.getDarkPlayerColor(), Alignment.MID, opad);

        info.addPara("- Make loan payments on time (+5 per payment)", opad);
        info.addPara("- Pay off loans in full (+15 per payoff)", 3f);
        info.addPara("- Maintain active investments (+1 per 100k invested, max +5)", 3f);
        info.addPara("- Earn colony income over 100k/month (+3)", 3f);
        info.addPara("- Avoid late payments (-15 per late payment)", 3f);

        // Bankruptcy option
        BankruptcyManager bm = data.getBankruptcyManager();
        if (bm.canFileBankruptcy(data)) {
            info.addSpacer(opad * 2);
            info.addSectionHeading("BANKRUPTCY", Misc.getNegativeHighlightColor(),
                Misc.getDarkPlayerColor(), Alignment.MID, opad);
            info.addPara("Filing bankruptcy will reduce your debt to 20%% of current balance, " +
                "but your credit score will be reset to 300 and you will lose access to " +
                "banking services for up to 24 months. Investments will be liquidated at 50%% value.",
                opad, Misc.getNegativeHighlightColor(),
                "20%", "300", "24 months", "50%");
            info.addButton("FILE BANKRUPTCY", "bankruptcy_file",
                Misc.getNegativeHighlightColor(), DARK_NAVY,
                Alignment.MID, CutStyle.NONE, 200, 28f, opad);
        }
    }

    private void renderHistory(TooltipMakerAPI info, float width, float opad) {
        BankData data = BankData.get();

        info.addSectionHeading("Transaction History", Misc.getBasePlayerColor(),
            Misc.getDarkPlayerColor(), Alignment.MID, opad);

        List<BankData.TransactionRecord> history = data.getTransactionHistory();
        if (history.isEmpty()) {
            info.addPara("No transactions yet.", Misc.getGrayColor(), opad);
        } else {
            for (BankData.TransactionRecord record : history) {
                Color amountColor = record.amount >= 0 ?
                    Misc.getPositiveHighlightColor() : Misc.getNegativeHighlightColor();
                String amountStr = record.amount != 0 ?
                    Misc.getDGSCredits(Math.abs(record.amount)) : "--";

                info.addPara("[%s] %s - %s", opad, amountColor,
                    record.type, amountStr, record.description);
            }
        }
    }

    // ========== BUTTON HANDLING ==========

    @Override
    public void buttonPressConfirmed(Object buttonId, IntelUIAPI ui) {
        String id = buttonId.toString();

        // Tab switching
        if (id.startsWith("tab_")) {
            currentTab = id;
            ui.updateUIForItem(this);
            return;
        }

        BankData data = BankData.get();

        // Loan payment
        if (id.startsWith("loan_paymin_")) {
            String accountId = id.substring("loan_paymin_".length());
            BankAccount loan = data.getLoanManager().findLoan(accountId);
            if (loan != null) {
                data.getLoanManager().makePayment(accountId, loan.getMonthlyPayment());
            }
            ui.updateUIForItem(this);
            return;
        }

        if (id.startsWith("loan_payoff_")) {
            String accountId = id.substring("loan_payoff_".length());
            data.getLoanManager().payOff(accountId);
            ui.updateUIForItem(this);
            return;
        }

        // Take loan
        if (id.startsWith("loan_take_")) {
            String rest = id.substring("loan_take_".length());
            int lastUnderscore = rest.lastIndexOf('_');
            String typeName = rest.substring(0, lastUnderscore);
            int pct = Integer.parseInt(rest.substring(lastUnderscore + 1));

            LoanType type = LoanType.valueOf(typeName);
            float maxAmount = type.getMaxAmountForScore(data.getCreditScoreManager().getScore());
            float amount = maxAmount * (pct / 100f);
            float effectiveRate = data.getInterestEngine().calculateEffectiveLoanRate(
                type, data.getCreditScoreManager().getScore());

            data.getLoanManager().takeLoan(type, amount, effectiveRate);
            ui.updateUIForItem(this);
            return;
        }

        // Investment withdrawal
        if (id.startsWith("invest_withdraw_")) {
            String accountId = id.substring("invest_withdraw_".length());
            data.getInvestmentManager().withdraw(accountId);
            ui.updateUIForItem(this);
            return;
        }

        // Buy investment
        if (id.startsWith("invest_buy_")) {
            String rest = id.substring("invest_buy_".length());
            int lastUnderscore = rest.lastIndexOf('_');
            String typeName = rest.substring(0, lastUnderscore);
            int amount = Integer.parseInt(rest.substring(lastUnderscore + 1));

            InvestmentType type = InvestmentType.valueOf(typeName);
            data.getInvestmentManager().invest(type, (float) amount);
            ui.updateUIForItem(this);
            return;
        }

        // Bankruptcy
        if ("bankruptcy_file".equals(id)) {
            data.getBankruptcyManager().fileBankruptcy(data);
            ui.updateUIForItem(this);
            return;
        }
    }

    @Override
    public Set<String> getIntelTags(SectorMapAPI map) {
        Set<String> tags = super.getIntelTags(map);
        tags.add("Economy");
        return tags;
    }

    @Override
    public String getIcon() {
        return "graphics/icons/intel/credits.png";
    }

    @Override
    public boolean isHidden() { return false; }

    @Override
    public IntelSortTier getSortTier() { return IntelSortTier.TIER_2; }
}
