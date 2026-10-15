package com.bankofstarsector.economy;

/**
 * Lifecycle of a development contract (0.4.0). Saved: never remove or rename a constant.
 *
 * OFFERED -> BUILDING (funds paid to the recipient) -> REPAYING once built, repaid at a low rate -> CLOSED.
 * BUILDING, deadline missed -> DEMAND: repay with penalty interest by a new deadline, else COLLECTION (the
 * player) or a recovery from the recipient's treasury (an NPC), CLOSED or DEFAULTED when it falls short.
 */
public enum ContractState {
    OFFERED,
    DECLINED,
    EXPIRED,
    BUILDING,
    REPAYING,
    DEMAND,
    COLLECTION,
    CLOSED,
    DEFAULTED;

    /** Under way: money is out and something is still owed or being built. */
    public boolean isOpen() {
        return this == BUILDING || this == REPAYING || this == DEMAND || this == COLLECTION;
    }

    public String getDisplayName() {
        return com.bankofstarsector.core.Str.get("dev.state." + name());
    }
}
