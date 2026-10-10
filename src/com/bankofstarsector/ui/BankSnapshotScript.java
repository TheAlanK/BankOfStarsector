package com.bankofstarsector.ui;

import com.bankofstarsector.compat.NexusUICompat;
import com.fs.starfarer.api.EveryFrameScript;

/**
 * Game-thread publisher for {@link BankSnapshot}. Only registered when NexusUI is enabled.
 * Runs while paused so the dashboard reflects actions taken in the paused Intel screen.
 */
public class BankSnapshotScript implements EveryFrameScript {

    private static final float INTERVAL = 0.5f;
    private float timer = 0f;

    public boolean isDone() { return false; }

    public boolean runWhilePaused() { return true; }

    public void advance(float amount) {
        timer -= amount;
        if (timer > 0f) return;
        timer = INTERVAL;
        BankSnapshot.capture();
        NexusUICompat.ensureDataProvider();
    }
}
