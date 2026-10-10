package com.bankofstarsector.compat;

import com.fs.starfarer.api.Global;
import org.apache.log4j.Logger;

/**
 * NexusUI integration without reflection (see {@link NexerelinCompat} for why).
 * {@link NexusUIBridge} references NexusUI types and is only loaded when NexusUI is enabled.
 */
public class NexusUICompat {

    private static final Logger log = Logger.getLogger(NexusUICompat.class);
    private static Boolean available = null;
    private static boolean registered = false;

    public static boolean isAvailable() {
        if (available == null) {
            available = Global.getSettings().getModManager().isModEnabled("nexus_ui");
        }
        return available;
    }

    /** Registers the banking page once per application run; returns whether it is registered. */
    public static boolean registerBankingPage() {
        if (!isAvailable()) return false;
        if (registered) return true;
        try {
            NexusUIBridge.register();
            registered = true;
            log.info("BOS: Banking page registered with NexusUI.");
        } catch (Throwable t) {
            log.warn("BOS: Failed to register NexusUI page: " + t);
        }
        return registered;
    }

    /** Registers the bank's data provider with the current NexusUI data bridge (game thread). */
    public static void ensureDataProvider() {
        if (!isAvailable()) return;
        try {
            NexusUIBridge.ensureDataProvider();
        } catch (Throwable t) {
            // NexusUI not initialised yet, or an incompatible version: the page still works.
        }
    }
}
