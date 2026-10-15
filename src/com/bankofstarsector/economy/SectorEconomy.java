package com.bankofstarsector.economy;

import com.bankofstarsector.banking.BankAccount;
import com.bankofstarsector.banking.CreditScoreManager;
import com.bankofstarsector.banking.InvestmentType;
import com.bankofstarsector.collection.AssetSeizureManager;
import com.bankofstarsector.core.BankData;
import com.bankofstarsector.core.BankSettings;
import com.bankofstarsector.core.Str;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Real money flows (0.4.0): every credit an investment earns has a payer in the Sector.
 *
 * Once a month, before investments are paid, one pass over the Sector's markets updates:
 *   - each major faction's treasury: it collects its markets' net income, pays the extra cost of its
 *     wars and the interest on its debt to the Confederation, borrows when short, repays when flush,
 *     and defaults when its debt outgrows its economy (or it loses all its markets);
 *   - each faction's bond yield: base + debt-to-economy spread + war and instability premiums;
 *   - a commodity price index over the whole Sector (fuel, supplies, metals, rare metals, volatiles,
 *     organics, food, heavy machinery);
 *   - the venture portfolio: stakes in growing NPC markets (sizes 3-5), and their earnings;
 *   - the Sector's military spending (a peacetime share of the economy plus the cost of wars).
 *
 * Then {@link #investmentReturn} pays each investment from its source and records the payer in the
 * {@link PbcLedger}: savings from the PBC's loan income, bonds from the issuing faction's treasury (plus
 * price changes when its yield moves, minus a haircut if it defaults), commodity futures from the price
 * index (plus interest on the collateral), venture stakes from the companies' earnings and growth (minus
 * write-offs), military contracts from the factions' military spending. Management fees go to the PBC.
 */
public class SectorEconomy implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = Logger.getLogger(SectorEconomy.class);
    public static final String[] BASKET = {"fuel", "supplies", "metals", "rare_metals", "volatiles", "organics", "food", "heavy_machinery"};
    /** Debts inherited from saves before 0.4.0 are restructured to at most this many years of gross income. */
    private static final float LEGACY_DEBT_CAP = 0.5f;
    /** Pseudo-faction id for the diversified sovereign bond fund (bonds bought without choosing a faction). */
    public static final String BOND_FUND = "*";

    private Map<String, FactionTreasury> treasuries;
    private PbcLedger ledger;
    private float commodityIndex, previousCommodityIndex;
    private float militarySpending, militaryEma, previousMilitaryEma;
    private float ventureIncome, ventureEma, previousVentureEma, ventureLostShare;
    /** Venture portfolio: market id -> owner faction when the stake was taken. */
    private Map<String, String> venturePortfolio;
    private int months;
    /** Timestamp of the last month end processed (defaults are "this month" when equal). */
    private long lastUpdateTs;

    private void ensure() {
        if (treasuries == null) treasuries = new LinkedHashMap<String, FactionTreasury>();
        if (ledger == null) {
            ledger = new PbcLedger();
            ledger.reserves = BankSettings.PBC_STARTING_RESERVES;
        }
        if (venturePortfolio == null) venturePortfolio = new LinkedHashMap<String, String>();
    }

    public PbcLedger getLedger() {
        ensure();
        return ledger;
    }

    public Map<String, FactionTreasury> getTreasuries() {
        ensure();
        return Collections.unmodifiableMap(treasuries);
    }

    public FactionTreasury getTreasury(String factionId) {
        ensure();
        return treasuries.get(factionId);
    }

    public boolean isReady() {
        return months > 0;
    }

    public float getCommodityIndex() { return commodityIndex; }
    public float getCommodityIndexChange() { return previousCommodityIndex > 0f ? commodityIndex / previousCommodityIndex - 1f : 0f; }
    public float getMilitarySpending() { return militarySpending; }
    public float getVentureIncome() { return ventureIncome; }
    public int getVentureStakes() { return venturePortfolio == null ? 0 : venturePortfolio.size(); }

    /** Start of a month end: clear the month's figures (the PBC's loan interest is recorded during billing). */
    public void beginMonth() {
        ensure();
        ledger.startMonth();
    }

    /** End of a month end: book the month into the PBC's reserves. */
    public void endMonth() {
        ensure();
        ledger.closeMonth();
    }

    /** The PBC lent this much interest to the player this month (recorded while billing loans). */
    public void recordLoanInterest(float interest) {
        ensure();
        if (interest > 0f) ledger.loanInterestIn += interest;
    }

    // ------------------------------------------------------------------ monthly update

    /** One pass over the Sector's markets; replaces the old random sovereign debt simulation. */
    public void monthlyUpdate(BankData data) {
        ensure();
        AssetSeizureManager debts = data.getAssetSeizureManager();
        List<FactionAPI> majors = data.getInterestEngine().getMajorFactions();
        Set<String> majorIds = new HashSet<String>();
        for (FactionAPI f : majors) majorIds.add(f.getId());

        Map<String, float[]> byFaction = new HashMap<String, float[]>(); // net, gross, stability sum, markets
        double priceSum = 0;
        int priceSamples = 0;
        Map<String, MarketAPI> marketsById = new HashMap<String, MarketAPI>();
        List<MarketAPI> ventureCandidates = new ArrayList<MarketAPI>();
        for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
            if (m.isHidden()) continue;
            marketsById.put(m.getId(), m);
            for (String c : BASKET) {
                priceSum += m.getSupplyPrice(c, 1, false);
                priceSamples++;
            }
            String fid = m.getFactionId();
            if (!majorIds.contains(fid)) continue;
            float[] a = byFaction.get(fid);
            if (a == null) byFaction.put(fid, a = new float[4]);
            a[0] += m.getNetIncome();
            a[1] += Math.max(0f, m.getGrossIncome());
            a[2] += m.getStabilityValue();
            a[3] += 1f;
            if (m.getSize() >= 3 && m.getSize() <= 5 && m.getNetIncome() > 0f) ventureCandidates.add(m);
        }

        // Commodity price index.
        previousCommodityIndex = commodityIndex;
        commodityIndex = priceSamples > 0 ? (float) (priceSum / priceSamples) : 0f;
        if (previousCommodityIndex <= 0f) previousCommodityIndex = commodityIndex;

        // Treasuries and the Sector's military spending.
        long now = Global.getSector().getClock().getTimestamp();
        float spending = 0f;
        float defaultedDebt = 0f;
        for (FactionAPI f : majors) {
            String fid = f.getId();
            float[] a = byFaction.get(fid);
            if (a == null) continue;
            FactionTreasury t = treasuries.get(fid);
            boolean fresh = t == null;
            if (fresh) treasuries.put(fid, t = new FactionTreasury(fid));
            int wars = 0;
            for (FactionAPI other : majors) if (other != f && f.isHostileTo(other)) wars++;
            t.wars = wars;
            t.income = a[0];
            t.gross = a[1];
            t.markets = (int) a[3];
            t.stability = a[3] > 0f ? a[2] / a[3] : 0f;
            t.warCost = t.gross * BankSettings.WAR_COST_PER_WAR * wars;
            t.militarySpending = t.gross * BankSettings.MILITARY_BASE_SHARE + t.warCost;
            spending += t.militarySpending;
            if (fresh) t.balance = 3f * Math.max(0f, t.income); // a starting cushion: three months of surplus

            float debt = debts.getFactionDebt(fid);
            if (months == 0 && debt > LEGACY_DEBT_CAP * 12f * t.gross) {
                // Saves from before 0.4.0 carry debts from the old random simulation. The first real month
                // quietly restructures them to what the faction's economy can carry: no default, no haircut.
                float forgiven = debt - LEGACY_DEBT_CAP * 12f * t.gross;
                debts.simulateFactionDebt(fid, -forgiven);
                debt = debts.getFactionDebt(fid);
                log.info("BOS: restructured " + forgiven + " of legacy sovereign debt for " + fid);
            }
            t.previousYield = t.yield > 0f ? t.yield : yieldFor(t, debt, now);
            t.interest = debt * t.previousYield / 12f;
            ledger.sovereignInterestIn += t.interest;
            t.balance += t.income - t.warCost - t.interest;
            t.couponsPaid = 0f; // this month's coupons are paid after the update (payCoupon)
            t.borrowed = t.repaid = 0f;
            if (t.balance < 0f) {
                t.borrowed = -t.balance;
                debts.simulateFactionDebt(fid, t.borrowed);
                t.balance = 0f;
            } else {
                float buffer = 3f * Math.max(1f, Math.abs(t.income));
                if (debt > 0f && t.balance > buffer) {
                    t.repaid = Math.min(debt, t.balance - buffer);
                    debts.simulateFactionDebt(fid, -t.repaid);
                    t.balance -= t.repaid;
                }
            }
            debt = debts.getFactionDebt(fid);
            // Default: more debt than its economy can carry.
            float annualGross = 12f * t.gross;
            if (debt > 0f && annualGross > 0f && debt > BankSettings.SOVEREIGN_DEBT_CEILING * annualGross) {
                defaultedDebt += defaultFaction(data, f, debt, now);
                debt = debts.getFactionDebt(fid);
            }
            t.yield = yieldFor(t, debt, now);
        }
        // A faction that lost all its markets defaults on whatever it owes.
        for (Map.Entry<String, Float> e : new ArrayList<Map.Entry<String, Float>>(debts.getAllFactionDebts().entrySet())) {
            if (byFaction.containsKey(e.getKey())) continue;
            FactionAPI f = Global.getSector().getFaction(e.getKey());
            if (f == null) continue;
            if (!PoliticalFactions.isPolitical(f)) {
                // Not a state the desk deals with (a modded swarm or pirate band that older versions counted, or one
                // a player moved off the desk in the settings): its book is closed quietly, like legacy debt.
                log.info("BOS: closed the sovereign book of non-political faction " + e.getKey() + " (" + e.getValue() + " owed)");
                debts.simulateFactionDebt(e.getKey(), -debts.getFactionDebt(e.getKey()));
                continue;
            }
            defaultedDebt += defaultFaction(data, f, e.getValue(), now);
            debts.simulateFactionDebt(e.getKey(), -debts.getFactionDebt(e.getKey()));
        }
        for (java.util.Iterator<String> it = treasuries.keySet().iterator(); it.hasNext(); ) {
            FactionAPI f = Global.getSector().getFaction(it.next());
            if (f != null && !PoliticalFactions.isPolitical(f)) it.remove();
        }

        previousMilitaryEma = militaryEma > 0f ? militaryEma : spending;
        militarySpending = spending;
        militaryEma = ema(previousMilitaryEma, spending);

        updateVenturePortfolio(marketsById, ventureCandidates);
        lastUpdateTs = now;
        months++;
        if (defaultedDebt > 0f) log.info("BOS: sovereign defaults this month on " + defaultedDebt + " credits of debt");
    }

    private static float ema(float previous, float value) {
        return previous + (value - previous) / BankSettings.ECONOMY_EMA_MONTHS;
    }

    /** Annual yield: base + debt-to-economy spread + war, instability and recent-default premiums. */
    private float yieldFor(FactionTreasury t, float debt, long now) {
        float base = InvestmentType.BONDS.expectedAnnualReturn();
        float annualGross = 12f * t.gross;
        float spread = annualGross > 0f ? BankSettings.SOVEREIGN_SPREAD_PER_DEBT_RATIO * debt / annualGross : 0.15f;
        spread += BankSettings.SOVEREIGN_WAR_PREMIUM * t.wars;
        if (t.markets > 0 && t.stability < 5f) spread += 0.01f;
        if (t.defaultedTs != 0L && Global.getSector().getClock().getElapsedDaysSince(t.defaultedTs) < 365f) {
            spread += BankSettings.SOVEREIGN_DEFAULT_PREMIUM;
        }
        return Math.max(0.01f, Math.min(0.30f, base + Math.min(0.15f, spread)));
    }

    /** Writes off part of a faction's debt; returns the debt that defaulted. Bondholders lose the haircut. */
    private float defaultFaction(BankData data, FactionAPI f, float debt, long now) {
        FactionTreasury t = treasuries.get(f.getId());
        if (t == null) treasuries.put(f.getId(), t = new FactionTreasury(f.getId()));
        t.defaultedTs = now;
        float writtenOff = debt * BankSettings.SOVEREIGN_HAIRCUT;
        data.getAssetSeizureManager().simulateFactionDebt(f.getId(), -writtenOff);
        ledger.writeOffs += writtenOff;
        data.addTransaction("SOVEREIGN", 0, Str.f("txd.sovereign", f.getDisplayName(), (int) (debt / 1000f)));
        Global.getSector().getCampaignUI().addMessage(Str.f("msg.sovereignDefault", f.getDisplayName()), Misc.getNegativeHighlightColor());
        return debt;
    }

    /** Keeps up to VENTURE_PORTFOLIO_SIZE stakes in growing NPC markets; lost markets are written off. */
    private void updateVenturePortfolio(Map<String, MarketAPI> marketsById, List<MarketAPI> candidates) {
        int lost = 0, before = venturePortfolio.size();
        for (java.util.Iterator<Map.Entry<String, String>> it = venturePortfolio.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, String> e = it.next();
            MarketAPI m = marketsById.get(e.getKey());
            if (m == null || !m.isInEconomy() || m.hasCondition("decivilized") || !e.getValue().equals(m.getFactionId())) {
                it.remove();
                lost++;
            }
        }
        ventureLostShare = before > 0 ? (float) lost / before : 0f;
        Collections.sort(candidates, new Comparator<MarketAPI>() {
            public int compare(MarketAPI a, MarketAPI b) { return a.getId().compareTo(b.getId()); }
        });
        for (MarketAPI m : candidates) {
            if (venturePortfolio.size() >= BankSettings.VENTURE_PORTFOLIO_SIZE) break;
            if (!venturePortfolio.containsKey(m.getId())) venturePortfolio.put(m.getId(), m.getFactionId());
        }
        float income = 0f;
        for (String id : venturePortfolio.keySet()) {
            MarketAPI m = marketsById.get(id);
            if (m != null) income += Math.max(0f, m.getNetIncome());
        }
        previousVentureEma = ventureEma > 0f ? ventureEma : income;
        ventureIncome = income;
        ventureEma = ema(previousVentureEma, income);
    }

    // ------------------------------------------------------------------ yields and expected returns

    /** Current annual yield of a faction's bonds, or of the diversified fund. */
    public float bondYield(String factionId) {
        ensure();
        if (factionId != null && !BOND_FUND.equals(factionId)) {
            FactionTreasury t = treasuries.get(factionId);
            return t != null && t.yield > 0f ? t.yield : InvestmentType.BONDS.expectedAnnualReturn();
        }
        float sum = 0f, weight = 0f;
        for (FactionTreasury t : treasuries.values()) {
            if (t.gross <= 0f || t.yield <= 0f) continue;
            sum += t.yield * t.gross;
            weight += t.gross;
        }
        return weight > 0f ? sum / weight : InvestmentType.BONDS.expectedAnnualReturn();
    }

    /** What the PBC pays on deposits: the policy rate, nudged by the yields on its sovereign book. */
    public float depositRate() {
        float base = InvestmentType.SAVINGS.expectedAnnualReturn();
        float sovereignSpread = bondYield(BOND_FUND) - InvestmentType.BONDS.expectedAnnualReturn();
        return Math.max(0.005f, Math.min(0.06f, base + 0.5f * sovereignSpread));
    }

    /** Expected annual return of a product right now (before the relationship bonus), for quotes and screens. */
    public float expectedAnnual(InvestmentType type, String bondFactionId) {
        ensure();
        switch (type) {
            case SAVINGS: return depositRate();
            case BONDS: return bondYield(bondFactionId);
            // Interest on the collateral; the price index adds or subtracts its own moves.
            case COMMODITIES: return InvestmentType.SAVINGS.expectedAnnualReturn();
            case VENTURE: return InvestmentType.VENTURE.expectedAnnualReturn() * ratio(ventureIncome, ventureEma) - BankSettings.MANAGED_FEE;
            case MILITARY: return InvestmentType.MILITARY.expectedAnnualReturn() * ratio(militarySpending, militaryEma) - BankSettings.MANAGED_FEE;
            default: return 0f;
        }
    }

    private static float ratio(float value, float ema) {
        return ema > 0f ? Math.max(0f, Math.min(3f, value / ema)) : 1f;
    }

    // ------------------------------------------------------------------ paying investments

    /**
     * This month's return of one investment, paid from its source (see the class comment). Records the
     * payer in the ledger and a short description on the account. Returns the credits to add.
     */
    public float investmentReturn(BankAccount inv) {
        ensure();
        float v = inv.currentValue;
        if (v <= 0f || inv.investmentType == null) return 0f;
        float r = 0f;
        String source;
        String arg = "";
        switch (inv.investmentType) {
            case SAVINGS: {
                float rate = depositRate();
                r = v * InvestmentType.toMonthly(rate);
                ledger.depositInterestOut += r;
                ledger.paidByPbc += r;
                source = "flow.savings";
                arg = pct(rate);
                break;
            }
            case BONDS: {
                String fid = inv.bondFactionId != null ? inv.bondFactionId : BOND_FUND;
                if (inv.bondYield <= 0f) inv.bondYield = bondYield(fid); // older saves: coupon at today's yield
                float coupon = v * inv.bondYield / 12f;
                float yieldChange = BOND_FUND.equals(fid) ? fundYieldChange() : treasuryYieldChange(fid);
                float price = clamp(-BankSettings.BOND_DURATION * yieldChange, -0.25f, 0.25f) * v;
                float loss = defaultedThisMonth(fid) * BankSettings.SOVEREIGN_HAIRCUT * (v + price);
                payCoupon(fid, coupon);
                ledger.paidByTreasuries += coupon;
                ledger.bondPriceChanges += price;
                ledger.lostToDefaults += loss;
                r = coupon + price - loss;
                source = loss > 0f ? "flow.bondsDefault" : "flow.bonds";
                arg = issuerName(fid);
                break;
            }
            case COMMODITIES: {
                float move = clamp(getCommodityIndexChange(), -0.3f, 0.3f) * v;
                float collateral = v * InvestmentType.toMonthly(InvestmentType.SAVINGS.expectedAnnualReturn());
                ledger.paidByCommodityMarkets += move;
                ledger.paidByPbc += collateral;
                r = move + collateral;
                source = "flow.commodities";
                arg = String.format("%+.1f%%", getCommodityIndexChange() * 100f);
                break;
            }
            case VENTURE: {
                float earnings = v * InvestmentType.toMonthly(InvestmentType.VENTURE.expectedAnnualReturn()) * ratio(ventureIncome, ventureEma);
                float growth = clamp(previousVentureEma > 0f ? ventureEma / previousVentureEma - 1f : 0f, -0.3f, 0.3f) * v;
                float writeOff = ventureLostShare * v;
                float fee = v * BankSettings.MANAGED_FEE / 12f;
                ledger.paidByCompanies += earnings + growth;
                ledger.lostToDefaults += writeOff;
                ledger.feesIn += fee;
                r = earnings + growth - writeOff - fee;
                source = writeOff > 0f ? "flow.ventureLoss" : "flow.venture";
                arg = "" + getVentureStakes();
                break;
            }
            case MILITARY: {
                float earnings = v * InvestmentType.toMonthly(InvestmentType.MILITARY.expectedAnnualReturn()) * ratio(militarySpending, militaryEma);
                float growth = clamp(previousMilitaryEma > 0f ? militaryEma / previousMilitaryEma - 1f : 0f, -0.3f, 0.3f) * v;
                float fee = v * BankSettings.MANAGED_FEE / 12f;
                ledger.paidByMilitarySpending += earnings + growth;
                ledger.feesIn += fee;
                r = earnings + growth - fee;
                source = "flow.military";
                arg = Misc.getDGSCredits(militarySpending);
                break;
            }
            default:
                source = "";
        }
        // Relationship bonus: the PBC rebates part of its margin to good customers.
        float rebate = v * BankSettings.RELATIONSHIP_RETURN_BONUS * CreditScoreManager.pbcRelationship() / 12f;
        if (rebate != 0f) {
            ledger.rebatesOut += rebate;
            ledger.paidByPbc += rebate;
            r += rebate;
        }
        inv.lastReturn = r;
        inv.lastSourceKey = source;
        inv.lastSourceArg = arg;
        ledger.totalPaidToPlayer += r;
        return r;
    }

    private float treasuryYieldChange(String fid) {
        FactionTreasury t = treasuries.get(fid);
        return t == null || t.previousYield <= 0f ? 0f : t.yield - t.previousYield;
    }

    private float fundYieldChange() {
        float sum = 0f, weight = 0f;
        for (FactionTreasury t : treasuries.values()) {
            if (t.gross <= 0f || t.previousYield <= 0f) continue;
            sum += (t.yield - t.previousYield) * t.gross;
            weight += t.gross;
        }
        return weight > 0f ? sum / weight : 0f;
    }

    /** 1 if the issuer defaulted at this month end, the defaulted share of the fund for the fund, else 0. */
    private float defaultedThisMonth(String fid) {
        if (!BOND_FUND.equals(fid)) {
            FactionTreasury t = treasuries.get(fid);
            return t != null && t.defaultedTs != 0L && t.defaultedTs == lastUpdateTs ? 1f : 0f;
        }
        float defaulted = 0f, weight = 0f;
        for (FactionTreasury t : treasuries.values()) {
            weight += Math.max(0f, t.gross);
            if (t.defaultedTs != 0L && t.defaultedTs == lastUpdateTs) defaulted += Math.max(0f, t.gross);
        }
        return weight > 0f ? defaulted / weight : 0f;
    }

    /**
     * Coupons are paid out of the issuer's treasury right away (the fund: out of every treasury, by economic
     * weight). A treasury that goes negative borrows the difference at the next month end.
     */
    private void payCoupon(String fid, float coupon) {
        if (!BOND_FUND.equals(fid)) {
            FactionTreasury t = treasuries.get(fid);
            if (t != null) {
                t.couponsPaid += coupon;
                t.balance -= coupon;
            }
            return;
        }
        float weight = 0f;
        for (FactionTreasury t : treasuries.values()) weight += Math.max(0f, t.gross);
        if (weight <= 0f) return;
        for (FactionTreasury t : treasuries.values()) {
            float share = coupon * Math.max(0f, t.gross) / weight;
            t.couponsPaid += share;
            t.balance -= share;
        }
    }

    private static String issuerName(String fid) {
        if (BOND_FUND.equals(fid)) return Str.get("flow.bondFund");
        FactionAPI f = Global.getSector().getFaction(fid);
        return f != null ? f.getDisplayName() : fid;
    }

    private static String pct(float f) {
        return String.format("%.1f%%", f * 100f);
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
