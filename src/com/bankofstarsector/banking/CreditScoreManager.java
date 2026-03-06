package com.bankofstarsector.banking;

import com.bankofstarsector.core.BankSettings;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.econ.MarketAPI;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class CreditScoreManager implements Serializable {

    private static final long serialVersionUID = 1L;

    private int creditScore;
    private int onTimePaymentsThisMonth;
    private int latePaymentsThisMonth;
    private int loansPayedOffThisMonth;
    private boolean hadDefaultThisMonth;
    private List<Integer> scoreHistory; // last 12 months

    public CreditScoreManager() {
        creditScore = BankSettings.CREDIT_SCORE_DEFAULT;
        scoreHistory = new ArrayList<Integer>();
    }

    public int getScore() { return creditScore; }

    public String getBracket() {
        if (creditScore >= 750) return "Excellent";
        if (creditScore >= 650) return "Good";
        if (creditScore >= 500) return "Fair";
        return "Poor";
    }

    public int getMaxLoans() {
        if (creditScore >= 750) return 5;
        if (creditScore >= 650) return 3;
        if (creditScore >= 500) return 2;
        return 1;
    }

    public float getRateModifier() {
        if (creditScore >= 750) return -0.15f;
        if (creditScore >= 650) return 0f;
        if (creditScore >= 500) return 0.20f;
        return 0.50f;
    }

    public void onPaymentMade(boolean onTime) {
        if (onTime) {
            onTimePaymentsThisMonth++;
        } else {
            latePaymentsThisMonth++;
            adjustScore(BankSettings.SCORE_LATE_PAYMENT);
        }
    }

    public void onLoanPayoff() {
        loansPayedOffThisMonth++;
        adjustScore(BankSettings.SCORE_LOAN_PAYOFF);
    }

    public void onDefault() {
        hadDefaultThisMonth = true;
        adjustScore(BankSettings.SCORE_DEFAULT);
    }

    public void onBankruptcy() {
        creditScore = BankSettings.CREDIT_SCORE_MIN;
    }

    public void advanceMonth(float totalInvested, int activeLoansCount, boolean allLoansCurrent) {
        // On-time payment bonus
        if (onTimePaymentsThisMonth > 0) {
            adjustScore(BankSettings.SCORE_ON_TIME_PAYMENT * onTimePaymentsThisMonth);
        }

        // Payoff bonus already applied immediately

        // Investment bonus
        int investBonus = Math.min(BankSettings.SCORE_MAX_INVESTMENT_BONUS,
            (int)(totalInvested / 100000f) * BankSettings.SCORE_INVESTMENT_PER_100K);
        if (investBonus > 0) {
            adjustScore(investBonus);
        }

        // Colony income bonus
        float monthlyColonyIncome = getPlayerColonyIncome();
        if (monthlyColonyIncome > 100000f) {
            adjustScore(BankSettings.SCORE_COLONY_INCOME_BONUS);
        }

        // All loans current bonus
        if (allLoansCurrent && activeLoansCount > 0) {
            adjustScore(2);
        }

        // Natural drift toward 600
        if (creditScore > BankSettings.SCORE_NATURAL_DRIFT_TARGET) {
            adjustScore(-1);
        } else if (creditScore < BankSettings.SCORE_NATURAL_DRIFT_TARGET) {
            adjustScore(1);
        }

        // Decay for inactivity
        if (onTimePaymentsThisMonth == 0 && activeLoansCount == 0 && totalInvested <= 0) {
            adjustScore(BankSettings.SCORE_DECAY_PER_MONTH);
        }

        // Record history
        if (scoreHistory == null) scoreHistory = new ArrayList<Integer>();
        scoreHistory.add(0, creditScore);
        if (scoreHistory.size() > 12) {
            scoreHistory.remove(scoreHistory.size() - 1);
        }

        // Reset monthly counters
        onTimePaymentsThisMonth = 0;
        latePaymentsThisMonth = 0;
        loansPayedOffThisMonth = 0;
        hadDefaultThisMonth = false;
    }

    public List<Integer> getScoreHistory() {
        if (scoreHistory == null) scoreHistory = new ArrayList<Integer>();
        return scoreHistory;
    }

    private void adjustScore(int delta) {
        creditScore = Math.max(BankSettings.CREDIT_SCORE_MIN,
            Math.min(BankSettings.CREDIT_SCORE_MAX, creditScore + delta));
    }

    private float getPlayerColonyIncome() {
        float total = 0f;
        for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
            if (market.isPlayerOwned()) {
                total += market.getNetIncome();
            }
        }
        return total;
    }
}
