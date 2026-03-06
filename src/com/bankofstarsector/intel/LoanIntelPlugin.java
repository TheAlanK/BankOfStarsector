package com.bankofstarsector.intel;

import com.fs.starfarer.api.impl.campaign.intel.BaseIntelPlugin;
import com.fs.starfarer.api.ui.TooltipMakerAPI;
import com.fs.starfarer.api.util.Misc;

import java.awt.Color;

public class LoanIntelPlugin extends BaseIntelPlugin {

    private String title;
    private String description;
    private String loanAccountId;
    private int phase;
    private boolean ended;

    public LoanIntelPlugin(String title, String description, String loanAccountId, int phase) {
        this.title = title;
        this.description = description;
        this.loanAccountId = loanAccountId;
        this.phase = phase;
        this.ended = false;
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
    public String getIcon() {
        return "graphics/icons/intel/credits.png";
    }

    @Override
    public boolean hasLargeDescription() { return false; }

    @Override
    public boolean isEnded() { return ended; }

    @Override
    public boolean isEnding() { return ended; }

    public void endEvent() {
        ended = true;
        endAfterDelay();
    }

    public String getLoanAccountId() { return loanAccountId; }
    public int getPhase() { return phase; }
}
