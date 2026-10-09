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

    public static boolean areFactionsAtWar(String factionId1, String factionId2) {
        com.fs.starfarer.api.campaign.FactionAPI f1 = Global.getSector().getFaction(factionId1);
        com.fs.starfarer.api.campaign.FactionAPI f2 = Global.getSector().getFaction(factionId2);
        return f1 != null && f2 != null && f1.isHostileTo(f2);
    }
}
