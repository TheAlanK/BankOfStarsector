package com.bankofstarsector.intel;

import com.bankofstarsector.core.BankData;
import com.bankofstarsector.core.Str;
import com.bankofstarsector.economy.DevelopmentContract;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.SectorMapAPI;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import java.util.List;
import java.util.Set;

/**
 * The Clearing Hall's public register of development deals between factions (0.4.0): one intel item, added
 * with the first deal, listing the most recent ones.
 */
public class DevelopmentLogIntel extends BaseIntelPlugin {

    private static final int SHOWN = 10;

    /** Adds the register once (the first NPC deal). */
    public static void ensure() {
        if (Global.getSector() == null || Global.getSector().getIntelManager() == null) return;
        if (Global.getSector().getIntelManager().hasIntelOfClass(DevelopmentLogIntel.class)) return;
        Global.getSector().getIntelManager().addIntel(new DevelopmentLogIntel(), true);
    }

    @Override
    public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
        info.addPara(Str.get("dev.log.title"), getTitleColor(mode), 0f);
        List<DevelopmentContract> deals = BankData.get().getDevelopmentManager().sectorDeals();
        info.addPara(Str.get("dev.log.subtitle"), 3f, Misc.getGrayColor(), Misc.getHighlightColor(), "" + deals.size());
    }

    @Override
    public boolean hasSmallDescription() { return true; }

    @Override
    public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
        info.addPara(Str.get("dev.log.intro"), 10f);
        List<DevelopmentContract> deals = BankData.get().getDevelopmentManager().sectorDeals();
        if (deals.isEmpty()) {
            info.addPara(Str.get("dev.log.none"), Misc.getGrayColor(), 10f);
            return;
        }
        for (int i = 0; i < deals.size() && i < SHOWN; i++) {
            DevelopmentContract c = deals.get(i);
            info.addPara(Str.get("dev.log.line"), 6f, Misc.getHighlightColor(), c.investorName(), c.recipientName(),
                c.industryName, c.marketName, Misc.getDGSCredits(c.principal), c.state.getDisplayName());
        }
    }

    @Override
    public Set<String> getIntelTags(SectorMapAPI map) {
        Set<String> tags = super.getIntelTags(map);
        tags.add("Economy");
        return tags;
    }

    @Override
    public String getIcon() {
        FactionAPI pbc = Global.getSector().getFaction("pbc");
        return pbc != null ? pbc.getCrest() : Global.getSettings().getSpriteName("intel", "monthly_income_report");
    }

    @Override
    public IntelSortTier getSortTier() { return IntelSortTier.TIER_3; }
}
