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

    /** Faction config flags: a pirate band, or hostile to every other faction (a swarm, a cult). */
    public static final int ROLE_PIRATE = 1, ROLE_HOSTILE_TO_ALL = 2;
    /** Faction configs are static data and the lookup is a linear search: cached until the next game load. */
    private static final java.util.Map<String, Integer> roleFlags = new java.util.HashMap<String, Integer>();

    public static boolean isAvailable() {
        if (available == null) {
            available = Global.getSettings().getModManager().isModEnabled("nexerelin");
        }
        return available;
    }

    /** Forgets cached faction data (on every game load, with the settings). */
    public static void clearCaches() {
        roleFlags.clear();
    }

    /** ROLE_* flags from Nexerelin's faction config; 0 without Nexerelin or without a config for this faction. */
    public static int factionRoleFlags(String factionId) {
        if (!isAvailable() || factionId == null) return 0;
        Integer cached = roleFlags.get(factionId);
        if (cached != null) return cached;
        int flags = 0;
        try {
            flags = NexerelinBridge.factionRoleFlags(factionId);
        } catch (Throwable t) {
            log.warn("BOS: could not read Nexerelin's config for " + factionId + ": " + t);
        }
        roleFlags.put(factionId, flags);
        return flags;
    }

    /** What Nexerelin's insurer pays if this ship is lost (0 without Nexerelin, a policy, or with legacy insurance). */
    public static float insuredAmount(String memberId) {
        if (!isAvailable()) return 0f;
        try {
            return NexerelinBridge.insuredAmount(memberId);
        } catch (Throwable t) {
            return 0f;
        }
    }

    /** What Nexerelin's insurer paid for this ship in the last maxAgeDays days. */
    public static float insurancePaidFor(String memberId, float maxAgeDays) {
        if (!isAvailable()) return 0f;
        try {
            return NexerelinBridge.paidFor(memberId, maxAgeDays);
        } catch (Throwable t) {
            return 0f;
        }
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
