package com.bankofstarsector.core;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CampaignClockAPI;
import com.bankofstarsector.banking.*;
import com.bankofstarsector.collection.*;

import org.apache.log4j.Logger;

public class BankCampaignScript implements EveryFrameScript {

    private static final Logger log = Logger.getLogger(BankCampaignScript.class);

    private int lastDay = -1;
    private int lastMonth = -1;
    private boolean done = false;

    @Override
    public boolean isDone() { return done; }

    @Override
    public boolean runWhilePaused() { return false; }

    @Override
    public void advance(float amount) {
        CampaignClockAPI clock = Global.getSector().getClock();
        int currentDay = clock.getDay();
        int currentMonth = clock.getMonth();

        // Daily tick
        if (currentDay != lastDay) {
            lastDay = currentDay;
            onDailyTick();
        }

        // Monthly tick
        if (currentMonth != lastMonth) {
            if (lastMonth != -1) {
                onMonthlyTick();
            }
            lastMonth = currentMonth;
        }
    }

    private void onDailyTick() {
        BankData data = BankData.get();

        // Advance overdue tracking on loans
        data.getLoanManager().advanceDay();

        // Advance collection events
        data.getCollectionManager().advanceDay(data);

        // Advance bankruptcy recovery
        data.getBankruptcyManager().advanceDay();

        // Advance asset seizures
        data.getAssetSeizureManager().advanceDay(data);
    }

    private void onMonthlyTick() {
        BankData data = BankData.get();
        InterestEngine engine = data.getInterestEngine();

        // Process loan interest
        data.getLoanManager().advanceMonth(engine);
        log.info("BOS: Monthly loan interest processed.");

        // Process investment returns
        data.getInvestmentManager().advanceMonth(engine);
        log.info("BOS: Monthly investment returns processed.");

        // Update credit score
        boolean allCurrent = true;
        for (BankAccount loan : data.getLoanManager().getActiveLoans()) {
            if (loan.status != LoanStatus.ACTIVE) {
                allCurrent = false;
                break;
            }
        }
        data.getCreditScoreManager().advanceMonth(
            data.getInvestmentManager().getTotalValue(),
            data.getLoanManager().getActiveLoanCount(),
            allCurrent
        );
        log.info("BOS: Credit score updated: " + data.getCreditScoreManager().getScore());

        // Check collection escalation
        data.getCollectionManager().checkEscalation(data);

        // Check bankruptcy conditions
        data.getBankruptcyManager().advanceMonth(data);

        // Cleanup old records
        data.getLoanManager().cleanupPaidLoans();
        data.getInvestmentManager().cleanupEmptyInvestments();
    }
}
