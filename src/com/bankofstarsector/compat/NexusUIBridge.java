package com.bankofstarsector.compat;

import com.bankofstarsector.ui.BankSnapshot;
import com.bankofstarsector.ui.BankingNexusPageFactory;
import com.nexusui.bridge.GameDataBridge;
import com.nexusui.overlay.NexusFrame;

/** Direct NexusUI calls. Only touched when {@link NexusUICompat#isAvailable()} is true. */
final class NexusUIBridge {

    /** The GameDataBridge we registered with; NexusUI creates a new one per campaign load. */
    private static Object registeredWith = null;

    private NexusUIBridge() {}

    static void register() {
        NexusFrame.registerPageFactory(new BankingNexusPageFactory());
    }

    /**
     * Exposes the account snapshot through NexusUI's data bridge (and its REST API).
     * NexusUI reads providers off the game thread, so this only returns the published snapshot.
     */
    static void ensureDataProvider() {
        GameDataBridge bridge = GameDataBridge.getInstance();
        if (bridge == null || bridge == registeredWith) return;
        bridge.registerProvider("pbc_banking", new GameDataBridge.DataProvider() {
            public String getData() {
                BankSnapshot s = BankSnapshot.get();
                return s == null ? "{}" : s.toJson();
            }
        });
        registeredWith = bridge;
    }
}
