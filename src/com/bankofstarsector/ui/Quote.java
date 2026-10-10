package com.bankofstarsector.ui;

import com.bankofstarsector.banking.InvestmentType;
import com.bankofstarsector.banking.LoanSchedule;
import com.bankofstarsector.banking.LoanType;
import com.bankofstarsector.core.BankSettings;
import com.bankofstarsector.core.Str;
import com.fs.starfarer.api.util.Misc;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

/**
 * What the player is about to sign, shown before a loan or an investment is made: in the terminal's
 * confirmation prompt and in the branch office dialog. Each line is a string-table format with its
 * highlighted arguments; renderers pass Str.get(key) as the format. Formats never contain a literal
 * percent sign (percentages are passed as arguments), so they render the same in tooltips
 * (String.format) and in the dialog text panel.
 */
public final class Quote {

    public static final class Line {
        /** String-table key of the format. */
        public final String key;
        public final Color color;
        public final String[] args;

        Line(String key, Color color, String... args) {
            this.key = key;
            this.color = color;
            this.args = args;
        }
    }

    private Quote() {}

    public static List<Line> loan(LoanType type, float amount, float monthlyRate) {
        LoanSchedule s = LoanSchedule.project(amount, monthlyRate, type.getTermMonths());
        Color hl = Misc.getHighlightColor();
        List<Line> lines = new ArrayList<Line>();
        lines.add(new Line("confirm.loan.summary", hl, type.getDisplayName(), Misc.getDGSCredits(amount),
            percent(monthlyRate, "%.1f"), "" + type.getTermMonths()));
        lines.add(new Line("confirm.loan.payments", hl, Misc.getDGSCredits(s.firstPayment),
            Misc.getDGSCredits(s.totalInterest), Misc.getDGSCredits(s.totalPaid)));
        lines.add(new Line("confirm.loan.firstDue", hl));
        if (type.isBuilder()) {
            lines.add(new Line("confirm.loan.held", Misc.getPositiveHighlightColor(), Misc.getDGSCredits(amount)));
        }
        lines.add(new Line("confirm.loan.inquiry", Misc.getNegativeHighlightColor()));
        return lines;
    }

    public static List<Line> investment(InvestmentType type, float amount) {
        Color hl = Misc.getHighlightColor();
        List<Line> lines = new ArrayList<Line>();
        lines.add(new Line("confirm.invest.summary", hl, type.getDisplayName(), Misc.getDGSCredits(amount)));
        lines.add(new Line("confirm.invest.return", hl, percent(type.baseMonthlyReturn, "%.1f"),
            percent(type.volatility, "%.1f")));
        if (type.lockMonths > 0) {
            lines.add(new Line("confirm.invest.lock", Misc.getNegativeHighlightColor(), "" + type.lockMonths,
                percent(BankSettings.EARLY_WITHDRAWAL_PENALTY, "%.0f")));
        } else {
            lines.add(new Line("confirm.invest.noLock", Misc.getPositiveHighlightColor()));
        }
        return lines;
    }

    /** 0.035 -> "3.5%" */
    static String percent(float fraction, String fmt) {
        return String.format(fmt, fraction * 100f) + "%";
    }

    /** A rate as text for a highlight argument: 0.035 -> "3.5%". */
    public static String percentText(float fraction) {
        return percent(fraction, "%.1f");
    }

    /** Smallest amount offered for a loan type (credit-builder minimum, else a tenth of the maximum, at least 1k). */
    public static float minLoanAmount(LoanType type, float maxAmount) {
        if (type.isBuilder()) return Math.min(BankSettings.BUILDER_MIN_AMOUNT, maxAmount);
        return Math.min(maxAmount, Math.max(1000f, maxAmount * 0.1f));
    }

    /** Selector values snap to whole thousands. */
    public static float roundAmount(float v) {
        return Math.round(v / 1000f) * 1000f;
    }
}
