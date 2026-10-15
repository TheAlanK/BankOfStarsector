package com.bankofstarsector.compat.console;

import com.bankofstarsector.banking.CreditScoreManager;
import com.bankofstarsector.banking.InsuranceManager;
import com.bankofstarsector.banking.InsurancePlan;
import com.bankofstarsector.banking.LoanManager;
import com.bankofstarsector.core.BankData;
import com.bankofstarsector.core.BankModPlugin;
import com.bankofstarsector.core.Str;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.RepLevel;
import com.fs.starfarer.api.impl.campaign.ids.Factions;
import com.fs.starfarer.api.util.Misc;
import org.lazywizard.console.BaseCommand;
import org.lazywizard.console.Console;

/**
 * Console Commands: "bos_status" prints the player's accounts and the bank's view of the Sector.
 * Registered in data/console/commands.csv; only loaded when Console Commands is enabled.
 */
public class BosStatus implements BaseCommand {

    public CommandResult runCommand(String args, CommandContext context) {
        if (!context.isInCampaign()) {
            Console.showMessage(Str.get("console.notCampaign"));
            return CommandResult.WRONG_CONTEXT;
        }
        BankData data = BankData.get();
        CreditScoreManager csm = data.getCreditScoreManager();
        LoanManager lm = data.getLoanManager();
        FactionAPI pbc = Global.getSector().getFaction("pbc");
        RepLevel level = pbc != null ? pbc.getRelationshipLevel(Factions.PLAYER) : null;

        StringBuilder sb = new StringBuilder();
        sb.append(Str.f("console.status.title", BankModPlugin.VERSION)).append('\n');
        sb.append(Str.f("console.status.score", csm.getScoreText(),
            csm.hasScore() ? String.valueOf(csm.getUnderwritingScore()) : "--",
            String.format("%+.0f", CreditScoreManager.pbcRelationship() * 100f),
            level != null ? level.getDisplayName() : "?")).append('\n');
        sb.append(Str.f("console.status.loans", lm.getActiveLoans().size(),
            Misc.getDGSCredits(lm.getTotalDebt()), Misc.getDGSCredits(lm.getTotalLate()))).append('\n');
        sb.append(Str.f("console.status.investments", data.getInvestmentManager().getActiveInvestments().size(),
            Misc.getDGSCredits(data.getInvestmentManager().getTotalValue()))).append('\n');

        InsuranceManager ins = data.getInsuranceManager();
        InsurancePlan plan = ins.getPlan();
        float[] other = InsuranceManager.otherCover();
        float exposure = plan != null ? InsuranceManager.insuredExposure(plan) : 0f;
        sb.append(Str.f("console.status.insurance",
            plan != null ? plan.getDisplayName() : Str.get("console.status.noInsurance"),
            Misc.getDGSCredits(plan != null ? ins.premiumForExposure(plan, data, exposure) : 0f),
            Misc.getDGSCredits(exposure), (int) other[0], Misc.getDGSCredits(other[1]),
            Misc.getDGSCredits(ins.getRecoveryDue()))).append('\n');

        sb.append(Str.f("console.status.desk", data.getSectorEconomy().getTreasuries().size(),
            data.getInterestEngine().getWarCount(), data.getInterestEngine().getStateCount(),
            String.format("%.1f%%", data.getInterestEngine().getWarSurcharge() * 100f),
            Misc.getDGSCredits(data.getSectorEconomy().getLedger().reserves)));
        Console.showMessage(sb.toString());
        return CommandResult.SUCCESS;
    }
}
