package com.bankofstarsector.collection;

import com.bankofstarsector.banking.BankAccount;
import com.bankofstarsector.banking.ColonyAppraisal;
import com.bankofstarsector.banking.LoanManager;
import com.bankofstarsector.banking.LoanStatus;
import com.bankofstarsector.compat.NexerelinCompat;
import com.bankofstarsector.core.BankData;
import com.bankofstarsector.core.BankSettings;
import com.bankofstarsector.core.Str;
import com.bankofstarsector.intel.LoanIntelPlugin;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Colony-secured loans when they go wrong.
 *
 *   default            -> RECEIVERSHIP: the colony's whole net income goes to the bank every month,
 *                         and the colony suffers a stability penalty, until the loan is current again.
 *   still unpaid after FORECLOSURE_DELAY_DAYS, with Nexerelin
 *                      -> FORECLOSURE: the PBC turns hostile to the player and sends an invasion
 *                         against the colony (again every FORECLOSURE_RETRY_DAYS if it fails).
 *   PBC holds the colony
 *                      -> AUCTION: the colony is sold to a major faction (the PBC never keeps
 *                         territory); the price pays the loan, any surplus goes to the player, any
 *                         shortfall stays owed. Relations go back to what they were.
 *
 * Paying the loan current lifts receivership and ends the foreclosure. Without Nexerelin there are no
 * invasions: receivership lasts until the loan is current or paid. Losing the colony in other ways:
 * abandoned or decivilized -> the whole balance falls due at once (default); captured by another
 * faction -> the loan simply becomes unsecured.
 */
public class ForeclosureManager implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = Logger.getLogger(ForeclosureManager.class);

    public static final String COND_LIEN = "bos_lien";
    public static final String COND_RECEIVERSHIP = "bos_receivership";
    public static final int STAGE_NONE = 0, STAGE_RECEIVERSHIP = 1, STAGE_FORECLOSURE = 2;

    private Map<String, Integer> stage;
    /** Days in receivership, then days since the last invasion was sent. */
    private Map<String, Integer> days;
    /** The PBC's relation to the player before the foreclosure war, restored afterwards. */
    private Map<String, Float> savedRelation;

    private void ensure() {
        if (stage == null) stage = new HashMap<String, Integer>();
        if (days == null) days = new HashMap<String, Integer>();
        if (savedRelation == null) savedRelation = new HashMap<String, Float>();
    }

    public int getStage(BankAccount loan) {
        ensure();
        Integer s = stage.get(loan.accountId);
        return s == null ? STAGE_NONE : s;
    }

    public int getDays(BankAccount loan) {
        ensure();
        Integer d = days.get(loan.accountId);
        return d == null ? 0 : d;
    }

    public static MarketAPI collateral(BankAccount loan) {
        if (loan.collateralMarketId == null) return null;
        return Global.getSector().getEconomy().getMarket(loan.collateralMarketId);
    }

    /** Signing: the lien is visible on the colony. */
    public void onLoanSigned(BankAccount loan, MarketAPI market) {
        if (!market.hasCondition(COND_LIEN)) market.addCondition(COND_LIEN);
    }

    /** The loan closed (paid off, settled or seized): the colony is free again. */
    public void onLoanClosed(BankAccount loan) {
        ensure();
        MarketAPI market = collateral(loan);
        if (market != null) {
            market.removeCondition(COND_LIEN);
            market.removeCondition(COND_RECEIVERSHIP);
        }
        endForeclosure(loan);
    }

    // ------------------------------------------------------------------ daily

    public void advanceDay(BankData data) {
        ensure();
        for (BankAccount loan : data.getLoanManager().getActiveLoans()) {
            if (!loan.loanType.isSecured() || loan.collateralMarketId == null) continue;
            MarketAPI market = collateral(loan);
            int s = getStage(loan);

            if (market != null && "pbc".equals(market.getFactionId())) {
                auction(data, loan, market);
                continue;
            }
            if (market == null || !market.isInEconomy() || market.hasCondition("decivilized")) {
                collateralAbandoned(data, loan, market);
                continue;
            }
            if (!market.isPlayerOwned()) {
                collateralCaptured(loan, market);
                continue;
            }

            if (loan.status == LoanStatus.ACTIVE) {
                if (s != STAGE_NONE) lift(loan, market);
                continue;
            }
            if (loan.status != LoanStatus.DEFAULTED) continue;

            if (s == STAGE_NONE) {
                startReceivership(loan, market);
                continue;
            }
            int d = getDays(loan) + 1;
            days.put(loan.accountId, d);
            if (s == STAGE_RECEIVERSHIP && d >= BankSettings.FORECLOSURE_DELAY_DAYS && NexerelinCompat.isAvailable()) {
                startForeclosure(loan, market);
            } else if (s == STAGE_FORECLOSURE && d >= BankSettings.FORECLOSURE_RETRY_DAYS) {
                // The last invasion failed or never arrived: send another.
                if (launchInvasion(market)) days.put(loan.accountId, 0);
            }
        }
    }

    private void startReceivership(BankAccount loan, MarketAPI market) {
        stage.put(loan.accountId, STAGE_RECEIVERSHIP);
        days.put(loan.accountId, 0);
        if (!market.hasCondition(COND_RECEIVERSHIP)) market.addCondition(COND_RECEIVERSHIP);
        String detail = NexerelinCompat.isAvailable()
            ? Str.f("notice.receivership.descNex", market.getName(), BankSettings.FORECLOSURE_DELAY_DAYS)
            : Str.f("notice.receivership.desc", market.getName());
        notice(loan, "notice.receivership.title", detail, 3);
        BankData.get().addTransaction("RECEIVERSHIP", 0f, Str.f("txd.receivership", market.getName()));
        log.info("BOS: " + market.getName() + " placed in receivership for " + loan.accountId);
    }

    private void startForeclosure(BankAccount loan, MarketAPI market) {
        FactionAPI pbc = Global.getSector().getFaction("pbc");
        if (pbc == null) return;
        if (!savedRelation.containsKey(loan.accountId)) savedRelation.put(loan.accountId, pbc.getRelationship(Factions.PLAYER));
        pbc.setRelationship(Factions.PLAYER, Math.min(pbc.getRelationship(Factions.PLAYER), BankSettings.FORECLOSURE_RELATION));
        stage.put(loan.accountId, STAGE_FORECLOSURE);
        days.put(loan.accountId, 0);
        boolean sent = launchInvasion(market);
        notice(loan, "notice.foreclosure.title", Str.f(sent ? "notice.foreclosure.desc" : "notice.foreclosure.descNoFleet",
            market.getName()), 4);
        BankData.get().addTransaction("FORECLOSURE", 0f, Str.f("txd.foreclosure", market.getName()));
        log.info("BOS: foreclosure on " + market.getName() + " (invasion sent: " + sent + ")");
    }

    /** From the PBC's largest market. Nexerelin only; false if it could not be sent. */
    private static boolean launchInvasion(MarketAPI target) {
        MarketAPI source = null;
        for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
            if ("pbc".equals(m.getFactionId()) && (source == null || m.getSize() > source.getSize())) source = m;
        }
        return source != null && NexerelinCompat.launchInvasion(source, target);
    }

    /** Loan current again: receivership lifted, war ended. */
    private void lift(BankAccount loan, MarketAPI market) {
        market.removeCondition(COND_RECEIVERSHIP);
        endForeclosure(loan);
        notice(loan, "notice.lifted.title", Str.f("notice.lifted.desc", market.getName()), 1);
        BankData.get().addTransaction("LIFTED", 0f, Str.f("txd.lifted", market.getName()));
    }

    private void endForeclosure(BankAccount loan) {
        ensure();
        Float rel = savedRelation.remove(loan.accountId);
        if (rel != null) {
            FactionAPI pbc = Global.getSector().getFaction("pbc");
            if (pbc != null) pbc.setRelationship(Factions.PLAYER, rel);
        }
        stage.remove(loan.accountId);
        days.remove(loan.accountId);
    }

    /** Abandoned or decivilized: nothing left to secure the loan, so all of it falls due now. */
    private void collateralAbandoned(BankData data, BankAccount loan, MarketAPI market) {
        String name = loan.collateralName != null ? loan.collateralName : "?";
        if (market != null) {
            market.removeCondition(COND_LIEN);
            market.removeCondition(COND_RECEIVERSHIP);
        }
        endForeclosure(loan);
        loan.collateralMarketId = null;
        if (loan.status != LoanStatus.DEFAULTED) {
            loan.amountPastDue = loan.remainingBalance;
            loan.currentBill = 0f;
            loan.status = LoanStatus.DEFAULTED;
            loan.daysOverdue = Math.max(loan.daysOverdue, BankSettings.DEFAULT_THRESHOLD_DAYS);
            data.getCreditScoreManager().onDefault(loan);
        }
        notice(loan, "notice.collateralLost.title", Str.f("notice.collateralLost.desc", name,
            LoanManager.formatCredits(loan.remainingBalance)), 4);
        data.addTransaction("ACCELERATED", 0f, Str.f("txd.accelerated", name));
    }

    /** Taken by another faction: the PBC has no claim left on it, the loan continues unsecured. */
    private void collateralCaptured(BankAccount loan, MarketAPI market) {
        market.removeCondition(COND_LIEN);
        market.removeCondition(COND_RECEIVERSHIP);
        endForeclosure(loan);
        loan.collateralMarketId = null;
        notice(loan, "notice.unsecured.title", Str.f("notice.unsecured.desc", market.getName()), 2);
    }

    // ------------------------------------------------------------------ auction

    /** One bid in an auction, for the record shown to the player. */
    public static final class Bid {
        public final FactionAPI faction;
        public final float amount;

        Bid(FactionAPI faction, float amount) {
            this.faction = faction;
            this.amount = amount;
        }
    }

    /**
     * The PBC holds the colony: sell it. Bidders are the major factions not hostile to the PBC; each bids
     * around the appraisal, more if it has territory nearby or good relations with the PBC. The highest
     * bidder wins and pays the second-highest bid (never under the reserve price). With no bidder, the
     * colony goes to the independents at the reserve price.
     */
    public void auction(BankData data, BankAccount loan, MarketAPI market) {
        ColonyAppraisal appraisal = ColonyAppraisal.of(market);
        List<Bid> bids = collectBids(market, appraisal.total, new Random());
        float reserve = appraisal.total * BankSettings.AUCTION_RESERVE_PCT;
        FactionAPI winner;
        float price;
        if (bids.isEmpty()) {
            winner = Global.getSector().getFaction(Factions.INDEPENDENT);
            price = reserve;
        } else {
            winner = bids.get(0).faction;
            price = Math.max(reserve, bids.size() > 1 ? bids.get(1).amount : reserve);
        }
        FactionAPI pbc = Global.getSector().getFaction("pbc");
        NexerelinCompat.transferMarket(market, winner, pbc);
        market.removeCondition(COND_LIEN);
        market.removeCondition(COND_RECEIVERSHIP);

        LoanManager lm = data.getLoanManager();
        float owed = loan.remainingBalance;
        float toLoan = Math.min(price, owed);
        float surplus = price - toLoan;
        String name = market.getName();
        loan.collateralMarketId = null; // no longer pledged, whatever is left is unsecured
        endForeclosure(loan);
        if (toLoan > 0f) lm.applyPayment(loan, toLoan, "SEIZURE");
        if (surplus > 1f) {
            Global.getSector().getPlayerFleet().getCargo().getCredits().add(surplus);
        }
        String winnerName = winner != null ? winner.getDisplayName() : "?";
        data.addTransaction("AUCTION", surplus, Str.f("txd.auction", name, winnerName, LoanManager.formatCredits(price)));
        String desc = Str.f("notice.auction.desc", name, winnerName, Misc.getDGSCredits(price),
            Misc.getDGSCredits(appraisal.total), Misc.getDGSCredits(toLoan),
            surplus > 1f ? Misc.getDGSCredits(surplus) : Misc.getDGSCredits(0f),
            Misc.getDGSCredits(Math.max(0f, owed - toLoan)), "" + bids.size());
        notice(loan, "notice.auction.title", desc, 4);
        log.info("BOS: auctioned " + name + " to " + winnerName + " for " + price + " (appraisal " + appraisal.total
            + ", " + bids.size() + " bids)");
    }

    /** Bids from the major factions, highest first. */
    public static List<Bid> collectBids(MarketAPI market, float appraisal, Random random) {
        List<Bid> bids = new ArrayList<Bid>();
        FactionAPI pbc = Global.getSector().getFaction("pbc");
        for (FactionAPI f : BankData.get().getInterestEngine().getMajorFactions()) {
            if (pbc != null && f.isHostileTo(pbc)) continue; // the Confederation won't sell to its enemies
            float proximity = proximityFactor(f, market);
            float relation = pbc != null ? 1f + 0.1f * f.getRelationship("pbc") : 1f;
            float amount = appraisal * (0.8f + 0.4f * random.nextFloat()) * proximity * relation;
            bids.add(new Bid(f, amount));
        }
        java.util.Collections.sort(bids, new java.util.Comparator<Bid>() {
            public int compare(Bid a, Bid b) { return Float.compare(b.amount, a.amount); }
        });
        return bids;
    }

    /** 1.2 for a faction with territory in the same system, down to 0.8 far away (over 40 light-years). */
    private static float proximityFactor(FactionAPI f, MarketAPI target) {
        float best = Float.MAX_VALUE;
        for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
            if (!f.getId().equals(m.getFactionId())) continue;
            if (m.getStarSystem() != null && m.getStarSystem() == target.getStarSystem()) return 1.2f;
            if (m.getLocationInHyperspace() != null && target.getLocationInHyperspace() != null) {
                best = Math.min(best, Misc.getDistanceLY(m.getLocationInHyperspace(), target.getLocationInHyperspace()));
            }
        }
        if (best == Float.MAX_VALUE) return 1f;
        return Math.max(0.8f, 1.15f - best / 40f * 0.35f);
    }

    // ------------------------------------------------------------------ month end

    /** Receivership: each colony's whole positive net income goes to its loan. Returns the total taken. */
    public float collectReceivership(BankData data, Map<String, Float> takenByColony) {
        ensure();
        float total = 0f;
        for (BankAccount loan : data.getLoanManager().getActiveLoans()) {
            if (getStage(loan) == STAGE_NONE) continue;
            MarketAPI market = collateral(loan);
            if (market == null || !market.isPlayerOwned()) continue;
            float income = Math.min(Math.max(0f, market.getNetIncome()), loan.remainingBalance);
            if (income <= 0f) continue;
            data.getLoanManager().applyPayment(loan, income, "GARNISH");
            total += income;
            if (takenByColony != null) takenByColony.put(market.getName(), income);
        }
        return total;
    }

    private static void notice(BankAccount loan, String titleKey, String desc, int phase) {
        Global.getSector().getIntelManager().addIntel(new LoanIntelPlugin(Str.get(titleKey), desc, loan.accountId, phase));
    }
}
