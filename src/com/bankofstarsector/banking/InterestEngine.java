package com.bankofstarsector.banking;

import com.bankofstarsector.compat.NexerelinCompat;
import com.bankofstarsector.core.BankSettings;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

public class InterestEngine implements Serializable {

    private static final long serialVersionUID = 1L;
    private Random random = new Random();

    /** Factions whose permanent hostility says nothing about the sector's political climate. */
    private static final Set<String> NOT_POLITICAL = new HashSet<String>();
    static {
        String[] ids = {"pirates", "luddic_path", "remnant", "derelict", "omega", "threat", "dweller",
            "neutral", "poor", "scavengers", "mercenary", "sleeper", "player"};
        for (String id : ids) NOT_POLITICAL.add(id);
    }

    /** Loan rate: base rate, raised by war, scaled by the credit bracket shown in the terminal. */
    public float calculateEffectiveLoanRate(LoanType type, int creditScore) {
        // Secured by its own funds: one fixed rate, whatever the score or the wars.
        if (type.isBuilder()) return com.bankofstarsector.core.BankSettings.BUILDER_RATE;
        float baseRate = type.getBaseRate();
        float warSurcharge = getWarSurcharge();
        float bracketMod = CreditScoreManager.rateModifierFor(creditScore);
        float disruption = getMarketDisruptionModifier(); // disrupted industry makes credit dearer
        return baseRate * (1f + warSurcharge) * (1f + bracketMod) * (1f + disruption);
    }

    /** Government bonds yield more when sovereign borrowing is high (see AssetSeizureManager). */
    public float getSovereignYieldBonus(float totalSovereignDebt) {
        return Math.min(0.01f, totalSovereignDebt / 50_000_000f * 0.01f);
    }

    public float calculateOverdueRate(float baseRate, int monthsOverdue) {
        float penalty = BankSettings.OVERDUE_PENALTY_PER_MONTH * monthsOverdue;
        return baseRate * (1f + penalty);
    }

    public float calculateInvestmentReturn(BankAccount investment) {
        if (investment.investmentType == null) return 0f;
        ensureRandom();

        float baseReturn = investment.investmentType.baseMonthlyReturn;
        float vol = investment.investmentType.volatility;
        float disruption = getMarketDisruptionModifier();

        // Military contracts benefit from war
        float warBonus = 0f;
        if (investment.investmentType == InvestmentType.MILITARY) {
            warBonus = getWarSurcharge() * 0.5f;
        }
        // Commodity futures follow market disruption the other way: scarcity spikes prices
        if (investment.investmentType == InvestmentType.COMMODITIES) {
            disruption = -disruption;
        }
        // Bonds are sovereign debt: more borrowing, higher coupons
        if (investment.investmentType == InvestmentType.BONDS) {
            float total = 0f;
            for (Float d : com.bankofstarsector.core.BankData.get().getAssetSeizureManager()
                    .getAllFactionDebts().values()) total += d;
            warBonus += getSovereignYieldBonus(total);
        }

        float volatilityRoll = (random.nextFloat() * 2f - 1f) * vol;
        float effectiveReturn = (baseReturn + warBonus) * (1f - disruption) + volatilityRoll;

        return investment.currentValue * effectiveReturn;
    }

    /** +5% per war between major political factions (dynamic with Nexerelin), capped. */
    public float getWarSurcharge() {
        int warCount = getWarCount();
        if (warCount <= 0) return 0f;
        return Math.min(BankSettings.WAR_SURCHARGE, warCount * BankSettings.WAR_SURCHARGE_PER_WAR);
    }

    /** Political factions that hold real territory (a size 4+ market), excluding the PBC itself. */
    public List<FactionAPI> getMajorFactions() {
        List<FactionAPI> majors = new ArrayList<FactionAPI>();
        Set<String> seen = new HashSet<String>();
        for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
            if (market.isHidden() || market.getSize() < 4) continue;
            FactionAPI f = market.getFaction();
            if (f == null || f.isPlayerFaction() || NOT_POLITICAL.contains(f.getId()) || "pbc".equals(f.getId())) continue;
            if (!NexerelinCompat.isFactionAlive(f.getId())) continue;
            if (seen.add(f.getId())) majors.add(f);
        }
        return majors;
    }

    /** Counts hostile pairs among factions that hold real territory. No reflection needed. */
    public int getWarCount() {
        List<FactionAPI> majors = getMajorFactions();
        int wars = 0;
        for (int i = 0; i < majors.size(); i++) {
            for (int k = i + 1; k < majors.size(); k++) {
                if (majors.get(i).isHostileTo(majors.get(k))) wars++;
            }
        }
        return wars;
    }

    public float getMarketDisruptionModifier() {
        int totalMarkets = 0;
        int disruptedMarkets = 0;
        for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
            if (market.isHidden()) continue;
            totalMarkets++;
            // There is no "disrupted" market condition in vanilla; disruption lives on industries.
            for (com.fs.starfarer.api.campaign.econ.Industry ind : market.getIndustries()) {
                if (ind.isDisrupted()) {
                    disruptedMarkets++;
                    break;
                }
            }
        }
        if (totalMarkets == 0) return 0f;
        float disruptionRatio = (float) disruptedMarkets / totalMarkets;
        return disruptionRatio * BankSettings.MARKET_DISRUPTION_RATE_MODIFIER;
    }

    private void ensureRandom() {
        if (random == null) random = new Random();
    }
}
