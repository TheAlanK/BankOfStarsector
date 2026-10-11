package com.bankofstarsector.economy;

import java.io.Serializable;

/**
 * The Confederation's books (0.4.0): where the money paid to investors comes from and where losses go.
 * Every return credited to the player is recorded here against its payer, so the books always balance:
 * a faction treasury (bond coupons), the PBC itself (deposit interest out of its loan income), the
 * commodity markets (price changes), the companies in the venture portfolio (their earnings) and the
 * factions' military spending (contractors' margin).
 */
public class PbcLedger implements Serializable {

    private static final long serialVersionUID = 1L;

    /** The PBC's own capital: what absorbs its losses. */
    public float reserves;

    // This month (reset at the start of each month end) ...
    public float loanInterestIn, sovereignInterestIn, feesIn, depositInterestOut, rebatesOut, writeOffs;
    // ... and the player's returns by payer, this month.
    public float paidByTreasuries, paidByPbc, paidByCommodityMarkets, paidByCompanies, paidByMilitarySpending, bondPriceChanges;
    public float lostToDefaults;

    // Since the books were opened.
    public float totalInterestIn, totalInterestOut, totalWriteOffs, totalPaidToPlayer;

    void startMonth() {
        loanInterestIn = sovereignInterestIn = feesIn = depositInterestOut = rebatesOut = writeOffs = 0f;
        paidByTreasuries = paidByPbc = paidByCommodityMarkets = paidByCompanies = paidByMilitarySpending = 0f;
        bondPriceChanges = lostToDefaults = 0f;
    }

    void closeMonth() {
        float in = loanInterestIn + sovereignInterestIn + feesIn;
        float out = depositInterestOut + rebatesOut + writeOffs;
        reserves += in - out;
        totalInterestIn += loanInterestIn + sovereignInterestIn;
        totalInterestOut += depositInterestOut;
        totalWriteOffs += writeOffs;
    }

    /** Sum of this month's player returns by payer, minus losses and fees (equals the investments' total return). */
    public float playerReturnsThisMonth() {
        return paidByTreasuries + paidByPbc + paidByCommodityMarkets + paidByCompanies + paidByMilitarySpending
            + bondPriceChanges - lostToDefaults - feesIn;
    }
}
