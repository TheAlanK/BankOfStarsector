package com.bankofstarsector.intel;

import com.bankofstarsector.banking.BankAccount;
import com.bankofstarsector.core.BankData;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import java.awt.Color;

/** Tracks an active Collection Fleet action against one loan. */
public class CollectionIntelPlugin extends BaseIntelPlugin {

    private String loanAccountId;
    private float debtAmount;
    /** Unused since 0.2.0; kept so 0.1.x saves still deserialize. */
    @SuppressWarnings("unused")
    private boolean ended;

    public CollectionIntelPlugin(String loanAccountId, float debtAmount) {
        this.loanAccountId = loanAccountId;
        this.debtAmount = debtAmount;
    }

    @Override
    public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
        Color c = getTitleColor(mode);
        info.addPara("PBC Collection Action", c, 0f);

        Color negative = Misc.getNegativeHighlightColor();
        info.addPara("Outstanding debt: %s", 3f, Misc.getGrayColor(),
            negative, Misc.getDGSCredits(debtAmount));
        if (!isEnding()) info.addPara("A Collection Fleet is hunting you.", negative, 3f);
        else info.addPara("Resolved.", Misc.getPositiveHighlightColor(), 3f);
    }

    @Override
    public boolean hasSmallDescription() { return true; }

    @Override
    public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
        float opad = 10f;
        BankAccount loan = BankData.get().getLoanManager().findLoan(loanAccountId);
        if (loan != null) {
            info.addPara("Loan: %s", opad, Misc.getHighlightColor(), loan.loanType.displayName);
            info.addPara("Past due: %s", opad, Misc.getNegativeHighlightColor(), Misc.getDGSCredits(loan.amountPastDue));
            info.addPara("Outstanding balance: %s", opad, Misc.getNegativeHighlightColor(),
                Misc.getDGSCredits(loan.remainingBalance));
            info.addPara("Days overdue: %s", opad, Misc.getNegativeHighlightColor(), "" + loan.daysOverdue);
        }
        info.addPara("The Persean Banking Confederation has dispatched an Enforcement Fleet. When it reaches "
            + "you, its commander will demand payment - you may pay, surrender a ship as collateral, or refuse "
            + "and fight. Paying the past-due amount in the Banking Terminal recalls the fleet.", opad);
        info.addPara("Destroying the fleet does not clear the debt; a larger fleet will follow.", opad,
            Misc.getNegativeHighlightColor(), "a larger fleet will follow");
    }

    @Override
    public String getIcon() {
        return com.fs.starfarer.api.Global.getSettings().getSpriteName("intel", "fleet_log");
    }

    @Override
    public boolean isImportant() { return !isEnding() && !isEnded(); }

    public void endEvent() {
        if (!isEnding() && !isEnded()) endAfterDelay();
    }

    public String getLoanAccountId() { return loanAccountId; }
}
