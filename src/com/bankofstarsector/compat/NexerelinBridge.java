package com.bankofstarsector.compat;

import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import exerelin.campaign.ExerelinSetupData;
import exerelin.campaign.SectorManager;
import exerelin.campaign.fleets.InvasionFleetManager;
import exerelin.campaign.intel.invasion.InvasionIntel;

/**
 * Direct calls into Nexerelin. Never reference this class unless
 * {@link NexerelinCompat#isAvailable()} is true, so it is never loaded without Nexerelin.
 */
final class NexerelinBridge {

    private NexerelinBridge() {}

    /** Same as Nexerelin's own SpawnInvasionFleet console command. */
    static boolean launchInvasion(MarketAPI source, MarketAPI target) {
        FactionAPI attacker = source.getFaction();
        float fp = InvasionFleetManager.getWantedFleetSize(attacker, target, 0.2f, false);
        fp *= InvasionFleetManager.getInvasionSizeMult(attacker.getId());
        InvasionIntel intel = new InvasionIntel(attacker, source, target, fp, 1);
        intel.init();
        return true;
    }

    static void transferMarket(MarketAPI market, FactionAPI newOwner, FactionAPI oldOwner) {
        SectorManager.transferMarket(market, newOwner, oldOwner, false, false, null, 0f, false);
    }

    static boolean isFactionAlive(String factionId) {
        return SectorManager.isFactionAlive(factionId);
    }

    /**
     * SectorManager only exists once the campaign is running; during onNewGame the choice
     * made in the new-game dialog lives in ExerelinSetupData.
     */
    static boolean isCorvusMode() {
        try {
            if (SectorManager.getManager() != null) return SectorManager.getCorvusMode();
        } catch (Throwable ignored) {
            // fall through to the setup data
        }
        ExerelinSetupData setup = ExerelinSetupData.getInstance();
        return setup == null || setup.corvusMode;
    }
}
