package com.bankofstarsector.faction;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.StarSystemAPI;

import org.apache.log4j.Logger;

/**
 * One-shot script that runs on the first game frame after all initialization.
 * Marks the Aurum system as explored so it appears as a proper core world.
 * This must run AFTER all sector generation is complete (onNewGame,
 * onNewGameAfterEconomyLoad, onNewGameAfterTimePass all get overwritten).
 */
public class PBCPostInitScript implements EveryFrameScript {

    private static final Logger log = Logger.getLogger(PBCPostInitScript.class);
    private boolean done = false;

    @Override
    public void advance(float amount) {
        if (!done) {
            StarSystemAPI system = Global.getSector().getStarSystem("Aurum");
            if (system != null) {
                system.setEnteredByPlayer(true);
                log.info("BOS: Aurum system marked as explored (post-init).");
            }
            done = true;
        }
    }

    @Override
    public boolean isDone() {
        return done;
    }

    @Override
    public boolean runWhilePaused() {
        return true;
    }
}
