package com.bankofstarsector.banking;

import com.bankofstarsector.core.BankData;
import com.bankofstarsector.core.BankSettings;
import com.fs.starfarer.api.Global;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

public class InvestmentManager implements Serializable {

    private static final long serialVersionUID = 1L;

    private List<BankAccount> investments;

    public InvestmentManager() {
        investments = new ArrayList<BankAccount>();
    }

    public List<BankAccount> getInvestments() {
        if (investments == null) investments = new ArrayList<BankAccount>();
        return investments;
    }

    public List<BankAccount> getActiveInvestments() {
        List<BankAccount> active = new ArrayList<BankAccount>();
        for (BankAccount inv : getInvestments()) {
            if (inv.currentValue > 0) {
                active.add(inv);
            }
        }
        return active;
    }

    public boolean canInvest(InvestmentType type) {
        float playerCredits = Global.getSector().getPlayerFleet().getCargo().getCredits().get();
        return playerCredits >= type.minInvestment;
    }

    public BankAccount invest(InvestmentType type, float amount) {
        float playerCredits = Global.getSector().getPlayerFleet().getCargo().getCredits().get();
        if (playerCredits < amount || amount < type.minInvestment) return null;

        Global.getSector().getPlayerFleet().getCargo().getCredits().subtract(amount);

        long timestamp = Global.getSector().getClock().getTimestamp();
        BankAccount investment = BankAccount.createInvestment(type, amount, timestamp);
        getInvestments().add(investment);

        BankData.get().addTransaction("INVEST", -amount,
            "Invested in " + type.displayName);

        return investment;
    }

    public float withdraw(String accountId) {
        BankAccount inv = findInvestment(accountId);
        if (inv == null || inv.currentValue <= 0) return 0f;

        float returnAmount;
        if (inv.isLocked()) {
            // Early withdrawal penalty
            float penalty = inv.accumulatedReturns * BankSettings.EARLY_WITHDRAWAL_PENALTY;
            returnAmount = inv.currentValue - penalty;
            if (returnAmount < 0) returnAmount = 0;
            BankData.get().addTransaction("EARLY_WITHDRAW", returnAmount,
                "Early withdrawal from " + inv.investmentType.displayName + " (penalty applied)");
        } else {
            returnAmount = inv.currentValue;
            BankData.get().addTransaction("WITHDRAW", returnAmount,
                "Withdrawal from " + inv.investmentType.displayName);
        }

        Global.getSector().getPlayerFleet().getCargo().getCredits().add(returnAmount);
        inv.currentValue = 0;
        inv.investedAmount = 0;

        return returnAmount;
    }

    public void advanceMonth(InterestEngine engine) {
        for (BankAccount inv : getActiveInvestments()) {
            float monthlyReturn = engine.calculateInvestmentReturn(inv);
            inv.currentValue += monthlyReturn;
            inv.accumulatedReturns += monthlyReturn;

            // Don't let value go below 0
            if (inv.currentValue < 0) {
                inv.currentValue = 0;
            }

            // Reduce lock period
            if (inv.lockMonthsRemaining > 0) {
                inv.lockMonthsRemaining--;
            }
        }
    }

    public float getTotalValue() {
        float total = 0f;
        for (BankAccount inv : getActiveInvestments()) {
            total += inv.currentValue;
        }
        return total;
    }

    public float getMonthlyProjectedReturn() {
        float total = 0f;
        for (BankAccount inv : getActiveInvestments()) {
            total += inv.currentValue * inv.investmentType.baseMonthlyReturn;
        }
        return total;
    }

    public BankAccount findInvestment(String accountId) {
        for (BankAccount inv : getInvestments()) {
            if (inv.accountId.equals(accountId)) return inv;
        }
        return null;
    }

    public void cleanupEmptyInvestments() {
        Iterator<BankAccount> it = getInvestments().iterator();
        while (it.hasNext()) {
            BankAccount inv = it.next();
            if (inv.currentValue <= 0 && inv.investedAmount <= 0) {
                it.remove();
            }
        }
    }
}
