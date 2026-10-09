package com.bankofstarsector.intel;

import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import java.awt.Color;

/** One escalation notice for one loan. Ended (and later removed) when superseded or resolved. */
public class LoanIntelPlugin extends BaseIntelPlugin {

    private String title;
    private String description;
    private String loanAccountId;
    private int phase;
    /** Unused since 0.2.0; kept so 0.1.x saves still deserialize. */
    @SuppressWarnings("unused")
    private boolean ended;

    public LoanIntelPlugin(String title, String description, String loanAccountId, int phase) {
        this.title = title;
        this.description = description;
        this.loanAccountId = loanAccountId;
        this.phase = phase;
    }

    @Override
    public void createIntelInfo(TooltipMakerAPI info, ListInfoMode mode) {
        Color c = getTitleColor(mode);
        info.addPara(title, c, 0f);

        Color textColor = phase >= 3 ? Misc.getNegativeHighlightColor() :
                          phase >= 2 ? Misc.getHighlightColor() : Misc.getGrayColor();
        info.addPara(description, textColor, 3f);
    }

    @Override
    public boolean hasSmallDescription() { return true; }

    @Override
    public void createSmallDescription(TooltipMakerAPI info, float width, float height) {
        info.addPara(description, 10f);
        info.addPara("Open the PBC Banking Terminal (Intel > Economy) to pay the past-due amount.",
            Misc.getGrayColor(), 10f);
    }

    @Override
    public String getIcon() {
        com.fs.starfarer.api.campaign.FactionAPI pbc = com.fs.starfarer.api.Global.getSector().getFaction("pbc");
        return pbc != null ? pbc.getCrest() : com.fs.starfarer.api.Global.getSettings().getSpriteName("intel", "monthly_income_report");
    }

    @Override
    public boolean isImportant() { return phase >= 3 && !isEnding() && !isEnded(); }

    public void endEvent() {
        if (!isEnding() && !isEnded()) endAfterDelay();
    }

    public String getLoanAccountId() { return loanAccountId; }
    public int getPhase() { return phase; }
}
