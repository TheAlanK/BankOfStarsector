package com.bankofstarsector.banking;

import com.bankofstarsector.compat.NexerelinCompat;
import com.bankofstarsector.core.BankSettings;
import com.fs.starfarer.api.Global;

import java.io.Serializable;
import java.util.Random;

public class InterestEngine implements Serializable {

    private static final long serialVersionUID = 1L;
    private Random random = new Random();

    public float calculateEffectiveLoanRate(LoanType type, int creditScore) {
        float baseRate = type.baseMonthlyRate;
        float warSurcharge = getWarSurcharge();
        float creditDiscount = getCreditScoreDiscount(creditScore);
        return baseRate * (1f + warSurcharge) * (1f - creditDiscount);
    }

    public float calculateOverdueRate(float baseRate, int monthsOverdue) {
        float penalty = BankSettings.OVERDUE_PENALTY_PER_MONTH * monthsOverdue;
        return baseRate * (1f + penalty);
    }

    public float calculateInvestmentReturn(BankAccount investment) {
        if (investment.investmentType == null) return 0f;

        float baseReturn = investment.investmentType.baseMonthlyReturn;
        float vol = investment.investmentType.volatility;
        float disruption = getMarketDisruptionModifier();

        // Military contracts benefit from war
        float warBonus = 0f;
        if (investment.investmentType == InvestmentType.MILITARY) {
            warBonus = getWarSurcharge() * 0.5f; // war increases military returns
        }

        float volatilityRoll = (random.nextFloat() * 2f - 1f) * vol;
        float effectiveReturn = (baseReturn + warBonus) * (1f - disruption) + volatilityRoll;

        return investment.currentValue * effectiveReturn;
    }

    public float getWarSurcharge() {
        if (!NexerelinCompat.isAvailable()) return 0f;
        int warCount = NexerelinCompat.getWarCount();
        if (warCount <= 0) return 0f;
        return Math.min(BankSettings.WAR_SURCHARGE, warCount * 0.05f);
    }

    public float getMarketDisruptionModifier() {
        // Count disrupted markets in the sector
        int totalMarkets = 0;
        int disruptedMarkets = 0;
        for (com.fs.starfarer.api.campaign.econ.MarketAPI market :
                Global.getSector().getEconomy().getMarketsCopy()) {
            totalMarkets++;
            if (market.hasCondition("disrupted")) {
                disruptedMarkets++;
            }
        }
        if (totalMarkets == 0) return 0f;
        float disruptionRatio = (float) disruptedMarkets / totalMarkets;
        return disruptionRatio * BankSettings.MARKET_DISRUPTION_RATE_MODIFIER;
    }

    public float getCreditScoreDiscount(int creditScore) {
        if (creditScore <= BankSettings.CREDIT_SCORE_MIN) return 0f;
        float scoreRange = BankSettings.CREDIT_SCORE_MAX - BankSettings.CREDIT_SCORE_MIN;
        float normalizedScore = (creditScore - BankSettings.CREDIT_SCORE_MIN) / scoreRange;
        return normalizedScore * BankSettings.MAX_CREDIT_DISCOUNT;
    }

    private void ensureRandom() {
        if (random == null) random = new Random();
    }
}
