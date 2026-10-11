package com.bankofstarsector.banking;

import com.bankofstarsector.core.BankSettings;

/**
 * Investment products. Since 0.3.2 their returns follow real long-run asset classes (annual figures,
 * converted to the game's monthly cycle):
 *
 *   SAVINGS      3.0%/yr, ~no risk     3-month T-bills 1928-2025: 3.4%/yr (Damodaran, NYU Stern);
 *                                      high-yield savings ~4% APY, US national average ~0.4% (2025)
 *   BONDS        4.5%/yr, 7.9% vol     10-year US Treasury 1928-2025: 4.5%/yr, 7.9% s.d.
 *   COMMODITIES  4.0%/yr, 18% vol      commodity futures indices: low single-digit total returns,
 *                                      14-23% annualized volatility
 *   VENTURE      12%/yr, 35% vol       VC pooled long-run net IRRs ~10-15% with huge dispersion;
 *                                      US small caps 1928-2025: 12.0%/yr, 38% s.d.
 *   MILITARY     9.0%/yr, 18% vol      defense contractors trade like equities (S&P 500 1928-2025:
 *                                      10.0%/yr, 19.4% s.d.), with gains when wars break out
 *
 * The enum's fields are not saved (saves store enum constants by name), so they can change freely.
 */
public enum InvestmentType {

    SAVINGS("Savings Account", 0.030f, 0.003f, 0, 10000f,
        "Low risk, low return. Withdraw anytime."),
    BONDS("Government Bonds", 0.045f, 0.079f, 3, 50000f,
        "Moderate returns backed by faction guarantees. 3-month lock."),
    COMMODITIES("Commodity Futures", 0.040f, 0.18f, 6, 100000f,
        "High volatility tied to sector trade conditions. 6-month lock."),
    VENTURE("Venture Fund", 0.12f, 0.35f, 12, 250000f,
        "High risk, high reward. Capital locked for 12 months."),
    MILITARY("Military Contract", 0.090f, 0.18f, 6, 100000f,
        "Returns increase during wartime. 6-month lock.");

    public final String displayName;
    /** Expected compound return per year (before the sector modifiers and the return multiplier). */
    public final float annualReturn;
    /** Standard deviation of yearly returns. */
    public final float annualVolatility;
    public final int lockMonths;
    public final float minInvestment;
    public final String description;

    InvestmentType(String displayName, float annualReturn, float annualVolatility,
                   int lockMonths, float minInvestment, String description) {
        this.displayName = displayName;
        this.annualReturn = annualReturn;
        this.annualVolatility = annualVolatility;
        this.lockMonths = lockMonths;
        this.minInvestment = minInvestment;
        this.description = description;
    }

    /** Expected annual return with the player's multiplier (settings), before sector modifiers. */
    public float expectedAnnualReturn() {
        return annualReturn * BankSettings.INVESTMENT_RETURN_MULT;
    }

    /** Monthly equivalent of the expected annual return: (1 + r)^(1/12) - 1. */
    public float expectedMonthlyReturn() {
        return toMonthly(expectedAnnualReturn());
    }

    /** Standard deviation of one month's return: annual volatility / sqrt(12). */
    public float monthlyVolatility() {
        return annualVolatility / (float) Math.sqrt(12.0);
    }

    public static float toMonthly(float annual) {
        return (float) (Math.pow(1.0 + annual, 1.0 / 12.0) - 1.0);
    }

    /** Localized name (see data/strings). The English field above stays for save compatibility. */
    public String getDisplayName() {
        return com.bankofstarsector.core.Str.get("invest." + name() + ".name");
    }

    public String getDescription() {
        return com.bankofstarsector.core.Str.get("invest." + name() + ".desc");
    }
}
