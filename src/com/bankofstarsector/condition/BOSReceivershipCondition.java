package com.bankofstarsector.condition;

import com.bankofstarsector.banking.BankAccount;
import com.bankofstarsector.core.BankSettings;
import com.bankofstarsector.core.Str;
import com.fs.starfarer.api.impl.campaign.econ.BaseMarketConditionPlugin;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

/**
 * "PBC Receivership": the colony secures a defaulted loan. Its whole net income goes to the bank at
 * month end (BankEconomyListener) and Confederation administrators cost it stability. Added and removed
 * by ForeclosureManager. Transient (rebuilt on load), so never saved.
 */
public class BOSReceivershipCondition extends BaseMarketConditionPlugin {

    @Override
    public void apply(String id) {
        market.getStability().modifyFlat(id, -BankSettings.RECEIVERSHIP_STABILITY_PENALTY, getName());
    }

    @Override
    public void unapply(String id) {
        market.getStability().unmodifyFlat(id);
    }

    @Override
    public String getName() {
        return Str.get("condition.receivership.name");
    }

    @Override
    public void createTooltip(TooltipMakerAPI tooltip, boolean expanded) {
        tooltip.addTitle(getName());
        BankAccount loan = BOSLienCondition.securedLoan(market.getId());
        tooltip.addPara(Str.get("condition.receivership.desc"), 10f, Misc.getNegativeHighlightColor(),
            "" + (int) BankSettings.RECEIVERSHIP_STABILITY_PENALTY,
            loan != null ? Misc.getDGSCredits(loan.amountPastDue) : "?");
    }
}
