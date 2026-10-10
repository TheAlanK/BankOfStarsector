package com.bankofstarsector.rulecmd;

import com.bankofstarsector.banking.BankAccount;
import com.bankofstarsector.banking.LoanManager;
import com.bankofstarsector.collection.AssetSeizureManager;
import com.bankofstarsector.collection.CollectionFleetScript;
import com.bankofstarsector.core.BankData;
import com.bankofstarsector.core.BankSettings;
import com.bankofstarsector.core.Str;
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
            text.addPara(Str.get("dialog.collection.inOrder"));
            settle(dialog, memoryMap, ruleId, fleet);
            showDone(dialog);
            return true;
        }

        if ("hail".equals(action)) {
            if (dialog.getInteractionTarget().getActivePerson() == null && fleet.getCommander() != null) {
                dialog.getInteractionTarget().setActivePerson(fleet.getCommander());
                dialog.getVisualPanel().showPersonInfo(fleet.getCommander(), true);
            }
            text.addPara(Str.f("dialog.collection.hail1", loan.accountId, loan.loanType.getDisplayName()));
            text.addPara(Str.get("dialog.collection.figures"), hl,
                Misc.getDGSCredits(loan.amountPastDue), Misc.getDGSCredits(loan.remainingBalance), "" + loan.daysOverdue);
            text.addPara(Str.get("dialog.collection.hail2"));
            showChoices(dialog, loan);
            return true;
        }

        LoanManager lm = data.getLoanManager();
        float credits = Global.getSector().getPlayerFleet().getCargo().getCredits().get();

        if ("payPastDue".equals(action) || "payAll".equals(action)) {
            float amount = "payAll".equals(action) ? loan.remainingBalance : loan.amountPastDue;
            if (credits < amount) {
                text.addPara(Str.f("dialog.collection.noCredits", Misc.getDGSCredits(amount)), bad);
                showChoices(dialog, loan);
                return true;
            }
            lm.makePayment(loan.accountId, amount);
            text.addPara(Str.get("dialog.collection.transferred"), hl, Misc.getDGSCredits(amount));
            text.addPara(Str.get("dialog.collection.thanks"));
            settle(dialog, memoryMap, ruleId, fleet);
            showDone(dialog);
            return true;
        }

        if ("surrender".equals(action)) {
            FleetMemberAPI ship = AssetSeizureManager.pickShipToSeize();
            if (ship == null) {
                text.addPara(Str.get("dialog.collection.noCollateral"), bad);
                showChoices(dialog, loan);
                return true;
            }
            float value = AssetSeizureManager.seizureValue(ship);
            data.getAssetSeizureManager().seizeShip(data, ship, loan);
            text.addPara(Str.get("dialog.collection.seized"), hl,
                ship.getShipName(), Misc.getDGSCredits(value));
            if (loan.status != com.bankofstarsector.banking.LoanStatus.ACTIVE) {
                text.addPara(Str.get("dialog.collection.partial"), bad,
                    Misc.getDGSCredits(loan.amountPastDue));
            } else {
                text.addPara(Str.get("dialog.collection.current"));
                settle(dialog, memoryMap, ruleId, fleet);
            }
            showDone(dialog);
            return true;
        }

        if ("refuse".equals(action)) {
            text.addPara(Str.get("dialog.collection.refused"), bad);
            Global.getSector().getPlayerFaction().adjustRelationship("pbc", BankSettings.COLLECTION_REFUSAL_REP_PENALTY);
            text.addPara(Str.get("dialog.collection.repLoss"), bad);
            new MakeOtherFleetHostile().execute(ruleId, dialog, Misc.tokenize("true"), memoryMap);
            new MakeOtherFleetAggressive().execute(ruleId, dialog, Misc.tokenize("true"), memoryMap);
            data.addTransaction("REFUSED", 0, Str.get("txd.refused"));
            return finish(dialog, memoryMap, fleet);
        }

        if ("later".equals(action)) {
            text.addPara(Str.get("dialog.collection.later"));
            return finish(dialog, memoryMap, fleet);
        }

        // done / unknown
        return finish(dialog, memoryMap, fleet);
    }

    private void showChoices(InteractionDialogAPI dialog, BankAccount loan) {
        OptionPanelAPI options = dialog.getOptionPanel();
        options.clearOptions();
        float credits = Global.getSector().getPlayerFleet().getCargo().getCredits().get();

        options.addOption(Str.f("dialog.collection.optPayDue", Misc.getDGSCredits(loan.amountPastDue)), OPT_PAY_PAST_DUE);
        if (credits < loan.amountPastDue) options.setEnabled(OPT_PAY_PAST_DUE, false);

        options.addOption(Str.f("dialog.collection.optPayAll", Misc.getDGSCredits(loan.remainingBalance)), OPT_PAY_ALL);
        if (credits < loan.remainingBalance) options.setEnabled(OPT_PAY_ALL, false);

        FleetMemberAPI ship = AssetSeizureManager.pickShipToSeize();
        if (ship != null) {
            options.addOption(Str.f("dialog.collection.optSurrender", ship.getShipName(), Misc.getDGSCredits(AssetSeizureManager.seizureValue(ship))), OPT_SURRENDER);
        }
        options.addOption(Str.get("dialog.collection.optRefuse"), OPT_REFUSE, Misc.getNegativeHighlightColor(), Str.get("dialog.collection.optRefuseTip"));
        options.addOption(Str.get("dialog.collection.optLater"), OPT_LATER);
        options.setShortcut(OPT_LATER, org.lwjgl.input.Keyboard.KEY_ESCAPE, false, false, false, true);
    }

    private void showDone(InteractionDialogAPI dialog) {
        dialog.getOptionPanel().clearOptions();
        dialog.getOptionPanel().addOption(Str.get("dialog.cutComm"), OPT_DONE);
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
