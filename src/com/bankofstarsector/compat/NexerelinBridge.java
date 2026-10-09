package com.bankofstarsector.compat;

import exerelin.campaign.ExerelinSetupData;
import exerelin.campaign.SectorManager;

/**
 * Direct calls into Nexerelin. Never reference this class unless
 * {@link NexerelinCompat#isAvailable()} is true, so it is never loaded without Nexerelin.
 */
final class NexerelinBridge {

    private NexerelinBridge() {}

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
