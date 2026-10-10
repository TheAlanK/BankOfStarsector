package com.bankofstarsector.compat;

import com.fs.starfarer.api.Global;
import org.apache.log4j.Logger;

/**
 * Nexerelin integration without reflection.
 *
 * Starsector's script class loader (com.fs.starfarer.loading.scripts.B) rejects any
 * java.lang.reflect class with "File access and reflection are not allowed to scripts",
 * which silently broke the 0.1.x integration. Calls now go through {@link NexerelinBridge},
 * compiled against ExerelinCore.jar and only loaded by the JVM when Nexerelin is enabled.
 */
public class NexerelinCompat {

    private static final Logger log = Logger.getLogger(NexerelinCompat.class);
    private static Boolean available = null;

    public static boolean isAvailable() {
        if (available == null) {
            available = Global.getSettings().getModManager().isModEnabled("nexerelin");
        }
        return available;
    }

    public static boolean isFactionAlive(String factionId) {
        if (!isAvailable()) return true;
        try {
            return NexerelinBridge.isFactionAlive(factionId);
        } catch (Throwable t) {
            return true;
        }
    }

    public static boolean isCorvusMode() {
        if (!isAvailable()) return true;
        try {
            return NexerelinBridge.isCorvusMode();
        } catch (Throwable t) {
            log.warn("BOS: Nexerelin corvus-mode check failed: " + t);
            return true;
        }
    }

    /** Sends a Nexerelin invasion from source against target. False without Nexerelin or on failure. */
    public static boolean launchInvasion(com.fs.starfarer.api.campaign.econ.MarketAPI source,
                                         com.fs.starfarer.api.campaign.econ.MarketAPI target) {
        if (!isAvailable()) return false;
        try {
            return NexerelinBridge.launchInvasion(source, target);
        } catch (Throwable t) {
            log.warn("BOS: could not launch the foreclosure invasion: " + t);
            return false;
        }
    }

    /**
     * Hands a market to another faction. Nexerelin's transfer handles everything that goes with it
     * (submarkets, admin, notifications); without Nexerelin (only reached off-game in tests, since
     * foreclosure auctions follow a Nexerelin invasion) the faction is simply switched.
     */
    public static void transferMarket(com.fs.starfarer.api.campaign.econ.MarketAPI market,
                                      com.fs.starfarer.api.campaign.FactionAPI newOwner,
                                      com.fs.starfarer.api.campaign.FactionAPI oldOwner) {
        if (newOwner == null) return;
        if (isAvailable()) {
            try {
                NexerelinBridge.transferMarket(market, newOwner, oldOwner);
                return;
            } catch (Throwable t) {
                log.warn("BOS: Nexerelin market transfer failed, switching the faction directly: " + t);
            }
        }
        market.setFactionId(newOwner.getId());
        if (market.getPrimaryEntity() != null) market.getPrimaryEntity().setFaction(newOwner.getId());
    }

    public static boolean areFactionsAtWar(String factionId1, String factionId2) {
        com.fs.starfarer.api.campaign.FactionAPI f1 = Global.getSector().getFaction(factionId1);
        com.fs.starfarer.api.campaign.FactionAPI f2 = Global.getSector().getFaction(factionId2);
        return f1 != null && f2 != null && f1.isHostileTo(f2);
    }
}
