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

    // Sector-wide numbers (war count, market disruption) scan every market in the sector, which is
    // hundreds with big mod lists. They change slowly, so they are computed at most once per game day.
    // Transient: not saved, recomputed after a load.
    private transient long sectorCacheTs;
    private transient boolean sectorCacheValid;
    private transient int cachedWarCount;
    private transient float cachedDisruption;

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
        return baseRate * (1f + warSurcharge) * (1f + bracketMod) * (1f + disruption) * relationshipPriceFactor();
    }

    /** Relationship pricing: up to RELATIONSHIP_RATE_DISCOUNT cheaper for a friend of the bank, dearer for an enemy. */
    public static float relationshipPriceFactor() {
        return 1f - BankSettings.RELATIONSHIP_RATE_DISCOUNT * CreditScoreManager.pbcRelationship();
    }

    /** Government bonds yield more when sovereign borrowing is high (see AssetSeizureManager): up to +2%/yr. */
    public float getSovereignYieldBonus(float totalSovereignDebt) {
        return Math.min(0.02f, totalSovereignDebt / 50_000_000f * 0.02f);
    }

    public float calculateOverdueRate(float baseRate, int monthsOverdue) {
        float penalty = BankSettings.OVERDUE_PENALTY_PER_MONTH * monthsOverdue;
        return baseRate * (1f + penalty);
    }

    /**
     * One month's return of an investment, in credits. Each product has a real-world expected annual
     * return and volatility (see InvestmentType); sector conditions shift the expected return:
     * military contracts earn up to +5%/yr at the war surcharge cap, government bonds up to +2%/yr when
     * sovereign debt is high, commodity futures gain from shortages (+ the disruption rate per year)
     * while disruption drags the other products down. The month's draw is normal around that mean, with
     * the mean raised by half the variance so the compound (geometric) return matches the target.
     * Savings never lose money.
     */
    public float calculateInvestmentReturn(BankAccount investment) {
        if (investment.investmentType == null) return 0f;
        ensureRandom();
        InvestmentType type = investment.investmentType;
        float annual = expectedAnnualReturn(type);
        float sd = type.monthlyVolatility();
        float mean = InvestmentType.toMonthly(annual) + sd * sd / 2f;
        float r = mean + (float) random.nextGaussian() * sd;
        if (type == InvestmentType.SAVINGS) r = Math.max(0f, r);
        return investment.currentValue * r;
    }

    /** Expected annual return of a product right now, with the sector's war, debt and disruption. */
    public float expectedAnnualReturn(InvestmentType type) {
        float annual = type.expectedAnnualReturn()
            + BankSettings.RELATIONSHIP_RETURN_BONUS * CreditScoreManager.pbcRelationship();
        float disruption = getMarketDisruptionModifier();
        switch (type) {
            case MILITARY:
                annual += getWarSurcharge() * BankSettings.INVESTMENT_WAR_BONUS;
                break;
            case BONDS:
                float total = 0f;
                for (Float d : com.bankofstarsector.core.BankData.get().getAssetSeizureManager()
                        .getAllFactionDebts().values()) total += d;
                annual += getSovereignYieldBonus(total);
                break;
            case COMMODITIES:
                // Scarcity spikes prices: shortages add to commodity returns instead of dragging them.
                return annual + disruption;
            default:
                break;
        }
        return annual * (1f - disruption);
    }

    /** Recomputes the cached sector numbers when a game day has passed since the last time. */
    private void refreshSectorCache() {
        long now = Global.getSector().getClock().getTimestamp();
        if (sectorCacheValid && Global.getSector().getClock().getElapsedDaysSince(sectorCacheTs) < 1f) return;
        cachedWarCount = countWars();
        cachedDisruption = computeDisruption();
        sectorCacheTs = now;
        sectorCacheValid = true;
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
            if (!seen.add(f.getId())) continue; // each faction is checked once, not once per market
            if (NexerelinCompat.isFactionAlive(f.getId())) majors.add(f);
        }
        return majors;
    }

    /** Hostile pairs among factions that hold real territory (cached per game day). */
    public int getWarCount() {
        refreshSectorCache();
        return cachedWarCount;
    }

    private int countWars() {
        List<FactionAPI> majors = getMajorFactions();
        int wars = 0;
        for (int i = 0; i < majors.size(); i++) {
            for (int k = i + 1; k < majors.size(); k++) {
                if (majors.get(i).isHostileTo(majors.get(k))) wars++;
            }
        }
        return wars;
    }

    /** Share of markets with disrupted industry, as a rate modifier (cached per game day). */
    public float getMarketDisruptionModifier() {
        refreshSectorCache();
        return cachedDisruption;
    }

    private float computeDisruption() {
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
