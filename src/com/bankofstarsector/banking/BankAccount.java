package com.bankofstarsector.banking;

import com.bankofstarsector.core.BankData;
import com.bankofstarsector.core.Str;

import java.io.Serializable;

public class BankAccount implements Serializable {

    private static final long serialVersionUID = 1L;

    public String accountId;

    // Loan fields
    public LoanType loanType;
    public float principal;
    public float remainingBalance;
    /** Rate charged right now (base rate, possibly raised while overdue). */
    public float monthlyRate;
    /** Rate agreed when the loan was signed. */
    public float baseMonthlyRate;
    public int termMonths;
    public int monthsElapsed;
    public int daysOverdue;
    /** Installments billed but not yet paid (includes the current bill). */
    public float amountPastDue;
    /** Installment billed at the last month end; it only becomes late if still unpaid a month later. */
    public float currentBill;
    public int missedPayments;
    public LoanStatus status;
    /** Credit-builder loans (0.3.0): loan money held at the bank until payoff. 0 for other loans and in older saves. */
    public float heldFunds;

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
        account.accountId = BankData.get().nextAccountId("LOAN");
        account.isLoan = true;
        account.loanType = type;
        account.principal = amount;
        account.remainingBalance = amount;
        account.monthlyRate = effectiveRate;
        account.baseMonthlyRate = effectiveRate;
        account.termMonths = type.getTermMonths();
        account.monthsElapsed = 0;
        account.daysOverdue = 0;
        account.amountPastDue = 0f;
        account.status = LoanStatus.ACTIVE;
        account.createdTimestamp = timestamp;
        return account;
    }

    public static BankAccount createInvestment(InvestmentType type, float amount, long timestamp) {
        BankAccount account = new BankAccount();
        account.accountId = BankData.get().nextAccountId("INV");
        account.isLoan = false;
        account.investmentType = type;
        account.investedAmount = amount;
        account.currentValue = amount;
        account.accumulatedReturns = 0f;
        account.lockMonthsRemaining = type.lockMonths;
        account.createdTimestamp = timestamp;
        return account;
    }

    /** Fixes fields added after 0.1.x so old saves keep working. */
    public void migrate() {
        if (isLoan && baseMonthlyRate <= 0f) baseMonthlyRate = monthlyRate;
    }

    /** Arrears older than the current bill - the part that is actually late. */
    public float getLateAmount() {
        return Math.max(0f, amountPastDue - currentBill);
    }

    public boolean isOpenLoan() {
        return isLoan && (status == LoanStatus.ACTIVE || status == LoanStatus.OVERDUE || status == LoanStatus.DEFAULTED);
    }

    /** Scheduled installment: interest on the balance plus an even share of principal. */
    public float getMonthlyPayment() {
        if (!isOpenLoan()) return 0f;
        if (monthsElapsed >= termMonths) return remainingBalance; // term over: balloon payment
        float interestPayment = remainingBalance * monthlyRate;
        float principalPayment = principal / termMonths;
        return Math.min(remainingBalance, interestPayment + principalPayment);
    }

    public boolean isLocked() {
        return !isLoan && lockMonthsRemaining > 0;
    }

    public String getStatusDisplay() {
        if (isLoan) {
            switch (status) {
                case ACTIVE: return Str.get("status.current");
                case OVERDUE: return Str.f("status.overdue", daysOverdue);
                case DEFAULTED: return Str.f("status.defaulted", daysOverdue);
                case PAID_OFF: return Str.get("status.paidOff");
                case SEIZED: return Str.get("status.seized");
                default: return status.name();
            }
        }
        if (lockMonthsRemaining > 0) return Str.f("status.locked", lockMonthsRemaining);
        return Str.get("status.active");
    }
}
