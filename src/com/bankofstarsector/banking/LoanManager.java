package com.bankofstarsector.banking;

import com.bankofstarsector.core.BankData;
import com.bankofstarsector.core.BankSettings;
import com.fs.starfarer.api.Global;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class LoanManager implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<BankAccount> loans;

    public LoanManager() {
        loans = new ArrayList<BankAccount>();
    }

    public List<BankAccount> getLoans() {
        if (loans == null) loans = new ArrayList<BankAccount>();
        return loans;
    }

    public List<BankAccount> getActiveLoans() {
        List<BankAccount> active = new ArrayList<BankAccount>();
        for (BankAccount loan : getLoans()) {
            if (loan.status == LoanStatus.ACTIVE || loan.status == LoanStatus.OVERDUE) {
                active.add(loan);
            }
        }
        return active;
    }

    public int getActiveLoanCount() {
        return getActiveLoans().size();
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

        // Credit the player
        Global.getSector().getPlayerFleet().getCargo().getCredits().add(amount);

        BankData.get().addTransaction("LOAN", amount,
            "Took " + type.displayName + " for " + formatCredits(amount));

        return loan;
    }

    public boolean makePayment(String accountId, float amount) {
        BankAccount loan = findLoan(accountId);
        if (loan == null || loan.status == LoanStatus.PAID_OFF) return false;

        float playerCredits = Global.getSector().getPlayerFleet().getCargo().getCredits().get();
        if (playerCredits < amount) return false;

        Global.getSector().getPlayerFleet().getCargo().getCredits().subtract(amount);
        loan.remainingBalance -= amount;

        if (loan.remainingBalance <= 0) {
            loan.remainingBalance = 0;
            loan.status = LoanStatus.PAID_OFF;
            BankData.get().getCreditScoreManager().onLoanPayoff();
            BankData.get().addTransaction("PAYOFF", amount,
                "Paid off " + loan.loanType.displayName);
        } else {
            boolean wasOverdue = loan.status == LoanStatus.OVERDUE;
            if (wasOverdue) {
                loan.daysOverdue = 0;
                loan.status = LoanStatus.ACTIVE;
            }
            BankData.get().getCreditScoreManager().onPaymentMade(!wasOverdue);
            BankData.get().addTransaction("PAYMENT", -amount,
                "Payment on " + loan.loanType.displayName);
        }

        return true;
    }

    public boolean payOff(String accountId) {
        BankAccount loan = findLoan(accountId);
        if (loan == null || loan.status == LoanStatus.PAID_OFF) return false;
        return makePayment(accountId, loan.remainingBalance);
    }

    public void advanceMonth(InterestEngine engine) {
        for (BankAccount loan : getActiveLoans()) {
            // Apply interest
            float interest = loan.remainingBalance * loan.monthlyRate;
            loan.remainingBalance += interest;
            loan.monthsElapsed++;

            // Check if over term
            if (loan.monthsElapsed > loan.termMonths && loan.status == LoanStatus.ACTIVE) {
                loan.status = LoanStatus.OVERDUE;
            }
        }
    }

    public void advanceDay() {
        for (BankAccount loan : getActiveLoans()) {
            if (loan.status == LoanStatus.OVERDUE) {
                loan.daysOverdue++;

                // Escalate to default
                if (loan.daysOverdue >= BankSettings.DEFAULT_THRESHOLD_DAYS &&
                        loan.status != LoanStatus.DEFAULTED) {
                    loan.status = LoanStatus.DEFAULTED;
                    BankData.get().getCreditScoreManager().onDefault();
                    BankData.get().addTransaction("DEFAULT", 0,
                        loan.loanType.displayName + " has DEFAULTED");
                }
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

    public void cleanupPaidLoans() {
        Iterator<BankAccount> it = getLoans().iterator();
        while (it.hasNext()) {
            BankAccount loan = it.next();
            if (loan.status == LoanStatus.PAID_OFF && loan.monthsElapsed > loan.termMonths + 6) {
                it.remove();
            }
        }
    }

    private String formatCredits(float amount) {
        if (amount >= 1000000) return String.format("%.1fM", amount / 1000000f);
        if (amount >= 1000) return String.format("%.0fk", amount / 1000f);
        return String.format("%.0f", amount);
    }
}
