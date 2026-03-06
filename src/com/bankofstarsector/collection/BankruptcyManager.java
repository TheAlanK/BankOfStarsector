package com.bankofstarsector.collection;

import com.bankofstarsector.banking.*;
import com.bankofstarsector.core.BankData;
import com.bankofstarsector.core.BankSettings;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;

import org.apache.log4j.Logger;

import java.io.Serializable;

public class BankruptcyManager implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = Logger.getLogger(BankruptcyManager.class);

    public enum BankruptcyState { NONE, FILED, ACTIVE, RECOVERY }

    private BankruptcyState state;
    private int recoveryDaysRemaining;
    private int noLoansDaysRemaining;
    private int noInvestDaysRemaining;
    private boolean stigmaApplied;

    public BankruptcyManager() {
        state = BankruptcyState.NONE;
        recoveryDaysRemaining = 0;
        noLoansDaysRemaining = 0;
        noInvestDaysRemaining = 0;
        stigmaApplied = false;
    }

    public BankruptcyState getState() {
        if (state == null) state = BankruptcyState.NONE;
        return state;
    }

    public boolean canFileBankruptcy(BankData data) {
        if (state != BankruptcyState.NONE) return false;

        // Must have defaulted loan OR debt > 3x monthly income
        boolean hasDefault = false;
        for (BankAccount loan : data.getLoanManager().getActiveLoans()) {
            if (loan.status == LoanStatus.DEFAULTED) {
                hasDefault = true;
                break;
            }
        }
        if (hasDefault) return true;

        float totalDebt = data.getLoanManager().getTotalDebt();
        float monthlyIncome = getPlayerMonthlyIncome();
        return monthlyIncome > 0 && totalDebt > monthlyIncome * 3f;
    }

    public void fileBankruptcy(BankData data) {
        if (!canFileBankruptcy(data)) return;

        log.info("BOS: Player filing bankruptcy!");

        // Reduce all outstanding debt to 20%
        for (BankAccount loan : data.getLoanManager().getActiveLoans()) {
            loan.remainingBalance *= (1f - BankSettings.BANKRUPTCY_DEBT_REDUCTION);
            loan.status = LoanStatus.ACTIVE;
            loan.daysOverdue = 0;
        }

        // Liquidate investments at 50% value
        for (BankAccount inv : data.getInvestmentManager().getActiveInvestments()) {
            float liquidationValue = inv.currentValue * 0.5f;
            Global.getSector().getPlayerFleet().getCargo().getCredits().add(liquidationValue);
            inv.currentValue = 0;
            inv.investedAmount = 0;
        }

        // Credit score to minimum
        data.getCreditScoreManager().onBankruptcy();

        // Relations penalty with PBC
        FactionAPI pbc = Global.getSector().getFaction("pbc");
        if (pbc != null) {
            FactionAPI player = Global.getSector().getPlayerFaction();
            float currentRel = pbc.getRelationship(player.getId());
            pbc.setRelationship(player.getId(),
                currentRel + BankSettings.BANKRUPTCY_RELATION_PENALTY);
        }

        // Apply colony income stigma
        applyBankruptcyStigma();

        // Set recovery timers (in days, roughly)
        state = BankruptcyState.ACTIVE;
        noLoansDaysRemaining = BankSettings.BANKRUPTCY_NO_LOANS_MONTHS * 30;
        noInvestDaysRemaining = BankSettings.BANKRUPTCY_NO_INVEST_MONTHS * 30;
        recoveryDaysRemaining = BankSettings.BANKRUPTCY_FULL_RECOVERY_MONTHS * 30;

        // Clear collection states
        data.getCollectionManager().onLoanResolved("*"); // clear all

        data.addTransaction("BANKRUPTCY", 0, "Bankruptcy filed. Debts reduced. Recovery period begun.");
        log.info("BOS: Bankruptcy processed. Recovery: " + recoveryDaysRemaining + " days.");
    }

    public void advanceDay() {
        if (state == null) state = BankruptcyState.NONE;
        if (state == BankruptcyState.NONE) return;

        if (noLoansDaysRemaining > 0) noLoansDaysRemaining--;
        if (noInvestDaysRemaining > 0) noInvestDaysRemaining--;
        if (recoveryDaysRemaining > 0) {
            recoveryDaysRemaining--;
        }

        // Transition to recovery
        if (state == BankruptcyState.ACTIVE && noLoansDaysRemaining <= 0) {
            state = BankruptcyState.RECOVERY;
        }

        // Full recovery
        if (recoveryDaysRemaining <= 0 && state != BankruptcyState.NONE) {
            state = BankruptcyState.NONE;
            removeBankruptcyStigma();
            log.info("BOS: Bankruptcy recovery complete.");
        }
    }

    public void advanceMonth(BankData data) {
        if (state == BankruptcyState.ACTIVE || state == BankruptcyState.RECOVERY) {
            // Gradual credit score recovery
            int currentScore = data.getCreditScoreManager().getScore();
            if (currentScore < 500) {
                // Implicit: CreditScoreManager natural drift handles this
            }
        }
    }

    public boolean canTakeLoans() {
        return state == BankruptcyState.NONE ||
            (state == BankruptcyState.RECOVERY && noLoansDaysRemaining <= 0);
    }

    public boolean canInvest() {
        return state == BankruptcyState.NONE ||
            ((state == BankruptcyState.RECOVERY || state == BankruptcyState.ACTIVE)
                && noInvestDaysRemaining <= 0);
    }

    public boolean canTakeLoanType(LoanType type) {
        if (!canTakeLoans()) return false;
        if (state == BankruptcyState.RECOVERY) {
            // During recovery, only Emergency and Small loans
            return type == LoanType.EMERGENCY || type == LoanType.SMALL;
        }
        return true;
    }

    public int getRecoveryDaysRemaining() { return recoveryDaysRemaining; }
    public int getNoLoansDaysRemaining() { return noLoansDaysRemaining; }
    public int getNoInvestDaysRemaining() { return noInvestDaysRemaining; }

    private void applyBankruptcyStigma() {
        if (stigmaApplied) return;
        stigmaApplied = true;
        for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
            if (market.isPlayerOwned()) {
                market.getIncomeMult().modifyMult("bos_bankruptcy_stigma",
                    1f - BankSettings.BANKRUPTCY_COLONY_INCOME_PENALTY,
                    "Bankruptcy Stigma");
            }
        }
    }

    private void removeBankruptcyStigma() {
        stigmaApplied = false;
        for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
            if (market.isPlayerOwned()) {
                market.getIncomeMult().unmodify("bos_bankruptcy_stigma");
            }
        }
    }

    private float getPlayerMonthlyIncome() {
        float total = 0f;
        for (MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
            if (market.isPlayerOwned()) {
                total += market.getNetIncome();
            }
        }
        return total;
    }
}
