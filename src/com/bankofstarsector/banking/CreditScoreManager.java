package com.bankofstarsector.banking;

import com.bankofstarsector.core.BankSettings;
import com.bankofstarsector.core.Str;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Credit score facade used by the rest of the bank. Since 0.2.0 the score is not a running
 * counter that payments nudge up or down (which let players take and instantly repay loans to
 * farm points); it is computed by {@link CreditBureau} from a credit report that lenders update
 * once a month. See CreditBureau for the model.
 */
public class CreditScoreManager implements Serializable {

    private static final long serialVersionUID = 1L;

    // 0.1.x fields, kept so old saves deserialize. The old score is not carried over: it could be farmed.
    @SuppressWarnings("unused") private int creditScore;
    @SuppressWarnings("unused") private int onTimePaymentsThisMonth;
    @SuppressWarnings("unused") private int latePaymentsThisMonth;
    @SuppressWarnings("unused") private int loansPayedOffThisMonth;
    @SuppressWarnings("unused") private boolean hadDefaultThisMonth;
    private List<Integer> scoreHistory; // monthly, newest first; 0 = no score that month

    private CreditBureau bureau;

    public CreditScoreManager() {
        scoreHistory = new ArrayList<Integer>();
        bureau = new CreditBureau();
    }

    /** Lazily creates the bureau for 0.1.x saves, seeding it from the loans that are still open. */
    public CreditBureau bureau(LoanManager lm) {
        if (bureau == null) {
            bureau = new CreditBureau();
            if (lm != null) {
                for (BankAccount loan : lm.getLoans()) {
                    if (!loan.isOpenLoan()) continue;
                    bureau.openTradeline(loan);
                    CreditBureau.Tradeline t = bureau.find(loan.accountId);
                    t.monthsReported = loan.monthsElapsed;
                    t.monthsOnTime = Math.max(0, loan.monthsElapsed - loan.missedPayments);
                    t.lastReportedTs = CreditBureau.now();
                }
                bureau.recompute();
            }
        }
        return bureau;
    }

    private CreditBureau bureau() {
        return bureau(com.bankofstarsector.core.BankData.get().getLoanManager());
    }

    public CreditBureau getBureau() { return bureau(); }

    public CreditBureau.Result getReport() { return bureau().getResult(); }

    /** False while the credit file is too thin to be scored. */
    public boolean hasScore() { return getReport().score != null; }

    /**
     * Score used for underwriting and pricing. A thin file is treated as the minimum score, so
     * newcomers can only get starter credit until they build history.
     */
    public int getScore() {
        Integer s = getReport().score;
        return s != null ? s : BankSettings.CREDIT_SCORE_MIN;
    }

    /** Score for display: "--" when unscoreable. */
    public String getScoreText() {
        Integer s = getReport().score;
        return s != null ? String.valueOf(s) : "--";
    }

    public String getBracket() {
        if (!hasScore()) return Str.get("credit.bracket.none");
        int score = getScore();
        if (score >= 750) return Str.get("credit.bracket.excellent");
        if (score >= 650) return Str.get("credit.bracket.good");
        if (score >= 500) return Str.get("credit.bracket.fair");
        return Str.get("credit.bracket.poor");
    }

    public int getMaxLoans() {
        if (!hasScore()) return 1;
        int score = getScore();
        if (score >= 750) return 5;
        if (score >= 650) return 3;
        if (score >= 500) return 2;
        return 1;
    }

    public float getRateModifier() {
        return rateModifierFor(getScore());
    }

    /** The bracket modifier shown in the terminal is the one actually applied to new loans. */
    public static float rateModifierFor(int score) {
        if (score >= 750) return -0.15f;
        if (score >= 650) return 0f;
        if (score >= 500) return 0.20f;
        return 0.50f;
    }

    // ------------------------------------------------------------------ report events

    /** A loan application: hard inquiry, and the new account is opened on the report. */
    public void onLoanOpened(BankAccount loan) {
        bureau().recordInquiry(loan.loanType);
        bureau().openTradeline(loan);
    }

    public void onBankruptcy() {
        bureau().recordBankruptcy();
    }

    /** A loan reached default: charged off on the report right away. */
    public void onDefault(BankAccount loan) {
        bureau().recordChargeOff(loan.accountId);
    }

    /** Monthly reporting cycle (called at month end, after payments and before the bank cleans up closed loans). */
    public void advanceMonth(LoanManager lm) {
        bureau(lm).monthlyReport(lm);
        if (scoreHistory == null) scoreHistory = new ArrayList<Integer>();
        Integer s = getReport().score;
        scoreHistory.add(0, s != null ? s : 0);
        while (scoreHistory.size() > 12) scoreHistory.remove(scoreHistory.size() - 1);
    }

    public List<Integer> getScoreHistory() {
        if (scoreHistory == null) scoreHistory = new ArrayList<Integer>();
        return scoreHistory;
    }

    // ------------------------------------------------------------------ legacy hooks
    // Payments, payoffs, misses and defaults no longer change the score directly; the bureau
    // reads them from the loan's state at the monthly report. Kept as no-ops for callers.

    public void onPaymentMade(boolean onTime) {}
    public void onLoanPayoff() {}
    public void onPaymentMissed() {}
    public void onRecoveryMonth() {}
}
