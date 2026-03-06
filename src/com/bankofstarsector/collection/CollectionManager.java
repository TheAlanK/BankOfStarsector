package com.bankofstarsector.collection;

import com.bankofstarsector.banking.BankAccount;
import com.bankofstarsector.banking.LoanStatus;
import com.bankofstarsector.core.BankData;
import com.bankofstarsector.core.BankSettings;
import com.fs.starfarer.api.Global;

import org.apache.log4j.Logger;

import java.io.Serializable;
import java.util.*;

public class CollectionManager implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = Logger.getLogger(CollectionManager.class);

    // Track which loans have had collection notices sent
    private Set<String> phase1Notified;
    private Set<String> phase2Notified;
    private Set<String> phase3FleetDispatched;

    public CollectionManager() {
        phase1Notified = new HashSet<String>();
        phase2Notified = new HashSet<String>();
        phase3FleetDispatched = new HashSet<String>();
    }

    public void advanceDay(BankData data) {
        if (phase1Notified == null) phase1Notified = new HashSet<String>();
        if (phase2Notified == null) phase2Notified = new HashSet<String>();
        if (phase3FleetDispatched == null) phase3FleetDispatched = new HashSet<String>();
    }

    public void checkEscalation(BankData data) {
        for (BankAccount loan : data.getLoanManager().getActiveLoans()) {
            if (loan.status == LoanStatus.OVERDUE || loan.status == LoanStatus.DEFAULTED) {
                int days = loan.daysOverdue;

                // Phase 1: Warning (1-30 days)
                if (days >= 1 && days <= BankSettings.OVERDUE_PHASE1_DAYS) {
                    if (!phase1Notified.contains(loan.accountId)) {
                        phase1Notified.add(loan.accountId);
                        sendWarningIntel(loan, 1);
                    }
                }

                // Phase 2: Restricted (31-60 days)
                if (days > BankSettings.OVERDUE_PHASE1_DAYS &&
                        days <= BankSettings.OVERDUE_PHASE2_DAYS) {
                    if (!phase2Notified.contains(loan.accountId)) {
                        phase2Notified.add(loan.accountId);
                        sendWarningIntel(loan, 2);
                        // Double interest rate
                        loan.monthlyRate *= 2f;
                    }
                }

                // Phase 3: Collection fleet (61-90 days)
                if (days > BankSettings.OVERDUE_PHASE2_DAYS &&
                        days <= BankSettings.OVERDUE_PHASE3_DAYS) {
                    if (!phase3FleetDispatched.contains(loan.accountId)) {
                        phase3FleetDispatched.add(loan.accountId);
                        dispatchCollectionFleet(loan);
                        sendWarningIntel(loan, 3);
                    }
                }

                // Phase 4: Default + seizure (90+ days) - handled by LoanManager.advanceDay
            }
        }
    }

    private void sendWarningIntel(BankAccount loan, int phase) {
        String title;
        String desc;
        switch (phase) {
            case 1:
                title = "PBC Payment Reminder";
                desc = "Your " + loan.loanType.displayName + " payment is overdue. " +
                       "Balance: " + formatCredits(loan.remainingBalance) + ". " +
                       "Please make a payment to avoid penalties.";
                break;
            case 2:
                title = "PBC Collection Warning";
                desc = "Your " + loan.loanType.displayName + " is seriously overdue (" +
                       loan.daysOverdue + " days). Interest rate has been doubled. " +
                       "Banking access restricted. Pay immediately.";
                break;
            case 3:
                title = "PBC Enforcement Notice";
                desc = "The Confederation has dispatched a Collection Fleet to recover " +
                       formatCredits(loan.remainingBalance) + " in outstanding debt. " +
                       "Pay your debt or face enforcement action.";
                break;
            default:
                return;
        }

        // Create intel notification
        com.bankofstarsector.intel.LoanIntelPlugin intel =
            new com.bankofstarsector.intel.LoanIntelPlugin(title, desc, loan.accountId, phase);
        Global.getSector().getIntelManager().addIntel(intel);

        log.info("BOS: Collection phase " + phase + " for " + loan.accountId);
    }

    private void dispatchCollectionFleet(BankAccount loan) {
        // Calculate fleet FP based on debt
        int fp = (int)(loan.remainingBalance / BankSettings.COLLECTION_FLEET_FP_PER_DEBT);
        fp = Math.max(BankSettings.COLLECTION_FLEET_MIN_FP, Math.min(BankSettings.COLLECTION_FLEET_MAX_FP, fp));

        // Spawn fleet script
        CollectionFleetScript script = new CollectionFleetScript(loan.accountId, fp);
        Global.getSector().addTransientScript(script);

        log.info("BOS: Collection fleet dispatched (" + fp + " FP) for " + loan.accountId);
    }

    public boolean hasActiveCollection(String accountId) {
        return phase3FleetDispatched != null && phase3FleetDispatched.contains(accountId);
    }

    public boolean isBankingRestricted() {
        // Check if any loan is in phase 2+
        BankData data = BankData.get();
        for (BankAccount loan : data.getLoanManager().getActiveLoans()) {
            if (loan.daysOverdue > BankSettings.OVERDUE_PHASE1_DAYS) {
                return true;
            }
        }
        return false;
    }

    public void onLoanResolved(String accountId) {
        if (phase1Notified != null) phase1Notified.remove(accountId);
        if (phase2Notified != null) phase2Notified.remove(accountId);
        if (phase3FleetDispatched != null) phase3FleetDispatched.remove(accountId);
    }

    private String formatCredits(float amount) {
        if (amount >= 1000000) return String.format("%.1fM credits", amount / 1000000f);
        if (amount >= 1000) return String.format("%.0fk credits", amount / 1000f);
        return String.format("%.0f credits", amount);
    }
}
