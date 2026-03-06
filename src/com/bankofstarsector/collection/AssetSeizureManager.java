package com.bankofstarsector.collection;

import com.bankofstarsector.banking.BankAccount;
import com.bankofstarsector.banking.LoanStatus;
import com.bankofstarsector.core.BankData;
import com.bankofstarsector.core.BankSettings;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.econ.MarketAPI;

import org.apache.log4j.Logger;

import java.io.Serializable;
import java.util.*;

public class AssetSeizureManager implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = Logger.getLogger(AssetSeizureManager.class);

    private boolean playerGarnished;
    private Set<String> garnishedMarkets;
    private Map<String, Float> factionDebts; // simulated NPC faction debts

    public AssetSeizureManager() {
        playerGarnished = false;
        garnishedMarkets = new HashSet<String>();
        factionDebts = new HashMap<String, Float>();
    }

    public void advanceDay(BankData data) {
        if (garnishedMarkets == null) garnishedMarkets = new HashSet<String>();
        if (factionDebts == null) factionDebts = new HashMap<String, Float>();
    }

    public void checkAndApplyGarnishment(BankData data) {
        boolean hasDefaultedLoan = false;
        for (BankAccount loan : data.getLoanManager().getActiveLoans()) {
            if (loan.status == LoanStatus.DEFAULTED) {
                hasDefaultedLoan = true;
                break;
            }
        }

        if (hasDefaultedLoan && !playerGarnished) {
            applyGarnishment();
        } else if (!hasDefaultedLoan && playerGarnished) {
            removeGarnishment();
        }
    }

    private void applyGarnishment() {
        playerGarnished = true;
        for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
            if (market.isPlayerOwned()) {
                market.getIncomeMult().modifyMult("bos_garnishment",
                    1f - BankSettings.GARNISH_PERCENTAGE,
                    "PBC Debt Garnishment");
                garnishedMarkets.add(market.getId());
            }
        }
        log.info("BOS: Income garnishment applied to player colonies.");
        BankData.get().addTransaction("GARNISH", 0,
            "PBC has garnished " + (int)(BankSettings.GARNISH_PERCENTAGE * 100) +
            "% of colony income due to defaulted loans.");
    }

    private void removeGarnishment() {
        playerGarnished = false;
        for (String marketId : garnishedMarkets) {
            MarketAPI market = Global.getSector().getEconomy().getMarket(marketId);
            if (market != null) {
                market.getIncomeMult().unmodify("bos_garnishment");
            }
        }
        garnishedMarkets.clear();
        log.info("BOS: Income garnishment removed.");
    }

    public boolean isPlayerGarnished() { return playerGarnished; }

    // NPC faction debt simulation
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
