package com.bankofstarsector.economy;

import com.bankofstarsector.compat.NexerelinCompat;
import com.bankofstarsector.core.BankSettings;
import com.fs.starfarer.api.campaign.FactionAPI;

import java.util.HashSet;
import java.util.Set;

/**
 * Which factions are states the Sovereign Desk deals with: they issue bonds, keep a treasury, count
 * in the war surcharge and bid at foreclosure auctions.
 *
 * Big mod lists bring factions that hold markets without being political: swarms and raiders that are
 * hostile to everyone (the Hiver Swarm holds over a dozen size 4+ worlds), pirate bands, cults. Their
 * permanent wars say nothing about the Sector's political climate, so they are left out like the
 * vanilla pirates and the Pather cells. The classification only reads static data (faction files,
 * Nexerelin's faction config, bos_settings.json), never live relations: a faction that drops out of
 * the desk would otherwise look like one that lost all its markets.
 */
public final class PoliticalFactions {

    /** Vanilla factions whose hostility says nothing about the Sector's politics. */
    private static final Set<String> BUILT_IN = new HashSet<String>();
    static {
        String[] ids = {"pirates", "luddic_path", "remnant", "derelict", "omega", "threat", "dweller",
            "neutral", "poor", "scavengers", "mercenary", "sleeper", "player"};
        for (String id : ids) BUILT_IN.add(id);
    }

    /** Vanilla faction custom flag ("custom" block of a .faction file) used by pirate-like factions. */
    private static final String PIRATE_BEHAVIOR = "pirateBehavior";

    private PoliticalFactions() {}

    public static boolean isPolitical(FactionAPI f) {
        return f != null && exclusionReason(f) == null;
    }

    /**
     * Null for a political faction, else why it is left out, as a string key ("politics.reason.*"):
     * the player or the PBC itself, a vanilla non-political faction, a faction file with pirate
     * behaviour, Nexerelin's pirate or hostile-to-all flag, or bos_settings.json.
     */
    public static String exclusionReason(FactionAPI f) {
        if (f == null) return "politics.reason.self";
        String id = f.getId();
        if (f.isPlayerFaction() || "pbc".equals(id)) return "politics.reason.self";
        if (BankSettings.POLITICAL_FACTIONS.contains(id)) return null;
        if (BankSettings.NON_POLITICAL_FACTIONS.contains(id)) return "politics.reason.settings";
        if (BUILT_IN.contains(id)) return "politics.reason.builtIn";
        if (f.getCustomBoolean(PIRATE_BEHAVIOR)) return "politics.reason.pirateBehavior";
        int nex = NexerelinCompat.factionRoleFlags(id);
        if ((nex & NexerelinCompat.ROLE_PIRATE) != 0) return "politics.reason.nexPirate";
        if ((nex & NexerelinCompat.ROLE_HOSTILE_TO_ALL) != 0) return "politics.reason.nexHostileToAll";
        return null;
    }

    /** Pirate-like: the PBC treats it like the vanilla pirates (used when a new game sets relations). */
    public static boolean isRaider(FactionAPI f) {
        if (f == null || f.isPlayerFaction() || "pbc".equals(f.getId())) return false;
        if (BankSettings.POLITICAL_FACTIONS.contains(f.getId())) return false;
        return f.getCustomBoolean(PIRATE_BEHAVIOR) || NexerelinCompat.factionRoleFlags(f.getId()) != 0;
    }
}
