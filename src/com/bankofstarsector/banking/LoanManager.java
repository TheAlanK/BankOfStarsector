package com.bankofstarsector.banking;

import com.bankofstarsector.core.BankData;
import com.bankofstarsector.core.BankSettings;
import com.fs.starfarer.api.Global;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Loan lifecycle: signing, monthly billing (interest + installment), payments,
 * overdue tracking and default.
 *
 * Each month the bank bills one installment into {@link BankAccount#amountPastDue}.
 * Autopay (see BankEconomyListener) settles it through the vanilla monthly report;
 * anything left unpaid makes the loan OVERDUE and starts the collection clock.
 */
public class LoanManager implements Serializable {

    private static final long serialVersionUID = 1L;

    /** Loans younger than this are not billed at their first month end. */
    private static final float GRACE_DAYS = 15f;

    private List<BankAccount> loans;

    public LoanManager() {
        loans = new ArrayList<BankAccount>();
    }

    public List<BankAccount> getLoans() {
        if (loans == null) loans = new ArrayList<BankAccount>();
        return loans;
    }

    /** Open loans: current, overdue or defaulted. */
    public List<BankAccount> getActiveLoans() {
        List<BankAccount> active = new ArrayList<BankAccount>();
        for (BankAccount loan : getLoans()) {
            if (loan.isOpenLoan()) active.add(loan);
        }
        return active;
    }

    public int getActiveLoanCount() {
        return getActiveLoans().size();
    }

    /** Open loans that count toward the score bracket's loan limit (credit-builder loans do not). */
    public int getLimitedLoanCount() {
        int n = 0;
        for (BankAccount loan : getActiveLoans()) if (!loan.loanType.isBuilder() && !loan.loanType.isRevolving()) n++;
        return n;
    }

    public boolean hasOpenBuilderLoan() {
        for (BankAccount loan : getActiveLoans()) if (loan.loanType.isBuilder()) return true;
        return false;
    }

    public boolean hasDefaultedLoan() {
        for (BankAccount loan : getActiveLoans()) {
            if (loan.status == LoanStatus.DEFAULTED) return true;
        }
        return false;
    }

    public boolean canTakeLoan(LoanType type, int creditScore, int maxLoans) {
        if (creditScore < type.minCreditScore) return false;
        if (getLimitedLoanCount() >= maxLoans) return false;
        if (type.getMaxAmountForScore(creditScore) <= 0) return false;
        return true;
    }

    /**
     * Why the player can't take this loan type right now, as a string key; null if they can.
     * The single place the terminal and the tests ask.
     */
    public String whyNot(LoanType type, BankData data) {
        CreditScoreManager csm = data.getCreditScoreManager();
        int score = csm.getScore();
        if (type.isRevolving()) {
            if (data.getCollectionManager().isBankingRestricted()) return "terminal.loans.reasonOverdue";
            if (!data.getBankruptcyManager().canTakeLoanType(type)) return "terminal.loans.reasonBankruptcy";
            if (getCreditLine() != null) return "terminal.line.reasonOpen";
            if (lineLimitFor(csm) <= 0f) return "terminal.line.reasonScore";
            return null;
        }
        if (type.isBuilder()) {
            // Costs the bank nothing, so it stays open to rebuild credit, even after bankruptcy.
            if (data.getCollectionManager().isBankingRestricted()) return "terminal.loans.reasonOverdue";
            if (csm.hasScore() && score >= BankSettings.BUILDER_MAX_SCORE) return "terminal.loans.reasonBuilderScore";
            if (hasOpenBuilderLoan()) return "terminal.loans.reasonBuilderOpen";
            return null;
        }
        if (!data.getBankruptcyManager().canTakeLoanType(type)) return "terminal.loans.reasonBankruptcy";
        if (data.getCollectionManager().isBankingRestricted()) return "terminal.loans.reasonOverdue";
        if (!canTakeLoan(type, score, csm.getMaxLoans())) {
            return score < type.minCreditScore ? "terminal.loans.reasonScore" : "terminal.loans.reasonMax";
        }
        return null;
    }

    public BankAccount takeLoan(LoanType type, float amount, float effectiveRate) {
        long timestamp = Global.getSector().getClock().getTimestamp();
        BankAccount loan = BankAccount.createLoan(type, amount, effectiveRate, timestamp);
        getLoans().add(loan);
        // Application = hard inquiry; the account goes on the credit report.
        BankData.get().getCreditScoreManager().onLoanOpened(loan);

        if (type.isBuilder()) {
            // The money stays at the bank; the player only pays the installments.
            loan.heldFunds = amount;
            BankData.get().addTransaction("LOAN", 0f,
                com.bankofstarsector.core.Str.f("txd.builder", formatCredits(amount)));
        } else {
            Global.getSector().getPlayerFleet().getCargo().getCredits().add(amount);
            BankData.get().addTransaction("LOAN", amount,
                com.bankofstarsector.core.Str.f("txd.loan", type.getDisplayName(), formatCredits(amount)));
        }

        return loan;
    }

    /**
     * Pays toward a loan from the player's credits. Past-due installments are
     * settled first; clearing them cures an overdue or defaulted loan.
     */
    public boolean makePayment(String accountId, float amount) {
        BankAccount loan = findLoan(accountId);
        if (loan == null || !loan.isOpenLoan() || amount <= 0) return false;
        amount = Math.min(amount, loan.remainingBalance);

        float playerCredits = Global.getSector().getPlayerFleet().getCargo().getCredits().get();
        if (playerCredits < amount) return false;

        Global.getSector().getPlayerFleet().getCargo().getCredits().subtract(amount);
        applyPayment(loan, amount, "PAYMENT");
        return true;
    }

    /** Applies money that already left the player's hands (manual payment, autopay, seizure). */
    public void applyPayment(BankAccount loan, float amount, String txType) {
        BankData data = BankData.get();
        boolean wasLate = loan.status != LoanStatus.ACTIVE;

        loan.remainingBalance -= amount;
        loan.amountPastDue = Math.max(0f, loan.amountPastDue - amount);

        boolean forced = "SEIZURE".equals(txType) || "GARNISH".equals(txType);
        if (loan.loanType.isRevolving()) loan.paidSinceStatement += amount;
        if (loan.loanType.isRevolving() && loan.remainingBalance <= 1f && loan.creditLimit > 0f && !(forced && wasLate)) {
            // A credit line paid down to zero stays open.
            loan.remainingBalance = 0f;
            loan.amountPastDue = 0f;
            if (wasLate) {
                loan.status = LoanStatus.ACTIVE;
                loan.daysOverdue = 0;
                loan.monthlyRate = loan.baseMonthlyRate;
                data.getCollectionManager().onLoanResolved(loan.accountId);
            }
            data.addTransaction(txType, -amount, com.bankofstarsector.core.Str.f("txd.payment", loan.loanType.getDisplayName()));
            return;
        }
        if (loan.remainingBalance <= 1f) {
            loan.remainingBalance = 0;
            loan.amountPastDue = 0;
            data.getCollectionManager().onLoanResolved(loan.accountId);
            if (forced && wasLate) {
                // Closed by enforcement, not by the borrower: no payoff credit.
                loan.status = LoanStatus.SEIZED;
                data.addTransaction("SEIZED", -amount, com.bankofstarsector.core.Str.f("txd.seized", loan.loanType.getDisplayName()));
            } else {
                loan.status = LoanStatus.PAID_OFF;
                data.getCreditScoreManager().onLoanPayoff();
                data.addTransaction("PAYOFF", -amount, com.bankofstarsector.core.Str.f("txd.payoff", loan.loanType.getDisplayName()));
                releaseHeldFunds(loan);
            }
            return;
        }

        // Cured once nothing older than the current bill is outstanding.
        if (wasLate && loan.getLateAmount() <= 1f) {
            loan.status = LoanStatus.ACTIVE;
            loan.daysOverdue = 0;
            loan.monthlyRate = loan.baseMonthlyRate;
            data.getCollectionManager().onLoanResolved(loan.accountId);
            data.addTransaction("CURED", 0, com.bankofstarsector.core.Str.f("txd.cured", loan.loanType.getDisplayName()));
        }
        if (!forced) data.getCreditScoreManager().onPaymentMade(!wasLate);
        data.addTransaction(txType, -amount, com.bankofstarsector.core.Str.f("txd.payment", loan.loanType.getDisplayName()));
    }

    public boolean payOff(String accountId) {
        BankAccount loan = findLoan(accountId);
        if (loan == null || !loan.isOpenLoan()) return false;
        return makePayment(accountId, loan.remainingBalance);
    }

    public boolean payPastDue(String accountId) {
        BankAccount loan = findLoan(accountId);
        if (loan == null || loan.amountPastDue <= 0f) return false;
        return makePayment(accountId, loan.amountPastDue);
    }

    /**
     * Month end: accrue interest and bill one installment per open loan.
     * Returns the total billed this month.
     */
    public float billMonth(InterestEngine engine) {
        float billed = 0f;
        for (BankAccount loan : getActiveLoans()) {
            if (Global.getSector().getClock().getElapsedDaysSince(loan.createdTimestamp) < GRACE_DAYS) continue;

            if (loan.status != LoanStatus.ACTIVE) {
                loan.monthlyRate = engine.calculateOverdueRate(loan.baseMonthlyRate, Math.max(1, loan.daysOverdue / 30));
            }
            if (loan.loanType.isRevolving()) {
                billed += billCreditLine(loan);
                continue;
            }
            float interest = loan.remainingBalance * loan.monthlyRate;
            loan.remainingBalance += interest;

            float installment;
            if (loan.monthsElapsed + 1 >= loan.termMonths) {
                installment = loan.remainingBalance - loan.amountPastDue; // final month: everything left
            } else {
                installment = interest + loan.principal / loan.termMonths;
            }
            installment = Math.max(0f, Math.min(installment, loan.remainingBalance - loan.amountPastDue));
            loan.amountPastDue += installment;
            loan.currentBill = installment;
            loan.monthsElapsed++;
            billed += installment;
        }
        return billed;
    }

    /**
     * Monthly statement of a credit line. Interest is charged only when the previous statement was not
     * paid in full (the grace period); then a minimum payment is billed like an installment.
     */
    private float billCreditLine(BankAccount line) {
        boolean graceKept = line.paidSinceStatement >= line.statementBalance - 1f;
        float interest = graceKept ? 0f : line.remainingBalance * line.monthlyRate;
        line.remainingBalance += interest;
        line.lastInterest = interest;
        line.statementBalance = line.remainingBalance;
        line.paidSinceStatement = 0f;
        float minimum = BankAccount.lineMinimum(line.remainingBalance, interest);
        minimum = Math.max(0f, Math.min(minimum, line.remainingBalance - line.amountPastDue));
        line.amountPastDue += minimum;
        line.currentBill = minimum;
        line.monthsElapsed++;
        return minimum;
    }

    /**
     * Called after autopay. An installment is late when it is still unpaid at the month end
     * after it was billed, i.e. when arrears older than this month's bill remain.
     */
    public void markMissedPayments() {
        BankData data = BankData.get();
        for (BankAccount loan : getActiveLoans()) {
            float late = loan.getLateAmount();
            if (late <= 1f) continue;
            loan.missedPayments++;
            data.getCreditScoreManager().onPaymentMissed();
            if (loan.status == LoanStatus.ACTIVE) {
                loan.status = LoanStatus.OVERDUE;
                loan.daysOverdue = Math.max(loan.daysOverdue, 1);
            }
            data.addTransaction("MISSED", 0,
                com.bankofstarsector.core.Str.f("txd.missed", formatCredits(late), loan.loanType.getDisplayName()));
        }
    }

    /**
     * Month end, after missed payments are marked: credit lines paid on time earn a limit increase
     * every few statements; a late payment resets that and cuts the limit (never below the balance).
     */
    public void reviewCreditLines(CreditScoreManager csm) {
        BankData data = BankData.get();
        for (BankAccount line : getActiveLoans()) {
            if (!line.loanType.isRevolving() || line.monthsElapsed <= 0 || line.creditLimit <= 0f) continue;
            if (line.getLateAmount() > 1f) {
                line.onTimeStreak = 0;
                float cut = Math.max(line.remainingBalance, line.creditLimit * (1f - BankSettings.LINE_LATE_CUT_PCT));
                if (cut < line.creditLimit - 1f) {
                    line.creditLimit = cut;
                    data.addTransaction("LIMIT", 0f, com.bankofstarsector.core.Str.f("txd.lineCut", formatCredits(cut)));
                }
                continue;
            }
            line.onTimeStreak++;
            if (BankSettings.LINE_INCREASE_MONTHS > 0 && line.onTimeStreak % BankSettings.LINE_INCREASE_MONTHS == 0) {
                float cap = lineLimitFor(csm) * BankSettings.LINE_MAX_MULTIPLIER;
                float raised = Math.min(cap, line.creditLimit * (1f + BankSettings.LINE_INCREASE_PCT));
                if (raised > line.creditLimit + 1f) {
                    line.creditLimit = raised;
                    data.addTransaction("LIMIT", 0f, com.bankofstarsector.core.Str.f("txd.lineIncrease", formatCredits(raised)));
                }
            }
        }
    }

    // ------------------------------------------------------------------ credit line

    /** The player's open credit line, or null. */
    public BankAccount getCreditLine() {
        for (BankAccount loan : getActiveLoans()) if (loan.loanType.isRevolving()) return loan;
        return null;
    }

    /** Starting limit the player's score qualifies for (0 = not offered). */
    public static float lineLimitFor(CreditScoreManager csm) {
        if (!csm.hasScore()) return 0f;
        int score = csm.getScore();
        if (score >= 750) return BankSettings.LINE_LIMIT_EXCELLENT;
        if (score >= 650) return BankSettings.LINE_LIMIT_GOOD;
        if (score >= BankSettings.LINE_MIN_SCORE) return BankSettings.LINE_LIMIT_FAIR;
        return 0f;
    }

    /** Opens a credit line: a hard inquiry and a new revolving account on the credit report. */
    public BankAccount openCreditLine(BankData data) {
        if (whyNot(LoanType.CREDIT_LINE, data) != null) return null;
        CreditScoreManager csm = data.getCreditScoreManager();
        float rate = data.getInterestEngine().calculateEffectiveLoanRate(LoanType.CREDIT_LINE, csm.getScore());
        BankAccount line = BankAccount.createLoan(LoanType.CREDIT_LINE, 0f, rate,
            Global.getSector().getClock().getTimestamp());
        line.creditLimit = lineLimitFor(csm);
        line.autopayFull = true; // no interest unless the player chooses to carry a balance
        getLoans().add(line);
        csm.onLoanOpened(line);
        data.addTransaction("LINE", 0f, com.bankofstarsector.core.Str.f("txd.lineOpened", formatCredits(line.creditLimit)));
        return line;
    }

    /** Draws money from the credit line. Not allowed while it is late or closed to draws. */
    public boolean drawCreditLine(String accountId, float amount) {
        BankAccount line = findLoan(accountId);
        BankData data = BankData.get();
        if (line == null || !line.loanType.isRevolving() || !line.isOpenLoan()) return false;
        if (line.status != LoanStatus.ACTIVE || data.getCollectionManager().isBankingRestricted()) return false;
        amount = Math.min(amount, line.getAvailableCredit());
        if (amount < 1f) return false;
        line.remainingBalance += amount;
        Global.getSector().getPlayerFleet().getCargo().getCredits().add(amount);
        data.addTransaction("DRAW", amount, com.bankofstarsector.core.Str.f("txd.lineDraw", formatCredits(amount)));
        return true;
    }

    /** Pays what is left of the last statement (avoids next month's interest). */
    public boolean payStatement(String accountId) {
        BankAccount line = findLoan(accountId);
        if (line == null || !line.loanType.isRevolving()) return false;
        float due = Math.max(line.getStatementRemaining(), line.amountPastDue);
        return due > 0f && makePayment(accountId, due);
    }

    /** Closes a credit line with no balance. */
    public boolean closeCreditLine(String accountId) {
        BankAccount line = findLoan(accountId);
        if (line == null || !line.loanType.isRevolving() || !line.isOpenLoan()) return false;
        if (line.remainingBalance > 1f || line.status != LoanStatus.ACTIVE) return false;
        line.remainingBalance = 0f;
        line.amountPastDue = 0f;
        line.status = LoanStatus.PAID_OFF;
        BankData.get().addTransaction("LINE", 0f, com.bankofstarsector.core.Str.get("txd.lineClosed"));
        return true;
    }

    /** Installments due right now that are not late yet (payable without penalty). */
    public float getTotalCurrentBills() {
        float total = 0f;
        for (BankAccount loan : getActiveLoans()) total += Math.min(loan.currentBill, loan.amountPastDue);
        return total;
    }

    /** Late arrears only (older than the current bill). */
    public float getTotalLate() {
        float total = 0f;
        for (BankAccount loan : getActiveLoans()) total += loan.getLateAmount();
        return total;
    }

    public float getTotalPastDue() {
        float total = 0f;
        for (BankAccount loan : getActiveLoans()) total += loan.amountPastDue;
        return total;
    }

    /** Daily: overdue loans age toward default. */
    public void advanceDay() {
        for (BankAccount loan : getActiveLoans()) {
            if (loan.status == LoanStatus.ACTIVE) continue;
            loan.daysOverdue++;
            if (loan.daysOverdue >= BankSettings.DEFAULT_THRESHOLD_DAYS && loan.status != LoanStatus.DEFAULTED) {
                loan.status = LoanStatus.DEFAULTED;
                if (loan.loanType.isRevolving()) loan.creditLimit = 0f; // closed to new draws for good
                BankData.get().getCreditScoreManager().onDefault(loan);
                BankData.get().addTransaction("DEFAULT", 0, com.bankofstarsector.core.Str.f("txd.default", loan.loanType.getDisplayName()));
                applyHeldFunds(loan); // a credit-builder loan is settled from its own deposit first
                BankData.get().getAssetSeizureManager().seizeInvestments(BankData.get());
            }
        }
    }

    /** Payoff of a credit-builder loan: the held money goes to the player. */
    private void releaseHeldFunds(BankAccount loan) {
        if (loan.heldFunds <= 0f) return;
        float held = loan.heldFunds;
        loan.heldFunds = 0f;
        Global.getSector().getPlayerFleet().getCargo().getCredits().add(held);
        BankData.get().addTransaction("RELEASE", held,
            com.bankofstarsector.core.Str.f("txd.builderReleased", formatCredits(held)));
    }

    /**
     * Default or bankruptcy of a credit-builder loan: the bank keeps what the loan still owes from the
     * held money and returns any excess to the player.
     */
    public void applyHeldFunds(BankAccount loan) {
        if (loan.heldFunds <= 0f) return;
        float held = loan.heldFunds;
        loan.heldFunds = 0f;
        float used = Math.min(held, loan.remainingBalance);
        float excess = held - used;
        if (excess > 0f) {
            Global.getSector().getPlayerFleet().getCargo().getCredits().add(excess);
            BankData.get().addTransaction("RELEASE", excess,
                com.bankofstarsector.core.Str.f("txd.builderReleased", formatCredits(excess)));
        }
        if (used > 0f) applyPayment(loan, used, "SEIZURE");
    }

    /** Credit-builder money held at the bank: the player's, released at payoff. */
    public float getTotalHeldFunds() {
        float total = 0f;
        for (BankAccount loan : getActiveLoans()) total += loan.heldFunds;
        return total;
    }

    public float getTotalDebt() {
        float total = 0f;
        for (BankAccount loan : getActiveLoans()) {
            total += loan.remainingBalance;
        }
        return total;
    }

    public float getTotalMonthlyPayment() {
        float total = 0f;
        for (BankAccount loan : getActiveLoans()) {
            total += loan.getMonthlyPayment();
        }
        return total;
    }

    public BankAccount findLoan(String accountId) {
        for (BankAccount loan : getLoans()) {
            if (loan.accountId.equals(accountId)) return loan;
        }
        return null;
    }

    /** Closed loans live on only in the transaction history. */
    public void cleanupPaidLoans() {
        Iterator<BankAccount> it = getLoans().iterator();
        while (it.hasNext()) {
            BankAccount loan = it.next();
            if (loan.status == LoanStatus.PAID_OFF || loan.status == LoanStatus.SEIZED) {
                it.remove();
            }
        }
    }

    public static String formatCredits(float amount) {
        if (amount >= 1000000) return String.format("%.1fM", amount / 1000000f);
        if (amount >= 1000) return String.format("%.0fk", amount / 1000f);
        return String.format("%.0f", amount);
    }
}
