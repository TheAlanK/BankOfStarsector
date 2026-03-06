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
        "Elite financing for faction-level operations. Excellent credit required.");

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

    public float getMaxAmountForScore(int creditScore) {
        if (creditScore < minCreditScore) return 0f;
        float scoreRatio = Math.min(1f, (creditScore - minCreditScore) / 200f);
        return maxAmount * (0.5f + 0.5f * scoreRatio);
    }
}
