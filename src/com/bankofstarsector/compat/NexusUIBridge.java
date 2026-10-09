package com.bankofstarsector.compat;

import com.bankofstarsector.ui.BankingNexusPageFactory;
import com.nexusui.overlay.NexusFrame;

/** Direct NexusUI call. Only touched when {@link NexusUICompat#isAvailable()} is true. */
final class NexusUIBridge {

    private NexusUIBridge() {}

    static void register() {
        NexusFrame.registerPageFactory(new BankingNexusPageFactory());
    }
}
