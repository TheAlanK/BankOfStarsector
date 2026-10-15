package com.bankofstarsector.compat.console;

import com.bankofstarsector.core.BankData;
import com.bankofstarsector.core.Str;
import com.bankofstarsector.economy.FactionTreasury;
import com.bankofstarsector.economy.PoliticalFactions;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.util.Misc;
import org.lazywizard.console.BaseCommand;
import org.lazywizard.console.Console;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Console Commands: "bos_factions" lists every faction that holds markets and whether the Sovereign Desk
 * treats it as a state (bonds, treasury, war surcharge, foreclosure bids) or leaves it out, and why.
 * Useful with big mod lists; bos_settings.json can move a faction either way.
 */
public class BosFactions implements BaseCommand {

    public CommandResult runCommand(String args, CommandContext context) {
        if (!context.isInCampaign()) {
            Console.showMessage(Str.get("console.notCampaign"));
            return CommandResult.WRONG_CONTEXT;
        }
        Map<String, int[]> markets = new LinkedHashMap<String, int[]>(); // faction -> {markets, size 4+}
        for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
            if (m.isHidden()) continue;
            int[] n = markets.get(m.getFactionId());
            if (n == null) markets.put(m.getFactionId(), n = new int[2]);
            n[0]++;
            if (m.getSize() >= 4) n[1]++;
        }
        BankData data = BankData.get();
        StringBuilder sb = new StringBuilder(Str.get("console.factions.title"));
        for (Map.Entry<String, int[]> e : markets.entrySet()) {
            FactionAPI f = Global.getSector().getFaction(e.getKey());
            if (f == null) continue;
            String reason = PoliticalFactions.exclusionReason(f);
            int[] n = e.getValue();
            sb.append('\n');
            if (reason != null) {
                sb.append(Str.f("console.factions.excluded", f.getDisplayName(), f.getId(), n[0], n[1], Str.get(reason)));
                continue;
            }
            FactionTreasury t = data.getSectorEconomy().getTreasury(f.getId());
            String book = t == null ? "" : Str.f("console.factions.treasury", Misc.getDGSCredits(t.balance),
                Misc.getDGSCredits(data.getAssetSeizureManager().getFactionDebt(f.getId())), String.format("%.2f%%", t.yield * 100f));
            sb.append(Str.f("console.factions.political", f.getDisplayName(), f.getId(), n[0], n[1], book));
        }
        Console.showMessage(sb.toString());
        return CommandResult.SUCCESS;
    }
}
