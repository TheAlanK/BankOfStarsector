package com.bankofstarsector.core;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignClockAPI;

/**
 * Daily banking tick. Month-end processing lives in {@link BankEconomyListener}
 * so it lines up with the vanilla monthly report.
 */
public class BankCampaignScript implements EveryFrameScript {

    @Override
    public boolean isDone() { return false; }

    @Override
    public boolean runWhilePaused() { return false; }

    @Override
    public void advance(float amount) {
        CampaignClockAPI clock = Global.getSector().getClock();
        BankData data = BankData.get();
        if (data.getLastDayTimestamp() == 0L) {
            data.setLastDayTimestamp(clock.getTimestamp());
            return;
        }
        // Timestamp-based so reloading a save never replays or skips a day.
        int days = (int) clock.getElapsedDaysSince(data.getLastDayTimestamp());
        if (days < 1) return;
        data.setLastDayTimestamp(clock.getTimestamp());
        for (int i = 0; i < Math.min(days, 10); i++) {
            onDailyTick(data);
        }
    }

    private void onDailyTick(BankData data) {
        data.getLoanManager().advanceDay();
        data.getCollectionManager().advanceDay(data);
        data.getBankruptcyManager().advanceDay();
        data.getAssetSeizureManager().advanceDay(data);
        data.getForeclosureManager().advanceDay(data);
        data.getInsuranceManager().advanceDay(data);
    }
}
