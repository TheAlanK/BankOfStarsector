package com.bankofstarsector.economy;

import java.io.Serializable;

/**
 * A faction's state finances, as the Sovereign Desk sees them (0.4.0). Updated once a month by
 * {@link SectorEconomy}: the faction collects the net income of its markets, pays the extra cost of its
 * wars and the interest on its debt, borrows from the Confederation when it runs short, repays when it
 * has a surplus, and defaults when its debt grows past what its economy can carry. Its sovereign debt
 * itself is kept where it always was (AssetSeizureManager), so older saves keep their debts.
 */
public class FactionTreasury implements Serializable {

    private static final long serialVersionUID = 1L;

    public String factionId;
    /** Cash on hand. */
    public float balance;
    // Last month's figures, for the terminal and the tests.
    public float income, gross, warCost, militarySpending, interest, couponsPaid, borrowed, repaid;
    public int wars, markets;
    public float stability;
    /** Annual yield on this faction's debt, now and a month ago (bond prices move with the change). */
    public float yield, previousYield;
    /** Timestamp of the last default (0 = never); a recent default raises the yield. */
    public long defaultedTs;

    public FactionTreasury() {}

    FactionTreasury(String factionId) {
        this.factionId = factionId;
    }
}
