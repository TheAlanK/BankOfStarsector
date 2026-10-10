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
        info.addPara(com.bankofstarsector.core.Str.get("collection.title"), c, 0f);

        Color negative = Misc.getNegativeHighlightColor();
        info.addPara(com.bankofstarsector.core.Str.get("collection.debt"), 3f, Misc.getGrayColor(),
            negative, Misc.getDGSCredits(debtAmount));
        if (!isEnding()) info.addPara(com.bankofstarsector.core.Str.get("collection.hunting"), negative, 3f);
        else info.addPara(com.bankofstarsector.core.Str.get("collection.resolved"), Misc.getPositiveHighlightColor(), 3f);
    }

    @Override
    public boolean hasSmallDescription() { return true; }

    @Override
    public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
        float opad = 10f;
        BankAccount loan = BankData.get().getLoanManager().findLoan(loanAccountId);
        if (loan != null) {
            info.addPara(com.bankofstarsector.core.Str.get("collection.loan"), opad, Misc.getHighlightColor(), loan.loanType.getDisplayName());
            info.addPara(com.bankofstarsector.core.Str.get("collection.pastDue"), opad, Misc.getNegativeHighlightColor(), Misc.getDGSCredits(loan.amountPastDue));
            info.addPara(com.bankofstarsector.core.Str.get("collection.balance"), opad, Misc.getNegativeHighlightColor(),
                Misc.getDGSCredits(loan.remainingBalance));
            info.addPara(com.bankofstarsector.core.Str.get("collection.days"), opad, Misc.getNegativeHighlightColor(), "" + loan.daysOverdue);
        }
        info.addPara(com.bankofstarsector.core.Str.get("collection.explain"), opad);
        info.addPara(com.bankofstarsector.core.Str.get("collection.warning"), Misc.getNegativeHighlightColor(), opad);
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
