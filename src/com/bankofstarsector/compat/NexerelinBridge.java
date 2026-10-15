package com.bankofstarsector.compat;

import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import exerelin.campaign.ExerelinSetupData;
import exerelin.campaign.SectorManager;
import exerelin.campaign.fleets.InvasionFleetManager;
import exerelin.campaign.intel.InsuranceIntelV2;
import exerelin.campaign.intel.invasion.InvasionIntel;
import exerelin.utilities.NexConfig;
import exerelin.utilities.NexFactionConfig;

/**
 * Direct calls into Nexerelin. Never reference this class unless
 * {@link NexerelinCompat#isAvailable()} is true, so it is never loaded without Nexerelin.
 */
final class NexerelinBridge {

    private NexerelinBridge() {}

    /** Same as Nexerelin's own SpawnInvasionFleet console command. */
    static boolean launchInvasion(MarketAPI source, MarketAPI target) {
        FactionAPI attacker = source.getFaction();
        float fp = InvasionFleetManager.getWantedFleetSize(attacker, target, 0.2f, false);
        fp *= InvasionFleetManager.getInvasionSizeMult(attacker.getId());
        InvasionIntel intel = new InvasionIntel(attacker, source, target, fp, 1);
        intel.init();
        return true;
    }

    static void transferMarket(MarketAPI market, FactionAPI newOwner, FactionAPI oldOwner) {
        SectorManager.transferMarket(market, newOwner, oldOwner, false, false, null, 0f, false);
    }

    static boolean isFactionAlive(String factionId) {
        return SectorManager.isFactionAlive(factionId);
    }

    /** Nexerelin's faction config flags (pirate band, hostile to everyone); 0 for a faction without a config. */
    static int factionRoleFlags(String factionId) {
        NexFactionConfig c = NexConfig.getFactionConfig(factionId, false);
        if (c == null) return 0;
        int flags = 0;
        if (c.pirateFaction) flags |= NexerelinCompat.ROLE_PIRATE;
        if (c.hostileToAll > 0) flags |= NexerelinCompat.ROLE_HOSTILE_TO_ALL;
        return flags;
    }

    /** Nexerelin's per-ship insurer, or null (legacy commission insurance, or no campaign yet). */
    private static InsuranceIntelV2 insurer() {
        if (NexConfig.legacyInsurance) return null;
        SectorManager manager = SectorManager.getManager();
        return manager != null ? manager.getInsurance() : null;
    }

    /** What Nexerelin's policy on this ship pays if it is lost (0 = not insured there). */
    static float insuredAmount(String memberId) {
        InsuranceIntelV2 ins = insurer();
        if (ins == null) return 0f;
        InsuranceIntelV2.InsurancePolicy p = ins.getPolicies().get(memberId);
        return p != null ? p.insuredAmount : 0f;
    }

    /** What Nexerelin's insurer paid for this ship in the last maxAgeDays days (it pays right after the battle). */
    static float paidFor(String memberId, float maxAgeDays) {
        InsuranceIntelV2 ins = insurer();
        if (ins == null) return 0f;
        float paid = 0f;
        for (InsuranceIntelV2.InsuranceClaim c : ins.getClaimsHistory()) {
            if (c.member != null && memberId.equals(c.member.getId()) && c.age <= maxAgeDays) paid += c.payment;
        }
        return paid;
    }

    /**
     * SectorManager only exists once the campaign is running; during onNewGame the choice
     * made in the new-game dialog lives in ExerelinSetupData.
     */
    static boolean isCorvusMode() {
        try {
            SectorManager manager = SectorManager.getManager();
            if (manager != null) return manager.isCorvusMode(); // the static getCorvusMode() is deprecated
        } catch (Throwable ignored) {
            // fall through to the setup data
        }
        ExerelinSetupData setup = ExerelinSetupData.getInstance();
        return setup == null || setup.corvusMode;
    }
}
