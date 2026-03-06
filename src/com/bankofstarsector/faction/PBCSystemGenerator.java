package com.bankofstarsector.faction;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.*;
import com.fs.starfarer.api.impl.campaign.procgen.NebulaEditor;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;
import com.fs.starfarer.api.impl.campaign.terrain.HyperspaceTerrainPlugin;
import com.fs.starfarer.api.util.Misc;

import org.apache.log4j.Logger;

import java.awt.Color;

public class PBCSystemGenerator {

    private static final Logger log = Logger.getLogger(PBCSystemGenerator.class);
    public static final String FACTION_ID = "pbc";

    /**
     * Phase 1: Create star system, planets, and orbital entities.
     * Called during onNewGame() BEFORE economy is loaded.
     */
    public static void generateSystem() {
        SectorAPI sector = Global.getSector();

        if (sector.getStarSystem("Aurum") != null) {
            log.info("Aurum system already exists, skipping generation.");
            return;
        }

        StarSystemAPI system = sector.createStarSystem("Aurum");
        system.getLocation().set(-4000, 6000);

        // Mark as a core world system
        system.addTag(Tags.THEME_CORE);
        system.addTag(Tags.THEME_CORE_POPULATED);
        system.setProcgen(false);
        system.setType(StarSystemGenerator.StarSystemType.SINGLE);
        system.setBackgroundTextureFilename("graphics/backgrounds/background1.jpg");

        // Star
        PlanetAPI star = system.initStar("aurum_star", "star_yellow", 800f, 400f);
        system.setLightColor(new Color(255, 245, 200));

        // Planet 1: Bullion - HQ world (terran)
        PlanetAPI bullion = system.addPlanet("pbc_bullion", star, "Bullion", "terran", 30, 150, 3500, 250);
        bullion.setCustomDescriptionId("pbc_bullion");
        bullion.setDiscoverable(false);

        // Planet 2: Vault - Barren secure world
        PlanetAPI vault = system.addPlanet("pbc_vault", star, "Vault", "barren", 150, 80, 5500, 350);
        vault.setCustomDescriptionId("pbc_vault");
        vault.setDiscoverable(false);

        // Planet 3: Ledger - Gas giant
        PlanetAPI ledger = system.addPlanet("pbc_ledger", star, "Ledger", "gas_giant", 270, 300, 8000, 500);
        ledger.setCustomDescriptionId("pbc_ledger");
        ledger.setDiscoverable(false);

        // Ledger Station (in orbit around gas giant)
        SectorEntityToken ledgerStation = system.addCustomEntity("pbc_ledger_station",
            "Ledger Station", "station_mining00", FACTION_ID);
        ledgerStation.setCircularOrbitPointingDown(ledger, 0, 500, 40);
        ledgerStation.setDiscoverable(false);

        // Ring band decoration
        system.addRingBand(star, "misc", "rings_dust0", 256f, 3, Color.WHITE, 256f, 2000, 120f, null, null);

        // Comm relay
        SectorEntityToken relay = system.addCustomEntity("pbc_relay", "Aurum Relay",
            "comm_relay", FACTION_ID);
        relay.setCircularOrbitPointingDown(star, 180, 4500, 300);
        relay.setDiscoverable(false);

        // Nav buoy
        SectorEntityToken navBuoy = system.addCustomEntity("pbc_nav_buoy", "Aurum Nav Buoy",
            "nav_buoy", FACTION_ID);
        navBuoy.setCircularOrbitPointingDown(star, 90, 6000, 400);
        navBuoy.setDiscoverable(false);

        // Sensor array
        SectorEntityToken sensor = system.addCustomEntity("pbc_sensor", "Aurum Sensor Array",
            "sensor_array", FACTION_ID);
        sensor.setCircularOrbitPointingDown(star, 270, 6500, 420);
        sensor.setDiscoverable(false);

        // Generate hyperspace jump points
        system.autogenerateHyperspaceJumpPoints(true, true);

        // Mark all jump points as non-discoverable too
        for (SectorEntityToken entity : system.getAllEntities()) {
            if (entity.hasTag(Tags.JUMP_POINT)) {
                entity.setDiscoverable(false);
            }
        }

        // Clear hyperspace nebula around the system (like vanilla core worlds)
        HyperspaceTerrainPlugin plugin = (HyperspaceTerrainPlugin) Misc.getHyperspaceTerrain().getPlugin();
        NebulaEditor editor = new NebulaEditor(plugin);
        float minRadius = plugin.getTileSize() * 2f;
        float radius = system.getMaxRadiusInHyperspace();
        editor.clearArc(system.getLocation().x, system.getLocation().y, 0, radius + minRadius * 0.5f, 0, 360f);
        editor.clearArc(system.getLocation().x, system.getLocation().y, 0, radius + minRadius, 0, 360f, 0.25f);

        log.info("Bank of Starsector: Aurum system generated (core world, pre-explored).");
    }

    /**
     * Phase 2: Create markets and attach to planets/stations.
     * Called during onNewGameAfterEconomyLoad() AFTER economy is ready.
     */
    public static void generateMarkets() {
        SectorAPI sector = Global.getSector();
        StarSystemAPI system = sector.getStarSystem("Aurum");

        if (system == null) {
            log.error("Aurum system not found during market generation!");
            return;
        }

        // =====================================================================
        // Bullion market - HQ, population 7
        // Banking capital, administrative center, high-tech services
        // =====================================================================
        SectorEntityToken bullion = sector.getEntityById("pbc_bullion");
        if (bullion != null) {
            MarketAPI m = createMarket(bullion, "pbc_bullion_market", FACTION_ID, 7);

            // Planet conditions
            m.addCondition(Conditions.POPULATION_7);
            m.addCondition(Conditions.HABITABLE);
            m.addCondition(Conditions.MILD_CLIMATE);
            m.addCondition(Conditions.FARMLAND_ADEQUATE);
            m.addCondition(Conditions.ORE_MODERATE);
            m.addCondition(Conditions.RARE_ORE_SPARSE);
            m.addCondition(Conditions.ORGANICS_COMMON);

            // Industries (banking HQ is a commerce/services hub)
            m.addIndustry(Industries.POPULATION);
            m.addIndustry(Industries.MEGAPORT);
            m.addIndustry(Industries.LIGHTINDUSTRY);
            m.addIndustry(Industries.ORBITALWORKS);
            m.addIndustry(Industries.WAYSTATION);
            m.addIndustry(Industries.STARFORTRESS);
            m.addIndustry(Industries.MILITARYBASE);

            // Submarkets
            m.addSubmarket(Submarkets.SUBMARKET_STORAGE);
            m.addSubmarket(Submarkets.SUBMARKET_BLACK);
            m.addSubmarket(Submarkets.SUBMARKET_OPEN);
            m.addSubmarket(Submarkets.GENERIC_MILITARY);

            finalizeMarket(m);
            log.info("Bullion market created (size 7).");
        } else {
            log.error("pbc_bullion planet not found!");
        }

        // =====================================================================
        // Vault market - secure repository, population 5
        // Mining and refining operations, high security
        // =====================================================================
        SectorEntityToken vault = sector.getEntityById("pbc_vault");
        if (vault != null) {
            MarketAPI m = createMarket(vault, "pbc_vault_market", FACTION_ID, 5);

            // Planet conditions
            m.addCondition(Conditions.POPULATION_5);
            m.addCondition(Conditions.NO_ATMOSPHERE);
            m.addCondition(Conditions.COLD);
            m.addCondition(Conditions.ORE_ABUNDANT);
            m.addCondition(Conditions.RARE_ORE_MODERATE);

            // Industries
            m.addIndustry(Industries.POPULATION);
            m.addIndustry(Industries.SPACEPORT);
            m.addIndustry(Industries.MINING);
            m.addIndustry(Industries.REFINING);
            m.addIndustry(Industries.PATROLHQ);
            m.addIndustry(Industries.BATTLESTATION);

            // Submarkets
            m.addSubmarket(Submarkets.SUBMARKET_STORAGE);
            m.addSubmarket(Submarkets.SUBMARKET_OPEN);

            finalizeMarket(m);
            log.info("Vault market created (size 5).");
        } else {
            log.error("pbc_vault planet not found!");
        }

        // =====================================================================
        // Ledger Station market - gas giant operations, population 4
        // Fuel production and light manufacturing
        // =====================================================================
        SectorEntityToken ledgerStation = sector.getEntityById("pbc_ledger_station");
        if (ledgerStation != null) {
            MarketAPI m = createMarket(ledgerStation, "pbc_ledger_market", FACTION_ID, 4);

            // Station conditions
            m.addCondition(Conditions.POPULATION_4);
            m.addCondition(Conditions.VOLATILES_ABUNDANT);

            // Industries
            m.addIndustry(Industries.POPULATION);
            m.addIndustry(Industries.SPACEPORT);
            m.addIndustry(Industries.FUELPROD);
            m.addIndustry(Industries.LIGHTINDUSTRY);
            m.addIndustry(Industries.ORBITALSTATION);

            // Submarkets
            m.addSubmarket(Submarkets.SUBMARKET_STORAGE);
            m.addSubmarket(Submarkets.SUBMARKET_OPEN);

            finalizeMarket(m);
            log.info("Ledger Station market created (size 4).");
        } else {
            log.error("pbc_ledger_station entity not found!");
        }

        // Mark the entire system as explored and all planets surveyed
        // This is the critical step - setEnteredByPlayer alone is not enough,
        // Misc.setAllPlanetsSurveyed also marks all conditions as surveyed
        system.setEnteredByPlayer(true);
        Misc.setAllPlanetsSurveyed(system, true);

        log.info("Bank of Starsector: All markets generated and system marked explored.");
    }

    /**
     * Creates a market but does NOT register it with the economy yet.
     * Call finalizeMarket() after adding all conditions and industries.
     */
    private static MarketAPI createMarket(SectorEntityToken entity, String marketId, String factionId, int size) {
        MarketAPI market = Global.getFactory().createMarket(marketId, entity.getName(), size);
        market.setFactionId(factionId);
        market.setPrimaryEntity(entity);
        market.setSurveyLevel(MarketAPI.SurveyLevel.FULL);
        market.getTariff().modifyFlat("generator", 0.3f);
        market.setEconGroup(market.getId());
        market.getLocationInHyperspace().set(entity.getLocationInHyperspace());
        entity.setMarket(market);
        entity.setFaction(factionId);
        return market;
    }

    /**
     * Registers a fully-configured market with the global economy.
     * Must be called AFTER all conditions, industries, and submarkets are added.
     */
    private static void finalizeMarket(MarketAPI market) {
        Global.getSector().getEconomy().addMarket(market, true);
    }
}
