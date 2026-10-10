package com.bankofstarsector.banking;

import com.bankofstarsector.core.BankData;
import com.bankofstarsector.core.BankSettings;
import com.bankofstarsector.core.Str;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.util.Misc;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fleet insurance (0.3.0).
 *
 * A policy covers every ship in the player's fleet. The monthly premium is
 *   plan rate x coverage x fleet base value x credit factor x claims factor x (1 + war surcharge)
 * where the credit factor follows the score bracket (insurers price on credit-based insurance scores)
 * and the claims factor grows with claims paid in the last 12 months.
 *
 * Ships lost in battle become pending claims, settled a couple of days later, once the post-battle
 * recovery is over: a ship back in the fleet by then was recovered and is not paid. A loss is paid
 * (coverage x base value - deductible) unless the policy is in its waiting period, the ship joined the
 * fleet less than INSURANCE_WAITING_DAYS before the loss, the premium is unpaid (lapsed), or this
 * month's payout cap is used up. One missed premium lapses the policy; two cancel it.
 */
public class InsuranceManager implements Serializable {

    private static final long serialVersionUID = 1L;

    private InsurancePlan plan;
    private long startTs;
    private int missedPremiums;
    private boolean lapsed;
    private float lastPremium;
    /** Insured value at the last month end (coverage x fleet value): the base of the monthly payout cap. */
    private float insuredValue;
    private float paidThisMonth;
    /** When each fleet member was first seen in the player's fleet. */
    private Map<String, Long> firstSeen;
    private List<PendingClaim> pending;
    private List<Claim> claims;

    public static class PendingClaim implements Serializable {
        private static final long serialVersionUID = 1L;
        public String memberId, shipName;
        public float baseValue;
        public long lossTs, joinedTs;
    }

    /** A settled claim: paid, or denied with a reason ("claim.reason.*"). */
    public static class Claim implements Serializable {
        private static final long serialVersionUID = 1L;
        public long ts;
        public String shipName, outcome;
        public float baseValue, payout;
    }

    private void ensure() {
        if (firstSeen == null) firstSeen = new HashMap<String, Long>();
        if (pending == null) pending = new ArrayList<PendingClaim>();
        if (claims == null) claims = new ArrayList<Claim>();
    }

    public InsurancePlan getPlan() { return plan; }
    public boolean hasPolicy() { return plan != null; }
    public boolean isLapsed() { return lapsed; }
    public int getMissedPremiums() { return missedPremiums; }
    public float getLastPremium() { return lastPremium; }
    public long getStartTs() { return startTs; }

    public List<Claim> getClaims() {
        ensure();
        return claims;
    }

    public List<PendingClaim> getPending() {
        ensure();
        return pending;
    }

    /** Days of the policy's waiting period still to run (0 = covered). */
    public int waitingDaysLeft() {
        if (plan == null) return 0;
        float days = Global.getSector().getClock().getElapsedDaysSince(startTs);
        return Math.max(0, (int) Math.ceil(BankSettings.INSURANCE_WAITING_DAYS - days));
    }

    /** Ships in the fleet that joined less than the waiting period ago (not covered yet). */
    public int shipsInWaiting() {
        ensure();
        int n = 0;
        for (FleetMemberAPI m : fleet()) {
            Long seen = firstSeen.get(m.getId());
            if (seen == null || Global.getSector().getClock().getElapsedDaysSince(seen) < BankSettings.INSURANCE_WAITING_DAYS) n++;
        }
        return n;
    }

    // ------------------------------------------------------------------ pricing

    public static List<FleetMemberAPI> fleet() {
        return Global.getSector().getPlayerFleet().getFleetData().getMembersListCopy();
    }

    public static float fleetValue() {
        float total = 0f;
        for (FleetMemberAPI m : fleet()) total += m.getBaseValue();
        return total;
    }

    /** Insurers use credit-based insurance scores: better credit, cheaper cover. */
    public static float creditFactor(CreditScoreManager csm) {
        if (!csm.hasScore()) return 1.5f;
        int s = csm.getScore();
        return s >= 750 ? 0.85f : s >= 650 ? 1f : s >= 500 ? 1.2f : 1.5f;
    }

    public float claimsFactor() {
        return Math.min(BankSettings.INSURANCE_MAX_CLAIMS_FACTOR, 1f + BankSettings.INSURANCE_CLAIM_LOAD * paidClaimsLast12Months());
    }

    public int paidClaimsLast12Months() {
        ensure();
        int n = 0;
        for (Claim c : claims) {
            if (c.payout > 0f && Global.getSector().getClock().getElapsedDaysSince(c.ts) <= 365f) n++;
        }
        return n;
    }

    /** Monthly premium of this plan for the current fleet. */
    public float premiumFor(InsurancePlan p, BankData data) {
        return p.getBaseRate() * p.coverage * fleetValue() * creditFactor(data.getCreditScoreManager())
            * claimsFactor() * (1f + data.getInterestEngine().getWarSurcharge());
    }

    // ------------------------------------------------------------------ buying

    /** Null when the player can buy (or switch to) this plan now, else the reason's string key. */
    public String whyNot(InsurancePlan p, BankData data) {
        if (data.getCollectionManager().isBankingRestricted()) return "terminal.loans.reasonOverdue";
        if (p == plan && !lapsed) return "insurance.reason.current";
        if (fleet().isEmpty()) return "insurance.reason.noFleet";
        return null;
    }

    /**
     * Buys or switches the policy. Switching keeps the policy's start date (no new waiting period);
     * buying after a cancellation starts a new one. The premium is billed at month end.
     */
    public boolean buy(InsurancePlan p, BankData data) {
        if (whyNot(p, data) != null) return false;
        if (plan == null) startTs = Global.getSector().getClock().getTimestamp();
        plan = p;
        lapsed = false;
        missedPremiums = 0;
        data.addTransaction("INSURANCE", 0f, Str.f("txd.insuranceBought", p.getDisplayName()));
        return true;
    }

    public void cancel(BankData data) {
        if (plan == null) return;
        data.addTransaction("INSURANCE", 0f, Str.f("txd.insuranceCancelled", plan.getDisplayName()));
        plan = null;
        lapsed = false;
        missedPremiums = 0;
    }

    // ------------------------------------------------------------------ losses and claims

    /** Battle losses (Misc.getSnapshotMembersLost of the player fleet): pending until recovery is over. */
    public void onShipsLost(List<FleetMemberAPI> lost) {
        ensure();
        if (plan == null || lost == null) return;
        long now = Global.getSector().getClock().getTimestamp();
        for (FleetMemberAPI m : lost) {
            boolean known = false;
            for (PendingClaim p : pending) if (p.memberId.equals(m.getId())) known = true;
            if (known) continue;
            PendingClaim p = new PendingClaim();
            p.memberId = m.getId();
            p.shipName = m.getShipName() != null ? m.getShipName() : m.getId();
            p.baseValue = m.getBaseValue();
            p.lossTs = now;
            Long joined = firstSeen.get(m.getId());
            p.joinedTs = joined != null ? joined : now;
            pending.add(p);
        }
    }

    /** Daily: settle claims whose recovery window is over, then record new arrivals in the fleet. */
    public void advanceDay(BankData data) {
        ensure();
        Set<String> inFleet = new HashSet<String>();
        for (FleetMemberAPI m : fleet()) inFleet.add(m.getId());

        Iterator<PendingClaim> it = pending.iterator();
        while (it.hasNext()) {
            PendingClaim p = it.next();
            if (Global.getSector().getClock().getElapsedDaysSince(p.lossTs) < BankSettings.INSURANCE_SETTLE_DAYS) continue;
            it.remove();
            if (inFleet.contains(p.memberId)) continue; // recovered after the battle: no loss
            settle(data, p);
        }

        long now = Global.getSector().getClock().getTimestamp();
        for (String id : inFleet) if (!firstSeen.containsKey(id)) firstSeen.put(id, now);
        Set<String> keep = new HashSet<String>(inFleet);
        for (PendingClaim p : pending) keep.add(p.memberId);
        firstSeen.keySet().retainAll(keep);
    }

    private void settle(BankData data, PendingClaim p) {
        Claim c = new Claim();
        c.ts = Global.getSector().getClock().getTimestamp();
        c.shipName = p.shipName;
        c.baseValue = p.baseValue;
        InsurancePlan at = plan;
        if (at == null) {
            c.outcome = "claim.reason.noPolicy";
        } else if (lapsed) {
            c.outcome = "claim.reason.lapsed";
        } else if (daysBetween(startTs, p.lossTs) < BankSettings.INSURANCE_WAITING_DAYS) {
            c.outcome = "claim.reason.waiting";
        } else if (daysBetween(p.joinedTs, p.lossTs) < BankSettings.INSURANCE_WAITING_DAYS) {
            c.outcome = "claim.reason.newShip";
        } else {
            float due = at.coverage * p.baseValue - at.deductible;
            float room = Math.max(0f, insuredValue * BankSettings.INSURANCE_MONTHLY_CAP_PCT - paidThisMonth);
            if (insuredValue <= 0f) room = Float.MAX_VALUE; // no month end yet: the cap starts with the first premium
            if (due <= 0f) {
                c.outcome = "claim.reason.deductible";
            } else if (room <= 0f) {
                c.outcome = "claim.reason.cap";
            } else {
                c.payout = Math.min(due, room);
                c.outcome = c.payout < due ? "claim.reason.partial" : "claim.reason.paid";
                paidThisMonth += c.payout;
                Global.getSector().getPlayerFleet().getCargo().getCredits().add(c.payout);
                data.addTransaction("CLAIM", c.payout, Str.f("txd.claim", p.shipName));
            }
        }
        claims.add(0, c);
        while (claims.size() > 30) claims.remove(claims.size() - 1);
        String msg = c.payout > 0f
            ? Str.f("insurance.msg.paid", p.shipName, Misc.getDGSCredits(c.payout))
            : Str.f("insurance.msg.denied", p.shipName, Str.get(c.outcome));
        Global.getSector().getCampaignUI().addMessage(msg, c.payout > 0f ? Misc.getPositiveHighlightColor() : Misc.getNegativeHighlightColor());
    }

    /** Days from one timestamp to a later one, measured by the campaign clock. */
    private static float daysBetween(long from, long to) {
        return Global.getSector().getClock().getElapsedDaysSince(from) - Global.getSector().getClock().getElapsedDaysSince(to);
    }

    // ------------------------------------------------------------------ month end

    /**
     * Bills the premium from what the month's books allow. Returns the amount charged (0 when there is
     * no policy or it could not be paid, which lapses the policy; a second miss in a row cancels it).
     */
    public float monthEnd(BankData data, float available) {
        paidThisMonth = 0f;
        if (plan == null) return 0f;
        float premium = premiumFor(plan, data);
        insuredValue = plan.coverage * fleetValue();
        if (premium <= available) {
            lastPremium = premium;
            missedPremiums = 0;
            lapsed = false;
            return premium;
        }
        missedPremiums++;
        lapsed = true;
        if (missedPremiums >= 2) {
            data.addTransaction("INSURANCE", 0f, Str.f("txd.insuranceLapsed", plan.getDisplayName()));
            Global.getSector().getCampaignUI().addMessage(Str.f("insurance.msg.cancelled", plan.getDisplayName()),
                Misc.getNegativeHighlightColor());
            plan = null;
            missedPremiums = 0;
            lapsed = false;
        } else {
            Global.getSector().getCampaignUI().addMessage(Str.get("insurance.msg.lapsed"), Misc.getNegativeHighlightColor());
        }
        return 0f;
    }
}
