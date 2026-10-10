package com.bankofstarsector.banking;

import com.bankofstarsector.core.BankSettings;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.SpecialItemData;
import com.fs.starfarer.api.campaign.econ.CommoditySpecAPI;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.campaign.econ.MarketConditionAPI;
import com.fs.starfarer.api.campaign.SpecialItemSpecAPI;

import java.util.HashMap;
import java.util.Map;

/**
 * What a colony is worth to the Confederation: the collateral value behind a colony-secured loan
 * (the loan is a share of it) and the reference price when a foreclosed colony is auctioned.
 *
 *   development  grows with the colony's size
 *   structures   build cost of every industry and structure (+25% if improved), installed AI cores
 *                and special items at their base price
 *   resources    natural resources and traits, by tier (ore, rare ore, organics, volatiles,
 *                farmland, habitability, ruins)
 *   income       a year of positive net income
 *   x hazard     150% - hazard/2, between 50% and 125% (a 100% hazard world counts at face value)
 *
 * Not saved: computed on demand.
 */
public final class ColonyAppraisal {

    public final float development, structures, resources, income, hazardMult, total;

    private static final Map<String, Float> RESOURCE_TIERS = new HashMap<String, Float>();
    static {
        String[][] tiers = {
            {"ore_sparse", "1"}, {"ore_moderate", "2"}, {"ore_abundant", "3"}, {"ore_rich", "4"}, {"ore_ultrarich", "5"},
            {"rare_ore_sparse", "1.5"}, {"rare_ore_moderate", "3"}, {"rare_ore_abundant", "4.5"}, {"rare_ore_rich", "6"}, {"rare_ore_ultrarich", "7.5"},
            {"organics_trace", "1"}, {"organics_common", "2"}, {"organics_abundant", "3"}, {"organics_plentiful", "4"},
            {"volatiles_trace", "1.5"}, {"volatiles_diffuse", "3"}, {"volatiles_abundant", "4.5"}, {"volatiles_plentiful", "6"},
            {"farmland_poor", "1"}, {"farmland_adequate", "2"}, {"farmland_rich", "3"}, {"farmland_bountiful", "4"},
            {"habitable", "2"}, {"mild_climate", "1"},
            {"ruins_scattered", "1"}, {"ruins_widespread", "2"}, {"ruins_extensive", "3"}, {"ruins_vast", "4"},
        };
        for (String[] t : tiers) RESOURCE_TIERS.put(t[0], Float.parseFloat(t[1]));
    }

    private ColonyAppraisal(float development, float structures, float resources, float income, float hazardMult) {
        this.development = development;
        this.structures = structures;
        this.resources = resources;
        this.income = income;
        this.hazardMult = hazardMult;
        this.total = (development + structures + resources + income) * hazardMult;
    }

    public static ColonyAppraisal of(MarketAPI market) {
        float development = BankSettings.APPRAISAL_SIZE_BASE
            * (float) Math.pow(BankSettings.APPRAISAL_SIZE_GROWTH, Math.max(0, market.getSize() - 3));

        float structures = 0f;
        for (Industry ind : market.getIndustries()) {
            structures += ind.getBuildCost() * (ind.isImproved() ? 1f + BankSettings.APPRAISAL_IMPROVED_BONUS : 1f);
            String core = ind.getAICoreId();
            if (core != null) {
                CommoditySpecAPI spec = Global.getSettings().getCommoditySpec(core);
                if (spec != null) structures += spec.getBasePrice();
            }
            SpecialItemData item = ind.getSpecialItem();
            if (item != null) {
                SpecialItemSpecAPI spec = Global.getSettings().getSpecialItemSpec(item.getId());
                if (spec != null) structures += spec.getBasePrice();
            }
        }

        float tiers = 0f;
        for (MarketConditionAPI c : market.getConditions()) {
            Float t = RESOURCE_TIERS.get(c.getId());
            if (t != null) tiers += t;
        }
        float resources = tiers * BankSettings.APPRAISAL_RESOURCE_TIER_VALUE;

        float income = Math.max(0f, market.getNetIncome()) * BankSettings.APPRAISAL_INCOME_MONTHS;
        float hazard = market.getHazardValue() > 0f ? market.getHazardValue() : 1f;
        float hazardMult = Math.max(0.5f, Math.min(1.25f, 1.5f - 0.5f * hazard));
        return new ColonyAppraisal(development, structures, resources, income, hazardMult);
    }
}
