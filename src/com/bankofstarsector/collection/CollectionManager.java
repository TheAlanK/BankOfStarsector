package com.bankofstarsector.collection;

import com.bankofstarsector.banking.BankAccount;
import com.bankofstarsector.banking.LoanManager;
import com.bankofstarsector.banking.LoanStatus;
import com.bankofstarsector.core.BankData;
import com.bankofstarsector.core.BankSettings;
import com.bankofstarsector.intel.CollectionIntelPlugin;
import com.bankofstarsector.intel.LoanIntelPlugin;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.comm.IntelInfoPlugin;

import org.apache.log4j.Logger;

import java.io.Serializable;
import java.util.*;

/**
 * Four-phase escalation, driven daily by days overdue:
 * 1 reminder, 2 banking restricted + penalty rate, 3 collection fleet, 4 default + garnishment.
 */
public class CollectionManager implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = Logger.getLogger(CollectionManager.class);

    private Set<String> phase1Notified;
    private Set<String> phase2Notified;
    private Set<String> phase3FleetDispatched;
    private Set<String> phase4Notified;
    /** Collection fleets the player destroyed, per loan: each one makes the next fleet bigger. */
    private Map<String, Integer> fleetsDefeated;
    /** Day count until the next fleet may be sent, per loan. */
    private Map<String, Integer> retryCooldown;

    public CollectionManager() {
        ensure();
    }

    private void ensure() {
        if (phase1Notified == null) phase1Notified = new HashSet<String>();
        if (phase2Notified == null) phase2Notified = new HashSet<String>();
        if (phase3FleetDispatched == null) phase3FleetDispatched = new HashSet<String>();
        if (phase4Notified == null) phase4Notified = new HashSet<String>();
        if (fleetsDefeated == null) fleetsDefeated = new HashMap<String, Integer>();
        if (retryCooldown == null) retryCooldown = new HashMap<String, Integer>();
    }

    public void advanceDay(BankData data) {
        ensure();
        for (Map.Entry<String, Integer> e : new ArrayList<Map.Entry<String, Integer>>(retryCooldown.entrySet())) {
            int left = e.getValue() - 1;
            if (left <= 0) {
                retryCooldown.remove(e.getKey());
                phase3FleetDispatched.remove(e.getKey()); // allow a new fleet
            } else {
                retryCooldown.put(e.getKey(), left);
            }
        }
        checkEscalation(data);
    }

    public void checkEscalation(BankData data) {
        ensure();
        for (BankAccount loan : data.getLoanManager().getActiveLoans()) {
            if (loan.status != LoanStatus.OVERDUE && loan.status != LoanStatus.DEFAULTED) continue;
            int days = loan.daysOverdue;

            if (days >= 1 && phase1Notified.add(loan.accountId)) {
                notify(loan, 1);
            }
            if (days > BankSettings.OVERDUE_PHASE1_DAYS && phase2Notified.add(loan.accountId)) {
                notify(loan, 2);
            }
            if (days > BankSettings.OVERDUE_PHASE2_DAYS && !phase3FleetDispatched.contains(loan.accountId)
                    && !retryCooldown.containsKey(loan.accountId)) {
                phase3FleetDispatched.add(loan.accountId);
                dispatchCollectionFleet(loan);
                notify(loan, 3);
            }
            if (loan.status == LoanStatus.DEFAULTED && phase4Notified.add(loan.accountId)) {
                notify(loan, 4);
            }
        }
    }

    private void notify(BankAccount loan, int phase) {
        String title;
        String desc;
        String bal = LoanManager.formatCredits(loan.remainingBalance) + " credits";
        String due = LoanManager.formatCredits(loan.amountPastDue) + " credits";
        switch (phase) {
            case 1:
                title = "PBC Payment Reminder";
                desc = "A payment of " + due + " on your " + loan.loanType.displayName + " is overdue. "
                    + "Pay the past-due amount in the Banking Terminal to avoid penalties.";
                break;
            case 2:
                title = "PBC Collection Warning";
                desc = "Your " + loan.loanType.displayName + " is " + loan.daysOverdue + " days overdue. "
                    + "Penalty interest applies and new banking services are suspended.";
                break;
            case 3:
                title = "PBC Enforcement Notice";
                desc = "The Confederation has dispatched a Collection Fleet over " + due + " past due ("
                    + bal + " outstanding). Settle with them, or face them.";
                break;
            case 4:
                title = "PBC Default Judgment";
                desc = "Your " + loan.loanType.displayName + " is in default. "
                    + (int) (BankSettings.GARNISH_PERCENTAGE * 100) + "% of your colony income will be garnished "
                    + "every month until the past-due amount is cleared.";
                break;
            default:
                return;
        }
        endIntelFor(loan.accountId, LoanIntelPlugin.class);
        LoanIntelPlugin intel = new LoanIntelPlugin(title, desc, loan.accountId, phase);
        Global.getSector().getIntelManager().addIntel(intel);
        log.info("BOS: Collection phase " + phase + " for " + loan.accountId);
    }

    private void dispatchCollectionFleet(BankAccount loan) {
        ensure();
        int fp = (int) (loan.remainingBalance / BankSettings.COLLECTION_FLEET_FP_PER_DEBT);
        fp = Math.max(BankSettings.COLLECTION_FLEET_MIN_FP, Math.min(BankSettings.COLLECTION_FLEET_MAX_FP, fp));
        Integer defeats = fleetsDefeated.get(loan.accountId);
        if (defeats != null && defeats > 0) {
            fp = (int) (fp * Math.pow(BankSettings.COLLECTION_FLEET_ESCALATION, defeats));
            fp = Math.min(fp, BankSettings.COLLECTION_FLEET_MAX_FP * 2);
        }

        // Persistent script: survives save/load together with the fleet it tracks.
        CollectionFleetScript script = new CollectionFleetScript(loan.accountId, fp);
        Global.getSector().addScript(script);

        Global.getSector().getIntelManager().addIntel(new CollectionIntelPlugin(loan.accountId, loan.remainingBalance));
        log.info("BOS: Collection fleet dispatched (" + fp + " FP) for " + loan.accountId);
    }

    /** The player beat a collection fleet: send a bigger one later. */
    public void onCollectionFleetDefeated(String accountId) {
        ensure();
        Integer d = fleetsDefeated.get(accountId);
        fleetsDefeated.put(accountId, d == null ? 1 : d + 1);
        retryCooldown.put(accountId, BankSettings.COLLECTION_FLEET_RETRY_DAYS);
        BankData.get().addTransaction("ENFORCEMENT", 0, "A PBC Collection Fleet was destroyed. A stronger one will follow.");
    }

    /** The fleet left without settling (timed out or was dismissed): try again later. */
    public void onCollectionFleetGone(String accountId) {
        ensure();
        if (!retryCooldown.containsKey(accountId)) retryCooldown.put(accountId, BankSettings.COLLECTION_FLEET_RETRY_DAYS);
    }

    public boolean hasActiveCollection(String accountId) {
        ensure();
        return phase3FleetDispatched.contains(accountId);
    }

    public boolean isBankingRestricted() {
        BankData data = BankData.get();
        for (BankAccount loan : data.getLoanManager().getActiveLoans()) {
            if (loan.daysOverdue > BankSettings.OVERDUE_PHASE1_DAYS) {
                return true;
            }
        }
        return false;
    }

    /** Loan cured or closed: clear its escalation state and wind down its notices. Use "*" for all. */
    public void onLoanResolved(String accountId) {
        ensure();
        if ("*".equals(accountId)) {
            phase1Notified.clear();
            phase2Notified.clear();
            phase3FleetDispatched.clear();
            phase4Notified.clear();
            retryCooldown.clear();
            fleetsDefeated.clear();
        } else {
            phase1Notified.remove(accountId);
            phase2Notified.remove(accountId);
            phase3FleetDispatched.remove(accountId);
            phase4Notified.remove(accountId);
            retryCooldown.remove(accountId);
            fleetsDefeated.remove(accountId);
        }
        endIntelFor(accountId, LoanIntelPlugin.class);
        endIntelFor(accountId, CollectionIntelPlugin.class);
    }

    private static void endIntelFor(String accountId, Class<?> type) {
        for (IntelInfoPlugin p : new ArrayList<IntelInfoPlugin>(Global.getSector().getIntelManager().getIntel(type))) {
            String id = null;
            if (p instanceof LoanIntelPlugin) id = ((LoanIntelPlugin) p).getLoanAccountId();
            if (p instanceof CollectionIntelPlugin) id = ((CollectionIntelPlugin) p).getLoanAccountId();
            if (id == null) continue;
            if ("*".equals(accountId) || id.equals(accountId)) {
                if (p instanceof LoanIntelPlugin) ((LoanIntelPlugin) p).endEvent();
                else ((CollectionIntelPlugin) p).endEvent();
            }
        }
    }
}
