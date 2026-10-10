package com.bankofstarsector.banking;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Projected repayment of an installment loan, month by month, assuming every installment is paid
 * when billed. Mirrors {@link LoanManager#billMonth}: each month interest accrues on the balance and
 * the installment is that interest plus an even share of the principal; the last month bills
 * whatever is left. Used for quotes before signing and for the amortization table.
 *
 * Not saved: built on demand.
 */
public final class LoanSchedule {

    public static final class Row {
        public final int month;
        public final float payment, interest, principal, balance;

        Row(int month, float payment, float interest, float principal, float balance) {
            this.month = month;
            this.payment = payment;
            this.interest = interest;
            this.principal = principal;
            this.balance = balance;
        }
    }

    public final List<Row> rows;
    public final float firstPayment, totalInterest, totalPaid;

    private LoanSchedule(List<Row> rows) {
        this.rows = Collections.unmodifiableList(rows);
        float interest = 0f, paid = 0f;
        for (Row r : rows) {
            interest += r.interest;
            paid += r.payment;
        }
        this.firstPayment = rows.isEmpty() ? 0f : rows.get(0).payment;
        this.totalInterest = interest;
        this.totalPaid = paid;
    }

    /** A new loan of this amount, rate and term. */
    public static LoanSchedule project(float principal, float monthlyRate, int termMonths) {
        return project(principal, principal, monthlyRate, termMonths, 0);
    }

    /** What is left of an open loan, from its current balance and elapsed months. */
    public static LoanSchedule remaining(BankAccount loan) {
        return project(loan.principal, loan.remainingBalance, loan.monthlyRate, loan.termMonths, loan.monthsElapsed);
    }

    private static LoanSchedule project(float principal, float balance, float monthlyRate, int termMonths, int elapsed) {
        List<Row> rows = new ArrayList<Row>();
        int term = Math.max(1, termMonths);
        for (int m = elapsed; m < term && balance > 1f; m++) {
            float interest = balance * monthlyRate;
            balance += interest;
            float installment = m + 1 >= term ? balance : interest + principal / term;
            installment = Math.max(0f, Math.min(installment, balance));
            balance -= installment;
            rows.add(new Row(m + 1, installment, interest, installment - interest, Math.max(0f, balance)));
        }
        return new LoanSchedule(rows);
    }
}
