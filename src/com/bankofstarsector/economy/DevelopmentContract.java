package com.bankofstarsector.economy;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;

import java.io.Serializable;

/**
 * One development financing contract (0.4.0): an investor (a faction, the PBC or the player) pays for a
 * building at a recipient's market; the recipient builds it by a deadline and repays at a low rate. The
 * Persean Banking Confederation holds the funds in escrow at its Clearing Hall and enforces the terms.
 */
public class DevelopmentContract implements Serializable {

    private static final long serialVersionUID = 1L;

    public String id;
    /** Faction ids; the player is "player", the Confederation "pbc". */
    public String investorId, recipientId;
    public String marketId, marketName, industryId, industryName;
    /** Paid to the recipient when the contract is signed: the building's cost plus a contingency margin. */
    public float principal;
    /** What the building costs to put up (the recipient pays it out of the principal). */
    public float buildCost;
    /** Monthly repayment rate, and the penalty rate once the construction deadline is missed. */
    public float rate, penaltyRate;
    public int repayMonths, monthsRepaid;
    /** Days from signing to the construction deadline: the building's build time plus slack. */
    public float deadlineDays;
    public long offeredTs, acceptedTs, deadlineTs, builtTs, demandDeadlineTs, closedTs;
    public ContractState state;
    /** Owed to the investor (NPC recipients; for the player the loan account holds the balance). */
    public float owed;
    /** Paid back to the investor so far, including any recovery. */
    public float repaid;
    /** The player's loan account when the player is the recipient. */
    public String loanAccountId;
    /** Why it closed ("dev.outcome.*"), for the logs. */
    public String outcome;

    public boolean investorIsPlayer() { return DevelopmentManager.PLAYER.equals(investorId); }
    public boolean recipientIsPlayer() { return DevelopmentManager.PLAYER.equals(recipientId); }
    public boolean involvesPlayer() { return investorIsPlayer() || recipientIsPlayer(); }

    public String investorName() { return partyName(investorId); }
    public String recipientName() { return partyName(recipientId); }

    public static String partyName(String factionId) {
        if (DevelopmentManager.PLAYER.equals(factionId)) return com.bankofstarsector.core.Str.get("dev.you");
        FactionAPI f = factionId == null ? null : Global.getSector().getFaction(factionId);
        return f != null ? f.getDisplayName() : String.valueOf(factionId);
    }

    /** Days until a timestamp (negative once past). */
    public static int daysUntil(long ts) {
        return (int) Math.ceil(-Global.getSector().getClock().getElapsedDaysSince(ts));
    }
}
