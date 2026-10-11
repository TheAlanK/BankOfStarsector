package com.bankofstarsector.collection;

import com.bankofstarsector.banking.BankAccount;
import com.bankofstarsector.banking.LoanStatus;
import com.bankofstarsector.core.BankData;
import com.bankofstarsector.core.BankSettings;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;

import org.apache.log4j.Logger;

import java.io.Serializable;
import java.util.*;

/**
 * Phase 4 enforcement: income garnishment on colonies while a loan is in default,
 * and ship seizure when settling with a Collection Fleet.
 */
public class AssetSeizureManager implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = Logger.getLogger(AssetSeizureManager.class);

    private boolean playerGarnished;
    private Set<String> garnishedMarkets; // kept for 0.1.x saves that used income multipliers
    private Map<String, Float> factionDebts; // simulated NPC faction debts

    public AssetSeizureManager() {
        playerGarnished = false;
        garnishedMarkets = new HashSet<String>();
        factionDebts = new HashMap<String, Float>();
    }

    public void advanceDay(BankData data) {
        if (garnishedMarkets == null) garnishedMarkets = new HashSet<String>();
        if (factionDebts == null) factionDebts = new HashMap<String, Float>();
        // 0.1.x reduced colony income with a multiplier that never reached the bank; undo it.
        if (!garnishedMarkets.isEmpty()) {
            for (String marketId : garnishedMarkets) {
                MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
                if (market != null) market.getIncomeMult().unmodify("bos_garnishment");
            }
            garnishedMarkets.clear();
        }
    }

    /**
     * Month end: while any loan is in default, take a share of positive colony income and
     * apply it to the defaulted balances. Returns the amount to book as upkeep in the report.
     */
    public float computeGarnishment(BankData data, float colonyIncome) {
        playerGarnished = data.getLoanManager().hasDefaultedLoan();
        if (!playerGarnished || colonyIncome <= 0f) return 0f;
        float owedPastDue = 0f;
        for (BankAccount loan : data.getLoanManager().getActiveLoans()) {
            if (loan.status == LoanStatus.DEFAULTED) owedPastDue += loan.remainingBalance;
        }
        return Math.min(owedPastDue, colonyIncome * BankSettings.GARNISH_PERCENTAGE);
    }

    /** Spreads seized money over defaulted loans, oldest first. */
    public void applyToDefaultedLoans(BankData data, float amount, String txType) {
        for (BankAccount loan : data.getLoanManager().getActiveLoans()) {
            if (amount <= 0f) break;
            if (loan.status != LoanStatus.DEFAULTED) continue;
            float part = Math.min(amount, loan.remainingBalance);
            data.getLoanManager().applyPayment(loan, part, txType);
            amount -= part;
        }
        // Any remainder goes to other overdue loans.
        for (BankAccount loan : data.getLoanManager().getActiveLoans()) {
            if (amount <= 0f) break;
            float part = Math.min(amount, loan.remainingBalance);
            data.getLoanManager().applyPayment(loan, part, txType);
            amount -= part;
        }
    }

    /**
     * Default judgment: the bank first takes what it already holds - the player's investments -
     * up to the defaulted balance. Returns the amount seized.
     */
    public float seizeInvestments(BankData data) {
        float owed = 0f;
        for (BankAccount loan : data.getLoanManager().getActiveLoans()) {
            if (loan.status == LoanStatus.DEFAULTED) owed += loan.remainingBalance;
        }
        if (owed <= 0f) return 0f;
        float seized = 0f;
        for (BankAccount inv : data.getInvestmentManager().getActiveInvestments()) {
            if (owed - seized <= 0f) break;
            float take = Math.min(inv.currentValue, owed - seized);
            float fraction = inv.currentValue > 0f ? take / inv.currentValue : 1f;
            inv.investedAmount *= (1f - fraction);
            inv.accumulatedReturns *= (1f - fraction);
            inv.currentValue -= take;
            seized += take;
            data.addTransaction("SEIZURE", 0,
                com.bankofstarsector.core.Str.f("txd.seizeInvest", (int) take, inv.investmentType.getDisplayName()));
        }
        if (seized > 0f) {
            applyToDefaultedLoans(data, seized, "SEIZURE");
            log.info("BOS: Seized " + seized + " from investments");
        }
        return seized;
    }

    public boolean isPlayerGarnished() { return playerGarnished; }

    /** Most valuable ship the bank would accept: never the flagship, never the last ship. */
    public static FleetMemberAPI pickShipToSeize() {
        CampaignFleetAPI player = Global.getSector().getPlayerFleet();
        if (player == null) return null;
        List<FleetMemberAPI> members = player.getFleetData().getMembersListCopy();
        if (members.size() < 2) return null;
        FleetMemberAPI best = null;
        for (FleetMemberAPI m : members) {
            if (m.isFlagship() || m.isFighterWing()) continue;
            if (m.getCaptain() != null && m.getCaptain().isPlayer()) continue;
            if (best == null || m.getBaseValue() > best.getBaseValue()) best = m;
        }
        return best;
    }

    public static float seizureValue(FleetMemberAPI m) {
        return m == null ? 0f : m.getBaseValue() * BankSettings.SHIP_SURRENDER_VALUE_FRACTION;
    }

    /** Hands the ship to the bank and credits its value against the loan. */
    public void seizeShip(BankData data, FleetMemberAPI member, BankAccount loan) {
        CampaignFleetAPI player = Global.getSector().getPlayerFleet();
        float value = seizureValue(member);
        if (member.getCaptain() != null && !member.getCaptain().isDefault()) {
            member.setCaptain(null); // the officer stays with the player
        }
        player.getFleetData().removeFleetMember(member);
        float part = Math.min(value, loan.remainingBalance);
        data.getLoanManager().applyPayment(loan, part, "SEIZURE");
        float rest = value - part;
        if (rest > 0) applyToDefaultedLoans(data, rest, "SEIZURE");
        data.addTransaction("SEIZURE", 0, com.bankofstarsector.core.Str.f("txd.seizeShip",
            member.getShipName(), member.getHullSpec().getHullName(), (int) value));
        log.info("BOS: Seized " + member.getShipName() + " worth " + value);
    }

    // ------------------------------------------------------------------ sovereign debt (NPC factions)

    /*
     * Sovereign debt itself still lives here (factionDebts, saved). Since 0.4.0 it is driven by real
     * faction deficits in economy/SectorEconomy instead of a random simulation.
     */

    public float getTotalSovereignDebt() {
        float total = 0f;
        for (Float d : getAllFactionDebts().values()) total += d;
        return total;
    }

    public void simulateFactionDebt(String factionId, float debtChange) {
        if (factionDebts == null) factionDebts = new HashMap<String, Float>();
        Float current = factionDebts.get(factionId);
        float newDebt = (current != null ? current : 0f) + debtChange;
        if (newDebt <= 0) {
            factionDebts.remove(factionId);
        } else {
            factionDebts.put(factionId, newDebt);
        }
    }

    public float getFactionDebt(String factionId) {
        if (factionDebts == null) return 0f;
        Float debt = factionDebts.get(factionId);
        return debt != null ? debt : 0f;
    }

    public Map<String, Float> getAllFactionDebts() {
        if (factionDebts == null) factionDebts = new HashMap<String, Float>();
        return Collections.unmodifiableMap(factionDebts);
    }
}
