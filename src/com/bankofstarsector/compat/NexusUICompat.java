package com.bankofstarsector.compat;

import com.fs.starfarer.api.Global;
import org.apache.log4j.Logger;

import java.lang.reflect.Method;

public class NexusUICompat {

    private static final Logger log = Logger.getLogger(NexusUICompat.class);
    private static Boolean available = null;

    public static boolean isAvailable() {
        if (available == null) {
            try {
                Class.forName("com.nexusui.overlay.NexusFrame");
                available = Global.getSettings().getModManager().isModEnabled("nexus_ui");
            } catch (ClassNotFoundException e) {
                available = false;
            }
        }
        return available;
    }

    public static void registerBankingPage() {
        if (!isAvailable()) return;
        try {
            // Create factory instance
            Object factory = new com.bankofstarsector.ui.BankingNexusPageFactory();

            // Register via NexusFrame.registerPageFactory(factory)
            Class<?> nexusFrame = Class.forName("com.nexusui.overlay.NexusFrame");
            Method registerFactory = nexusFrame.getMethod("registerPageFactory",
                Class.forName("com.nexusui.api.NexusPageFactory"));
            registerFactory.invoke(null, factory);

            log.info("BOS: Banking page registered with NexusUI.");
        } catch (Exception e) {
            log.warn("BOS: Failed to register NexusUI page: " + e.getMessage());
        }
    }
}
