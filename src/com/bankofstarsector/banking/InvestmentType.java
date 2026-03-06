package com.bankofstarsector.banking;

public enum InvestmentType {

    SAVINGS("Savings Account", 0.01f, 0.005f, 0, 10000f,
        "Low risk, low return. Withdraw anytime."),
    BONDS("Government Bonds", 0.025f, 0.01f, 3, 50000f,
        "Moderate returns backed by faction guarantees. 3-month lock."),
    COMMODITIES("Commodity Futures", 0.05f, 0.04f, 6, 100000f,
        "High volatility tied to sector trade conditions. 6-month lock."),
    VENTURE("Venture Fund", 0.08f, 0.06f, 12, 250000f,
        "High risk, high reward. Capital locked for 12 months."),
    MILITARY("Military Contract", 0.04f, 0.02f, 6, 100000f,
        "Returns increase during wartime. 6-month lock.");

    public final String displayName;
    public final float baseMonthlyReturn;
    public final float volatility;
    public final int lockMonths;
    public final float minInvestment;
    public final String description;

    InvestmentType(String displayName, float baseMonthlyReturn, float volatility,
                   int lockMonths, float minInvestment, String description) {
        this.displayName = displayName;
        this.baseMonthlyReturn = baseMonthlyReturn;
        this.volatility = volatility;
        this.lockMonths = lockMonths;
        this.minInvestment = minInvestment;
        this.description = description;
    }
}
