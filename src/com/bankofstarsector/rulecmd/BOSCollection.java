package com.bankofstarsector.rulecmd;

import com.bankofstarsector.banking.BankAccount;
import com.bankofstarsector.banking.LoanManager;
import com.bankofstarsector.collection.AssetSeizureManager;
import com.bankofstarsector.collection.CollectionFleetScript;
import com.bankofstarsector.core.BankData;
import com.bankofstarsector.core.BankSettings;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.fleet.FleetMemberAPI;
import com.fs.starfarer.api.impl.campaign.FleetInteractionDialogPluginImpl;
import com.fs.starfarer.api.impl.campaign.rulecmd.BaseCommandPlugin;
import com.fs.starfarer.api.impl.campaign.rulecmd.EndConversation;
import com.fs.starfarer.api.impl.campaign.rulecmd.MakeOtherFleetAggressive;
import com.fs.starfarer.api.impl.campaign.rulecmd.MakeOtherFleetHostile;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.Misc.Token;

import java.awt.Color;
import java.util.List;
import java.util.Map;

/**
 * BOSCollection &lt;hail|payPastDue|payAll|surrender|refuse|later&gt;
 *
 * Drives the conversation with a PBC Collection Fleet (rules.csv: BOSCollectionHail /
 * OpenCommLink with $entity.bos_collection_fleet). Works both in the standalone hail dialog
 * opened by CollectionFleetScript and inside a vanilla fleet encounter comm link.
 */
public class BOSCollection extends BaseCommandPlugin {

    public static final String OPT_PAY_PAST_DUE = "bos_payPastDue";
    public static final String OPT_PAY_ALL = "bos_payAll";
    public static final String OPT_SURRENDER = "bos_surrender";
    public static final String OPT_REFUSE = "bos_refuse";
    public static final String OPT_LATER = "bos_later";
    public static final String OPT_DONE = "bos_done";

    @Override
    public boolean doesCommandAddOptions() { return true; }

    @Override
    public boolean execute(String ruleId, InteractionDialogAPI dialog, List<Token> params, Map<String, MemoryAPI> memoryMap) {
        if (dialog == null || params.isEmpty()) return false;
        String action = params.get(0).getString(memoryMap);

        CampaignFleetAPI fleet = dialog.getInteractionTarget() instanceof CampaignFleetAPI
            ? (CampaignFleetAPI) dialog.getInteractionTarget() : null;
        if (fleet == null) return false;

        BankData data = BankData.get();
        String loanId = fleet.getMemoryWithoutUpdate().getString(CollectionFleetScript.MEM_TARGET_LOAN);
        BankAccount loan = loanId == null ? null : data.getLoanManager().findLoan(loanId);
        TextPanelAPI text = dialog.getTextPanel();
        Color hl = Misc.getHighlightColor();
        Color bad = Misc.getNegativeHighlightColor();

        if (loan == null || !loan.isOpenLoan() || loan.status == com.bankofstarsector.banking.LoanStatus.ACTIVE) {
            if (!"hail".equals(action)) return finish(dialog, memoryMap, fleet);
            text.addPara("\"Our records show your account is in order, Captain. The Confederation thanks you for your business.\"");
            settle(dialog, memoryMap, ruleId, fleet);
            showDone(dialog);
            return true;
        }

        if ("hail".equals(action)) {
            if (dialog.getInteractionTarget().getActivePerson() == null && fleet.getCommander() != null) {
                dialog.getInteractionTarget().setActivePerson(fleet.getCommander());
                dialog.getVisualPanel().showPersonInfo(fleet.getCommander(), true);
            }
            text.addPara("\"Captain. This is an enforcement action of the Persean Banking Confederation regarding account "
                + loan.accountId + ", your " + loan.loanType.displayName + ".\"");
            text.addPara("Past due: %s. Outstanding balance: %s. Days overdue: %s.", hl,
                Misc.getDGSCredits(loan.amountPastDue), Misc.getDGSCredits(loan.remainingBalance), "" + loan.daysOverdue);
            text.addPara("\"Settle the arrears now and we part as partners. Otherwise we are authorized to take "
                + "collateral - or to take it by force.\"");
            showChoices(dialog, loan);
            return true;
        }

        LoanManager lm = data.getLoanManager();
        float credits = Global.getSector().getPlayerFleet().getCargo().getCredits().get();

        if ("payPastDue".equals(action) || "payAll".equals(action)) {
            float amount = "payAll".equals(action) ? loan.remainingBalance : loan.amountPastDue;
            if (credits < amount) {
                text.addPara("You don't have " + Misc.getDGSCredits(amount) + ".", bad);
                showChoices(dialog, loan);
                return true;
            }
            lm.makePayment(loan.accountId, amount);
            text.addPara("Transferred %s to the Confederation.", hl, Misc.getDGSCredits(amount));
            text.addPara("\"A pleasure doing business. Fly safe, Captain.\"");
            settle(dialog, memoryMap, ruleId, fleet);
            showDone(dialog);
            return true;
        }

        if ("surrender".equals(action)) {
            FleetMemberAPI ship = AssetSeizureManager.pickShipToSeize();
            if (ship == null) {
                text.addPara("\"You have nothing we can accept as collateral.\"", bad);
                showChoices(dialog, loan);
                return true;
            }
            float value = AssetSeizureManager.seizureValue(ship);
            data.getAssetSeizureManager().seizeShip(data, ship, loan);
            text.addPara("A prize crew boards the %s. The Confederation credits %s against your debt.", hl,
                ship.getShipName(), Misc.getDGSCredits(value));
            if (loan.status != com.bankofstarsector.banking.LoanStatus.ACTIVE) {
                text.addPara("Still past due: %s. \"This covers part of it. We'll be in touch.\"", bad,
                    Misc.getDGSCredits(loan.amountPastDue));
            } else {
                text.addPara("\"Your account is current. Good day, Captain.\"");
                settle(dialog, memoryMap, ruleId, fleet);
            }
            showDone(dialog);
            return true;
        }

        if ("refuse".equals(action)) {
            text.addPara("\"Then the Confederation will recover its assets the hard way.\"", bad);
            Global.getSector().getPlayerFaction().adjustRelationship("pbc", BankSettings.COLLECTION_REFUSAL_REP_PENALTY);
            text.addPara("Relations with the Persean Banking Confederation decreased.", bad);
            new MakeOtherFleetHostile().execute(ruleId, dialog, Misc.tokenize("true"), memoryMap);
            new MakeOtherFleetAggressive().execute(ruleId, dialog, Misc.tokenize("true"), memoryMap);
            data.addTransaction("REFUSED", 0, "Refused a PBC Collection Fleet's demands");
            return finish(dialog, memoryMap, fleet);
        }

        if ("later".equals(action)) {
            text.addPara("\"We'll be watching, Captain. Don't make us come looking.\"");
            return finish(dialog, memoryMap, fleet);
        }

        // done / unknown
        return finish(dialog, memoryMap, fleet);
    }

    private void showChoices(InteractionDialogAPI dialog, BankAccount loan) {
        OptionPanelAPI options = dialog.getOptionPanel();
        options.clearOptions();
        float credits = Global.getSector().getPlayerFleet().getCargo().getCredits().get();

        options.addOption("Pay the past-due amount (" + Misc.getDGSCredits(loan.amountPastDue) + ")", OPT_PAY_PAST_DUE);
        if (credits < loan.amountPastDue) options.setEnabled(OPT_PAY_PAST_DUE, false);

        options.addOption("Settle the entire debt (" + Misc.getDGSCredits(loan.remainingBalance) + ")", OPT_PAY_ALL);
        if (credits < loan.remainingBalance) options.setEnabled(OPT_PAY_ALL, false);

        FleetMemberAPI ship = AssetSeizureManager.pickShipToSeize();
        if (ship != null) {
            options.addOption("Surrender the " + ship.getShipName() + " as collateral (worth "
                + Misc.getDGSCredits(AssetSeizureManager.seizureValue(ship)) + ")", OPT_SURRENDER);
        }
        options.addOption("Refuse", OPT_REFUSE, Misc.getNegativeHighlightColor(), "They will open fire.");
        options.addOption("\"I need more time.\"", OPT_LATER);
        options.setShortcut(OPT_LATER, org.lwjgl.input.Keyboard.KEY_ESCAPE, false, false, false, true);
    }

    private void showDone(InteractionDialogAPI dialog) {
        dialog.getOptionPanel().clearOptions();
        dialog.getOptionPanel().addOption("Cut the comm link", OPT_DONE);
        dialog.getOptionPanel().setShortcut(OPT_DONE, org.lwjgl.input.Keyboard.KEY_ESCAPE, false, false, false, true);
    }

    /** Debt settled: stand the fleet down (undoing a refusal's hostility) and send it home. */
    private static void settle(InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap, String ruleId,
                               CampaignFleetAPI fleet) {
        fleet.getMemoryWithoutUpdate().set(CollectionFleetScript.MEM_SETTLED, true);
        new MakeOtherFleetHostile().execute(ruleId, dialog, Misc.tokenize("false"), memoryMap);
        new MakeOtherFleetAggressive().execute(ruleId, dialog, Misc.tokenize("false"), memoryMap);
    }

    /** Back to the fleet encounter if we're inside one; otherwise close our own dialog. */
    private boolean finish(InteractionDialogAPI dialog, Map<String, MemoryAPI> memoryMap, CampaignFleetAPI fleet) {
        if (dialog.getPlugin() instanceof FleetInteractionDialogPluginImpl) {
            return new EndConversation().execute(null, dialog, Misc.tokenize(""), memoryMap);
        }
        dialog.dismiss();
        return true;
    }
}
