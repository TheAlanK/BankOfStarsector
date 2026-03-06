package com.bankofstarsector.compat;

import com.fs.starfarer.api.Global;
import org.apache.log4j.Logger;

import java.lang.reflect.Method;

public class NexerelinCompat {

    private static final Logger log = Logger.getLogger(NexerelinCompat.class);
    private static Boolean available = null;

    public static boolean isAvailable() {
        if (available == null) {
            try {
                Class.forName("exerelin.campaign.SectorManager");
                available = Global.getSettings().getModManager().isModEnabled("nexerelin");
            } catch (ClassNotFoundException e) {
                available = false;
            }
        }
        return available;
    }

    public static int getWarCount() {
        if (!isAvailable()) return 0;
        try {
            Class<?> dipMgr = Class.forName("exerelin.campaign.DiplomacyManager");
            Method getManager = dipMgr.getMethod("getManager");
            Object manager = getManager.invoke(null);
            if (manager == null) return 0;

            // Count active wars by checking faction relationships
            int warCount = 0;
            for (com.fs.starfarer.api.campaign.FactionAPI f1 :
                    Global.getSector().getAllFactions()) {
                if (f1.isNeutralFaction() || f1.isPlayerFaction()) continue;
                for (com.fs.starfarer.api.campaign.FactionAPI f2 :
                        Global.getSector().getAllFactions()) {
                    if (f2.isNeutralFaction() || f2.isPlayerFaction()) continue;
                    if (f1.getId().compareTo(f2.getId()) >= 0) continue;
                    if (f1.isHostileTo(f2)) warCount++;
                }
            }
            return warCount;
        } catch (Exception e) {
            log.warn("BOS: Error getting war count: " + e.getMessage());
            return 0;
        }
    }

    public static boolean areFactionsAtWar(String factionId1, String factionId2) {
        if (!isAvailable()) return false;
        try {
            com.fs.starfarer.api.campaign.FactionAPI f1 = Global.getSector().getFaction(factionId1);
            com.fs.starfarer.api.campaign.FactionAPI f2 = Global.getSector().getFaction(factionId2);
            return f1 != null && f2 != null && f1.isHostileTo(f2);
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isFactionAlive(String factionId) {
        if (!isAvailable()) return true;
        try {
            Class<?> sectorManager = Class.forName("exerelin.campaign.SectorManager");
            Method isLive = sectorManager.getMethod("isFactionAlive", String.class);
            Object result = isLive.invoke(null, factionId);
            return result instanceof Boolean && (Boolean) result;
        } catch (Exception e) {
            return true;
        }
    }
}
