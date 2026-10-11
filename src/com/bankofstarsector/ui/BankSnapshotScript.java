package com.bankofstarsector.ui;

import com.bankofstarsector.compat.NexusUICompat;
import com.fs.starfarer.api.EveryFrameScript;

/**
 * Game-thread publisher for {@link BankSnapshot}. Only registered when NexusUI is enabled.
 * Runs while paused so the dashboard reflects actions taken in the paused Intel screen.
 * Captures twice a second while the page or the data API is reading it, and only every
 * ten seconds otherwise, so an unopened dashboard costs (almost) nothing.
 */
public class BankSnapshotScript implements EveryFrameScript {

    private static final float INTERVAL = 0.5f;
    private static final float IDLE_INTERVAL = 10f;
    private float timer = 0f;

    public boolean isDone() { return false; }

    public boolean runWhilePaused() { return true; }

    public void advance(float amount) {
        timer -= amount;
        if (timer > 0f) return;
        timer = BankSnapshot.isWatched() ? INTERVAL : IDLE_INTERVAL;
        BankSnapshot.capture();
        NexusUICompat.ensureDataProvider();
    }
}
