package com.bankofstarsector.compat;

import com.fs.starfarer.api.Global;
import org.apache.log4j.Logger;

/**
 * Optional in-game settings through LunaLib (data/config/LunaSettings.csv).
 * Without LunaLib the mod reads data/config/bos_settings.json only.
 */
public class LunaLibCompat {

    private static final Logger log = Logger.getLogger(LunaLibCompat.class);
    private static Boolean available = null;
    private static boolean listening = false;

    public static boolean isAvailable() {
        if (available == null) {
            available = Global.getSettings().getModManager().isModEnabled("lunalib");
        }
        return available;
    }

    /** Overrides BankSettings with the LunaLib values (no-op if LunaLib is missing or not loaded yet). */
    public static void applyOverrides() {
        if (!isAvailable()) return;
        try {
            LunaLibBridge.applyOverrides();
        } catch (Throwable t) {
            log.warn("BOS: could not read LunaLib settings: " + t);
        }
    }

    /** Re-applies settings whenever the player edits them in LunaLib's menu. */
    public static void listenForChanges() {
        if (!isAvailable() || listening) return;
        try {
            LunaLibBridge.addListener();
            listening = true;
        } catch (Throwable t) {
            log.warn("BOS: could not register LunaLib settings listener: " + t);
        }
    }
}
