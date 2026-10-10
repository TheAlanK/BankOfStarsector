package com.bankofstarsector.banking;

public enum LoanType {

    EMERGENCY("Emergency Loan", 50000f, 0.08f, 3, 300,
        "Quick cash for urgent needs. High interest, short term."),
    SMALL("Small Business Loan", 200000f, 0.05f, 6, 400,
        "For fleet upgrades, repairs, and small operations."),
    CORPORATE("Corporate Loan", 500000f, 0.04f, 12, 550,
        "Substantial funding for colony establishment or expansion."),
    MEGACORP("Megacorp Loan", 1500000f, 0.035f, 24, 650,
        "Major capital for large-scale industrial operations."),
    SOVEREIGN("Sovereign Credit Line", 5000000f, 0.03f, 36, 750,
        "Elite financing for faction-level operations. Excellent credit required."),
    /** Since 0.3.0. Amount, term and rate come from BankSettings.BUILDER_*. */
    BUILDER("Credit Builder Loan", 50000f, 0.01f, 12, 300,
        "The money stays at the bank until you pay the loan off. Builds a credit file."),
    /** Since 0.3.0. A revolving account: limit, rate and minimum payment come from BankSettings.LINE_*. */
    CREDIT_LINE("Confederation Credit Line", 500000f, 0.02f, 0, 500,
        "Draw and repay freely up to your limit. Pay each statement in full and you pay no interest."),
    /** Since 0.3.0. Secured by a colony: amount from its appraisal (BankSettings.SECURED_LTV). */
    SECURED("Colony-Secured Loan", 3000000f, 0.03f, 24, 450,
        "Pledge one of your colonies for a larger, cheaper loan. If it defaults, the colony's income goes to the bank, and it can be foreclosed and auctioned.");

    public final String displayName;
    public final float maxAmount;
    public final float baseMonthlyRate;
    public final int termMonths;
    public final int minCreditScore;
    public final String description;

    LoanType(String displayName, float maxAmount, float baseMonthlyRate,
             int termMonths, int minCreditScore, String description) {
        this.displayName = displayName;
        this.maxAmount = maxAmount;
        this.baseMonthlyRate = baseMonthlyRate;
        this.termMonths = termMonths;
        this.minCreditScore = minCreditScore;
        this.description = description;
    }

    /**
     * A credit-builder loan: the money is held at the bank until the loan is paid off, so it costs
     * the bank nothing to offer and exists only to build a credit history.
     */
    public boolean isBuilder() {
        return this == BUILDER;
    }

    /** A revolving credit line: no term, billed a minimum payment on its balance every month. */
    public boolean isRevolving() {
        return this == CREDIT_LINE;
    }

    /** Secured by one of the player's colonies (needs a colony to be offered). */
    public boolean isSecured() {
        return this == SECURED;
    }

    public int getTermMonths() {
        if (isRevolving()) return 0;
        return isBuilder() ? com.bankofstarsector.core.BankSettings.BUILDER_TERM_MONTHS : termMonths;
    }

    /** Monthly base rate before credit-score and war adjustments. */
    public float getBaseRate() {
        if (isRevolving()) return com.bankofstarsector.core.BankSettings.LINE_RATE;
        if (isSecured()) return baseMonthlyRate * (1f - com.bankofstarsector.core.BankSettings.SECURED_RATE_DISCOUNT);
        return baseMonthlyRate;
    }

    public float getMaxAmountForScore(int creditScore) {
        if (isBuilder()) return com.bankofstarsector.core.BankSettings.BUILDER_MAX_AMOUNT;
        if (creditScore < minCreditScore) return 0f;
        float scoreRatio = Math.min(1f, (creditScore - minCreditScore) / 200f);
        return maxAmount * (0.5f + 0.5f * scoreRatio);
    }

    /** Localized name (see data/strings). The English field above stays for save compatibility. */
    public String getDisplayName() {
        return com.bankofstarsector.core.Str.get("loan." + name() + ".name");
    }

    public String getDescription() {
        return com.bankofstarsector.core.Str.get("loan." + name() + ".desc");
    }
}
