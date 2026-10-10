package com.bankofstarsector.condition;

import com.bankofstarsector.banking.BankAccount;
import com.bankofstarsector.core.BankData;
import com.bankofstarsector.core.Str;
import com.fs.starfarer.api.impl.campaign.econ.BaseMarketConditionPlugin;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * "PBC Lien": the colony secures a Confederation loan. No effect of its own; it shows the pledge on the
 * colony screen. Added and removed by ForeclosureManager. Transient (rebuilt on load), so never saved.
 */
public class BOSLienCondition extends BaseMarketConditionPlugin {

    @Override
    public String getName() {
        return Str.get("condition.lien.name");
    }

    @Override
    public void createTooltip(TooltipMakerAPI tooltip, boolean expanded) {
        tooltip.addTitle(getName());
        BankAccount loan = securedLoan(market.getId());
        tooltip.addPara(Str.get("condition.lien.desc"), 10f, Misc.getHighlightColor(),
            loan != null ? loan.loanType.getDisplayName() : "?",
            loan != null ? Misc.getDGSCredits(loan.remainingBalance) : "?");
    }

    /** The open loan this colony secures, if any. */
    static BankAccount securedLoan(String marketId) {
        for (BankAccount loan : BankData.get().getLoanManager().getActiveLoans()) {
            if (marketId.equals(loan.collateralMarketId)) return loan;
        }
        return null;
    }
}
