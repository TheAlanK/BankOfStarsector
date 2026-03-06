package com.bankofstarsector.banking;

import java.io.Serializable;

public class BankAccount implements Serializable {

    private static final long serialVersionUID = 1L;

    private static int nextId = 1;

    public String accountId;

    // Loan fields
    public LoanType loanType;
    public float principal;
    public float remainingBalance;
    public float monthlyRate;
    public int termMonths;
    public int monthsElapsed;
    public int daysOverdue;
    public LoanStatus status;

    // Investment fields
    public InvestmentType investmentType;
    public float investedAmount;
    public float currentValue;
    public float accumulatedReturns;
    public int lockMonthsRemaining;

    // Common
    public boolean isLoan;
    public long createdTimestamp;

    private BankAccount() {}

    public static BankAccount createLoan(LoanType type, float amount, float effectiveRate, long timestamp) {
        BankAccount account = new BankAccount();
        account.accountId = "LOAN-" + (nextId++);
        account.isLoan = true;
        account.loanType = type;
        account.principal = amount;
        account.remainingBalance = amount;
        account.monthlyRate = effectiveRate;
        account.termMonths = type.termMonths;
        account.monthsElapsed = 0;
        account.daysOverdue = 0;
        account.status = LoanStatus.ACTIVE;
        account.createdTimestamp = timestamp;
        return account;
    }

    public static BankAccount createInvestment(InvestmentType type, float amount, long timestamp) {
        BankAccount account = new BankAccount();
        account.accountId = "INV-" + (nextId++);
        account.isLoan = false;
        account.investmentType = type;
        account.investedAmount = amount;
        account.currentValue = amount;
        account.accumulatedReturns = 0f;
        account.lockMonthsRemaining = type.lockMonths;
        account.createdTimestamp = timestamp;
        return account;
    }

    public float getMonthlyPayment() {
        if (!isLoan || status == LoanStatus.PAID_OFF) return 0f;
        float interestPayment = remainingBalance * monthlyRate;
        float principalPayment = principal / termMonths;
        return interestPayment + principalPayment;
    }

    public boolean isLocked() {
        return !isLoan && lockMonthsRemaining > 0;
    }

    public String getStatusDisplay() {
        if (isLoan) {
            switch (status) {
                case ACTIVE: return "Current";
                case OVERDUE: return "OVERDUE (" + daysOverdue + " days)";
                case DEFAULTED: return "DEFAULTED";
                case PAID_OFF: return "Paid Off";
                case SEIZED: return "SEIZED";
                default: return status.name();
            }
        } else {
            if (lockMonthsRemaining > 0) {
                return "Locked (" + lockMonthsRemaining + " mo)";
            }
            return "Active";
        }
    }
}
