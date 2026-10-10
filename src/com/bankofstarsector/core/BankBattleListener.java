package com.bankofstarsector.core;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.BaseCampaignEventListener;
import com.fs.starfarer.api.campaign.BattleAPI;
import com.fs.starfarer.api.campaign.CampaignFleetAPI;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;

/**
 * Hands the player's battle losses to fleet insurance. The game reports a battle when it is cleaned
 * up, after the post-battle recovery screen; InsuranceManager still waits a couple of days before
 * paying, so a ship that comes back to the fleet is never paid. Registered as a transient listener
 * on every game load, so it is never saved.
 */
public class BankBattleListener extends BaseCampaignEventListener {

    private static final Logger log = Logger.getLogger(BankBattleListener.class);

    public BankBattleListener() {
        super(false);
    }

    @Override
    public void reportBattleOccurred(CampaignFleetAPI primaryWinner, BattleAPI battle) {
        if (battle == null || !battle.isPlayerInvolved()) return;
        try {
            CampaignFleetAPI player = Global.getSector().getPlayerFleet();
            BankData.get().getInsuranceManager().onShipsLost(Misc.getSnapshotMembersLost(player));
        } catch (Exception e) {
            log.error("BOS: could not record battle losses for insurance", e);
        }
    }
}
