package com.bankofstarsector.rulecmd;

import com.bankofstarsector.banking.BankAccount;
import com.bankofstarsector.core.BankData;
import com.bankofstarsector.core.BankModPlugin;
import com.bankofstarsector.core.Str;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CoreInteractionListener;
import com.fs.starfarer.api.campaign.CoreUITabId;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.rulecmd.BaseCommandPlugin;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.Misc.Token;

import java.util.List;
import java.util.Map;

/**
 * BOSBranch &lt;visit|payPastDue|terminal&gt;
 *
 * The Confederation branch office available at every PBC market.
 */
public class BOSBranch extends BaseCommandPlugin implements CoreInteractionListener {

    public static final String OPT_VISIT = "bos_branchVisit";
    public static final String OPT_PAY = "bos_branchPay";
    public static final String OPT_TERMINAL = "bos_branchTerminal";
    public static final String OPT_BACK = "bos_branchBack";

    private transient InteractionDialogAPI dialog;
    private transient Map<String, MemoryAPI> memoryMap;

    @Override
    public boolean doesCommandAddOptions() { return true; }

    /** Same slot the market menu used for the static rules.csv option (14). */
    @Override
    public int getOptionOrder(List<Token> params, Map<String, MemoryAPI> memoryMap) {
        return 14;
    }

    @Override
    public boolean execute(String ruleId, InteractionDialogAPI dialog, List<Token> params, Map<String, MemoryAPI> memoryMap) {
        if (dialog == null || params.isEmpty()) return false;
        this.dialog = dialog;
        this.memoryMap = memoryMap;
        String action = params.get(0).getString(memoryMap);
        BankData data = BankData.get();
        TextPanelAPI text = dialog.getTextPanel();

        if ("addOption".equals(action)) {
            // Market main menu entry, added from code so its label can be translated.
            dialog.getOptionPanel().addOption(Str.get("branch.option"), OPT_VISIT);
            return true;
        }

        if ("payPastDue".equals(action)) {
            float credits = Global.getSector().getPlayerFleet().getCargo().getCredits().get();
            float paid = 0f;
            for (BankAccount loan : data.getLoanManager().getActiveLoans()) {
                if (loan.amountPastDue <= 1f || credits < loan.amountPastDue) continue;
                float amount = loan.amountPastDue;
                if (data.getLoanManager().makePayment(loan.accountId, amount)) {
                    paid += amount;
                    credits -= amount;
                }
            }
            if (paid > 0f) text.addPara(Str.get("branch.paid"), Misc.getHighlightColor(), Misc.getDGSCredits(paid));
            else text.addPara(Str.get("branch.cannotPay"), Misc.getNegativeHighlightColor());
        } else if ("terminal".equals(action)) {
            dialog.getOptionPanel().clearOptions();
            dialog.getVisualPanel().showCore(CoreUITabId.INTEL, dialog.getInteractionTarget(), BankModPlugin.getTerminal(), this);
            return true;
        } else {
            text.addPara(Str.get("branch.intro"));
        }

        float debt = data.getLoanManager().getTotalDebt();
        float pastDue = data.getLoanManager().getTotalPastDue();
        text.addPara(Str.get("branch.summary"), Misc.getHighlightColor(),
            data.getCreditScoreManager().getScoreText(), data.getCreditScoreManager().getBracket(),
            Misc.getDGSCredits(debt), Misc.getDGSCredits(pastDue));

        OptionPanelAPI options = dialog.getOptionPanel();
        options.clearOptions();
        if (pastDue > 1f) options.addOption(Str.get("branch.optPay"), OPT_PAY);
        options.addOption(Str.get("branch.optTerminal"), OPT_TERMINAL);
        options.addOption(Str.get("branch.optLeave"), OPT_BACK);
        options.setShortcut(OPT_BACK, org.lwjgl.input.Keyboard.KEY_ESCAPE, false, false, false, true);
        return true;
    }

    public void coreUIDismissed() {
        if (dialog == null) return;
        execute(null, dialog, Misc.tokenize("visit"), memoryMap);
    }
}
