package com.bankofstarsector.intel;

import com.bankofstarsector.banking.BankAccount;
import com.bankofstarsector.core.BankData;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import java.awt.Color;

public class CollectionIntelPlugin extends BaseIntelPlugin {

    private String loanAccountId;
    private float debtAmount;
    private boolean ended;

    public CollectionIntelPlugin(String loanAccountId, float debtAmount) {
        this.loanAccountId = loanAccountId;
        this.debtAmount = debtAmount;
        this.ended = false;
    }

    @Override
    public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
        Color c = getTitleColor(mode);
        info.addPara("PBC Collection Action", c, 0f);

        Color negative = Misc.getNegativeHighlightColor();
        info.addPara("Outstanding debt: %s", 3f, Misc.getGrayColor(),
            negative, Misc.getDGSCredits(debtAmount));
        info.addPara("A Collection Fleet has been dispatched.", negative, 3f);
    }

    @Override
    public boolean hasLargeDescription() { return true; }

    @Override
    public void createLargeDescription(com.fs.starfarer.api.ui.CustomPanelAPI panel,
                                       float width, float height) {
        float opad = 10f;
        TooltipMakerAPI info = panel.createUIElement(width, height, true);

        info.addSectionHeading("PBC Debt Collection", Misc.getBasePlayerColor(),
            Misc.getDarkPlayerColor(), com.fs.starfarer.api.ui.Alignment.MID, opad);

        BankData data = BankData.get();
        BankAccount loan = data.getLoanManager().findLoan(loanAccountId);

        if (loan != null) {
            info.addPara("Loan Type: %s", opad, Misc.getHighlightColor(), loan.loanType.displayName);
            info.addPara("Outstanding Balance: %s", opad, Misc.getNegativeHighlightColor(),
                Misc.getDGSCredits(loan.remainingBalance));
            info.addPara("Days Overdue: %s", opad, Misc.getNegativeHighlightColor(),
                "" + loan.daysOverdue);
            info.addPara("Status: %s", opad, Misc.getNegativeHighlightColor(),
                loan.getStatusDisplay());
        }

        info.addSpacer(opad);
        info.addPara("The Persean Banking Confederation has dispatched an Enforcement Fleet " +
            "to collect on your outstanding debt. You can resolve this by making a payment " +
            "through the Banking Terminal in the Intel screen.", opad);

        info.addPara("Failure to pay may result in asset seizure and income garnishment.", opad,
            Misc.getNegativeHighlightColor(), "asset seizure", "income garnishment");

        panel.addUIElement(info);
    }

    @Override
    public String getIcon() {
        return "graphics/icons/intel/fleet_log.png";
    }

    @Override
    public boolean isEnded() { return ended; }

    @Override
    public boolean isEnding() { return ended; }

    public void endEvent() {
        ended = true;
        endAfterDelay();
    }

    public String getLoanAccountId() { return loanAccountId; }
}
