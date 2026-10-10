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
    /** 0.3.0: per month (parallel to scoreHistory), the factor that moved the score most, as "factor.key:delta"; "" = none. */
    private List<String> scoreChanges;
    /** 0.3.0: factor points at the last monthly report (payment, amounts, length, new credit, mix); null before the first. */
    private float[] lastFactors;

    private static final String[] FACTOR_KEYS = {"factor.payment", "factor.amounts", "factor.length", "factor.newCredit", "factor.mix"};

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
        if (scoreChanges == null) scoreChanges = new ArrayList<String>();
        CreditBureau.Result r = getReport();
        Integer s = r.score;
        float[] factors = {r.payment, r.amounts, r.length, r.newCredit, r.mix};
        // Compared with the last report (not the live result), so mid-month events such as a new inquiry count.
        boolean comparable = s != null && lastFactors != null && !scoreHistory.isEmpty() && scoreHistory.get(0) > 0;
        scoreChanges.add(0, comparable ? biggestChange(lastFactors, factors) : "");
        lastFactors = factors;
        scoreHistory.add(0, s != null ? s : 0);
        while (scoreHistory.size() > 12) scoreHistory.remove(scoreHistory.size() - 1);
        while (scoreChanges.size() > scoreHistory.size()) scoreChanges.remove(scoreChanges.size() - 1);
    }

    private static String biggestChange(float[] before, float[] after) {
        int best = -1;
        float bestAbs = 0.5f; // ignore rounding noise
        for (int i = 0; i < FACTOR_KEYS.length && i < before.length; i++) {
            float d = Math.abs(after[i] - before[i]);
            if (d > bestAbs) { bestAbs = d; best = i; }
        }
        return best < 0 ? "" : FACTOR_KEYS[best] + ":" + Math.round(after[best] - before[best]);
    }

    /**
     * The factor that moved the score most at the report {@code monthsAgo} months back (0 = last
     * report), as "factor.key:delta", or "" (no score, nothing changed, or older than this record).
     */
    public String getScoreChange(int monthsAgo) {
        if (scoreChanges == null || monthsAgo < 0 || monthsAgo >= scoreChanges.size()) return "";
        return scoreChanges.get(monthsAgo);
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
