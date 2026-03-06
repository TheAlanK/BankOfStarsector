package com.bankofstarsector.faction;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.impl.campaign.ids.Factions;

import org.apache.log4j.Logger;

public class PBCFactionSetup {

    private static final Logger log = Logger.getLogger(PBCFactionSetup.class);
    public static final String FACTION_ID = "pbc";

    public static void setup() {
        FactionAPI pbc = Global.getSector().getFaction(FACTION_ID);
        if (pbc == null) {
            log.error("PBC faction not found! Faction file may be missing.");
            return;
        }

        // Set initial relationships with all major factions
        setRelation(pbc, Factions.HEGEMONY, 0.3f);
        setRelation(pbc, Factions.PERSEAN, 0.35f);
        setRelation(pbc, Factions.TRITACHYON, 0.25f);
        setRelation(pbc, Factions.DIKTAT, 0.2f);
        setRelation(pbc, Factions.LUDDIC_CHURCH, 0.1f);
        setRelation(pbc, Factions.LUDDIC_PATH, -0.3f);
        setRelation(pbc, Factions.PIRATES, -0.5f);
        setRelation(pbc, Factions.INDEPENDENT, 0.15f);
        setRelation(pbc, Factions.PLAYER, 0.0f);
        setRelation(pbc, Factions.REMNANTS, -0.5f);

        log.info("PBC faction relationships configured.");
    }

    public static void setupNexerelinRelationships() {
        // Nexerelin-specific relationship tuning via their config system
        // The pbc.json in exerelinFactionConfig handles most of this.
        // This method is called to ensure runtime overrides if needed.
        FactionAPI pbc = Global.getSector().getFaction(FACTION_ID);
        if (pbc == null) return;

        try {
            Class<?> sectorManager = Class.forName("exerelin.campaign.SectorManager");
            java.lang.reflect.Method corvusMode = sectorManager.getMethod("getCorvusMode");
            Boolean isCorvus = (Boolean) corvusMode.invoke(null);

            if (isCorvus != null && isCorvus) {
                log.info("PBC: Corvus mode detected, applying Nex relationships.");
                // Relationships are already set in pbc.json startRelationships
                // Just ensure min relationships hold
            }
        } catch (Exception e) {
            log.info("PBC: Nexerelin class reflection issue (non-critical): " + e.getMessage());
        }
    }

    private static void setRelation(FactionAPI faction, String otherId, float relation) {
        FactionAPI other = Global.getSector().getFaction(otherId);
        if (other != null) {
            faction.setRelationship(otherId, relation);
        }
    }
}
