package com.bankofstarsector.compat;

/**
 * Other insurers covering the player's ships. With Nexerelin, its insurer sells per-ship policies that
 * pay 80% of a lost ship's base value right after the battle. Fleet insurance has an "other insurance"
 * clause: it covers only the excess over any other policy in force, so a ship is never paid twice and
 * the premium is charged only on what the Confederation actually insures.
 */
public final class OtherInsurance {

    public interface Source {
        /** Credits another insurer currently promises for this ship's loss (0 = not insured elsewhere). */
        float insuredAmount(String memberId);
        /** Credits another insurer paid for this ship's loss in the last maxAgeDays days. */
        float paidFor(String memberId, float maxAgeDays);
    }

    private static final Source NEXERELIN = new Source() {
        public float insuredAmount(String memberId) { return NexerelinCompat.insuredAmount(memberId); }
        public float paidFor(String memberId, float maxAgeDays) { return NexerelinCompat.insurancePaidFor(memberId, maxAgeDays); }
    };

    /** Set by the off-game tests; null in the game, where Nexerelin's insurer is used when it is enabled. */
    public static Source source = null;

    private OtherInsurance() {}

    private static Source get() {
        if (source != null) return source;
        return NexerelinCompat.isAvailable() ? NEXERELIN : null;
    }

    public static float insuredAmount(String memberId) {
        Source s = get();
        return s == null || memberId == null ? 0f : Math.max(0f, s.insuredAmount(memberId));
    }

    public static float paidFor(String memberId, float maxAgeDays) {
        Source s = get();
        return s == null || memberId == null ? 0f : Math.max(0f, s.paidFor(memberId, maxAgeDays));
    }
}
