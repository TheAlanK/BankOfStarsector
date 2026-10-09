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

    public boolean hasDefaultedLoan() {
        for (BankAccount loan : getActiveLoans()) {
            if (loan.status == LoanStatus.DEFAULTED) return true;
        }
        return false;
    }

    public boolean canTakeLoan(LoanType type, int creditScore, int maxLoans) {
        if (creditScore < type.minCreditScore) return false;
        if (getActiveLoanCount() >= maxLoans) return false;
        if (type.getMaxAmountForScore(creditScore) <= 0) return false;
        return true;
    }

    public BankAccount takeLoan(LoanType type, float amount, float effectiveRate) {
        long timestamp = Global.getSector().getClock().getTimestamp();
        BankAccount loan = BankAccount.createLoan(type, amount, effectiveRate, timestamp);
        getLoans().add(loan);

        Global.getSector().getPlayerFleet().getCargo().getCredits().add(amount);

        BankData.get().addTransaction("LOAN", amount,
            "Took " + type.displayName + " for " + formatCredits(amount));

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
        if (loan.remainingBalance <= 1f) {
            loan.remainingBalance = 0;
            loan.amountPastDue = 0;
            data.getCollectionManager().onLoanResolved(loan.accountId);
            if (forced && wasLate) {
                // Closed by enforcement, not by the borrower: no payoff credit.
                loan.status = LoanStatus.SEIZED;
                data.addTransaction("SEIZED", -amount, loan.loanType.displayName + " closed by asset seizure");
            } else {
                loan.status = LoanStatus.PAID_OFF;
                data.getCreditScoreManager().onLoanPayoff();
                data.addTransaction("PAYOFF", -amount, "Paid off " + loan.loanType.displayName);
            }
            return;
        }

        // Cured once nothing older than the current bill is outstanding.
        if (wasLate && loan.getLateAmount() <= 1f) {
            loan.status = LoanStatus.ACTIVE;
            loan.daysOverdue = 0;
            loan.monthlyRate = loan.baseMonthlyRate;
            data.getCollectionManager().onLoanResolved(loan.accountId);
            data.addTransaction("CURED", 0, loan.loanType.displayName + " is current again");
        }
        if (!forced) data.getCreditScoreManager().onPaymentMade(!wasLate);
        data.addTransaction(txType, -amount, "Payment on " + loan.loanType.displayName);
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
            data.addTransaction("MISSED", 0, "Missed payment of " + formatCredits(late)
                + " on " + loan.loanType.displayName);
        }
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
                BankData.get().getCreditScoreManager().onDefault();
                BankData.get().addTransaction("DEFAULT", 0, loan.loanType.displayName + " has DEFAULTED");
                BankData.get().getAssetSeizureManager().seizeInvestments(BankData.get());
            }
        }
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
