package com.bankofstarsector.faction;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.EconomyAPI;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.*;
import com.fs.starfarer.api.impl.campaign.procgen.NebulaEditor;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;
import com.fs.starfarer.api.impl.campaign.terrain.HyperspaceTerrainPlugin;
import com.fs.starfarer.api.util.Misc;

import org.apache.log4j.Logger;

import java.awt.Color;
import java.util.ArrayList;
import java.util.Arrays;

public class PBCSystemGenerator {

    private static final Logger log = Logger.getLogger(PBCSystemGenerator.class);
    public static final String FACTION_ID = "pbc";

    /**
     * Creates the entire Aurum system: star, planets, markets, industries.
     * Called during onNewGame() BEFORE economy loads, so the economy's
     * initial processing cycle will pick up and initialize our markets
     * (stability, supply/demand, stockpiles).
     */
    public static void generate(SectorAPI sector) {
        if (sector.getStarSystem("Aurum") != null) {
            log.info("Aurum system already exists, skipping generation.");
            return;
        }
        // Nexerelin random sector: Nex's own procgen places PBC markets (mod_factions.csv),
        // so a hand-placed Aurum would give the Confederation a second homeworld.
        if (!com.bankofstarsector.compat.NexerelinCompat.isCorvusMode()) {
            log.info("Nexerelin random sector: Aurum not generated; Nexerelin places PBC markets.");
            return;
        }

        EconomyAPI globalEconomy = sector.getEconomy();

        // =====================================================================
        // Star System
        // =====================================================================
        StarSystemAPI system = sector.createStarSystem("Aurum");
        system.getLocation().set(-4000, 6000);
        system.addTag(Tags.THEME_CORE);
        system.addTag(Tags.THEME_CORE_POPULATED);
        system.setProcgen(false);
        system.setType(StarSystemGenerator.StarSystemType.SINGLE);
        system.setBackgroundTextureFilename("graphics/backgrounds/background1.jpg");

        PlanetAPI star = system.initStar("aurum_star", "star_yellow", 800f, 400f);
        system.setLightColor(new Color(255, 245, 200));

        // =====================================================================
        // Planet 1: Bullion - Banking HQ (terran), size 7
        // =====================================================================
        PlanetAPI bullion = system.addPlanet("pbc_bullion", star, "Bullion", "terran", 30, 150, 3500, 250);
        bullion.setCustomDescriptionId("pbc_bullion");

        MarketAPI bullionMarket = addMarketplace(FACTION_ID, bullion, null, "Bullion", 7,
            new ArrayList<String>(Arrays.asList(
                Conditions.POPULATION_7,
                Conditions.HABITABLE,
                Conditions.MILD_CLIMATE,
                Conditions.FARMLAND_ADEQUATE,
                Conditions.ORE_MODERATE,
                Conditions.RARE_ORE_SPARSE,
                Conditions.ORGANICS_COMMON
            )),
            new ArrayList<String>(Arrays.asList(
                Industries.POPULATION,
                Industries.MEGAPORT,
                Industries.LIGHTINDUSTRY,
                Industries.ORBITALWORKS,
                Industries.WAYSTATION,
                Industries.STARFORTRESS,
                Industries.MILITARYBASE
            )),
            new ArrayList<String>(Arrays.asList(
                Submarkets.SUBMARKET_STORAGE,
                Submarkets.SUBMARKET_BLACK,
                Submarkets.SUBMARKET_OPEN,
                Submarkets.GENERIC_MILITARY
            )),
            0.3f);

        // =====================================================================
        // Planet 2: Vault - Secure repository (barren), size 5
        // =====================================================================
        PlanetAPI vault = system.addPlanet("pbc_vault", star, "Vault", "barren", 150, 80, 5500, 350);
        vault.setCustomDescriptionId("pbc_vault");

        addMarketplace(FACTION_ID, vault, null, "Vault", 5,
            new ArrayList<String>(Arrays.asList(
                Conditions.POPULATION_5,
                Conditions.NO_ATMOSPHERE,
                Conditions.COLD,
                Conditions.ORE_ABUNDANT,
                Conditions.RARE_ORE_MODERATE
            )),
            new ArrayList<String>(Arrays.asList(
                Industries.POPULATION,
                Industries.SPACEPORT,
                Industries.MINING,
                Industries.REFINING,
                Industries.PATROLHQ,
                Industries.BATTLESTATION
            )),
            new ArrayList<String>(Arrays.asList(
                Submarkets.SUBMARKET_STORAGE,
                Submarkets.SUBMARKET_OPEN
            )),
            0.3f);

        // =====================================================================
        // Planet 3: Ledger - Gas giant (no market on the planet itself)
        // =====================================================================
        PlanetAPI ledger = system.addPlanet("pbc_ledger", star, "Ledger", "gas_giant", 270, 300, 8000, 500);
        ledger.setCustomDescriptionId("pbc_ledger");

        // Ledger Station orbiting gas giant, size 4
        SectorEntityToken ledgerStation = system.addCustomEntity("pbc_ledger_station",
            "Ledger Station", "station_mining00", FACTION_ID);
        ledgerStation.setCircularOrbitPointingDown(ledger, 0, 500, 40);

        addMarketplace(FACTION_ID, ledgerStation, null, "Ledger Station", 4,
            new ArrayList<String>(Arrays.asList(
                Conditions.POPULATION_4,
                Conditions.VOLATILES_ABUNDANT
            )),
            new ArrayList<String>(Arrays.asList(
                Industries.POPULATION,
                Industries.SPACEPORT,
                Industries.FUELPROD,
                Industries.LIGHTINDUSTRY,
                Industries.ORBITALSTATION
            )),
            new ArrayList<String>(Arrays.asList(
                Submarkets.SUBMARKET_STORAGE,
                Submarkets.SUBMARKET_OPEN
            )),
            0.3f);

        // =====================================================================
        // System furniture: relay, nav buoy, sensor array, ring
        // =====================================================================
        system.addRingBand(star, "misc", "rings_dust0", 256f, 3, Color.WHITE, 256f, 2000, 120f, null, null);

        SectorEntityToken relay = system.addCustomEntity("pbc_relay", "Aurum Relay",
            "comm_relay", FACTION_ID);
        relay.setCircularOrbitPointingDown(star, 180, 4500, 300);

        SectorEntityToken navBuoy = system.addCustomEntity("pbc_nav_buoy", "Aurum Nav Buoy",
            "nav_buoy", FACTION_ID);
        navBuoy.setCircularOrbitPointingDown(star, 90, 6000, 400);

        SectorEntityToken sensor = system.addCustomEntity("pbc_sensor", "Aurum Sensor Array",
            "sensor_array", FACTION_ID);
        sensor.setCircularOrbitPointingDown(star, 270, 6500, 420);

        // =====================================================================
        // Jump points and hyperspace cleanup
        // =====================================================================
        system.autogenerateHyperspaceJumpPoints(true, true);

        // Clear hyperspace nebula around the system (like vanilla core worlds)
        HyperspaceTerrainPlugin plugin = (HyperspaceTerrainPlugin) Misc.getHyperspaceTerrain().getPlugin();
        NebulaEditor editor = new NebulaEditor(plugin);
        float minRadius = plugin.getTileSize() * 2f;
        float radius = system.getMaxRadiusInHyperspace();
        editor.clearArc(system.getLocation().x, system.getLocation().y, 0, radius + minRadius * 0.5f, 0, 360f);
        editor.clearArc(system.getLocation().x, system.getLocation().y, 0, radius + minRadius, 0, 360f, 0.25f);

        log.info("Bank of Starsector: Aurum system fully generated.");
    }

    /**
     * Post-economy setup: mark system explored, set admin.
     * Called during onNewGameAfterEconomyLoad().
     */
    public static void postEconomySetup() {
        StarSystemAPI system = Global.getSector().getStarSystem("Aurum");
        if (system == null) return;

        system.setEnteredByPlayer(true);
        Misc.setAllPlanetsSurveyed(system, true);

        log.info("Bank of Starsector: Post-economy setup complete.");
    }

    /**
     * Creates a market following the II_Thracia pattern:
     * fully configure then register with economy.
     */
    private static MarketAPI addMarketplace(String factionId, SectorEntityToken primaryEntity,
            ArrayList<SectorEntityToken> connectedEntities, String name, int size,
            ArrayList<String> conditions, ArrayList<String> industries,
            ArrayList<String> submarkets, float tariff) {

        EconomyAPI globalEconomy = Global.getSector().getEconomy();
        String marketId = primaryEntity.getId();

        MarketAPI market = Global.getFactory().createMarket(marketId, name, size);
        market.setFactionId(factionId);
        market.setPrimaryEntity(primaryEntity);
        market.getTariff().modifyFlat("generator", tariff);
        market.getLocationInHyperspace().set(primaryEntity.getLocationInHyperspace());

        for (String sub : submarkets) {
            market.addSubmarket(sub);
        }

        for (String cond : conditions) {
            market.addCondition(cond);
        }

        for (String ind : industries) {
            market.addIndustry(ind);
        }

        if (connectedEntities != null) {
            for (SectorEntityToken entity : connectedEntities) {
                market.getConnectedEntities().add(entity);
            }
        }

        globalEconomy.addMarket(market, true);
        primaryEntity.setMarket(market);
        primaryEntity.setFaction(factionId);

        if (connectedEntities != null) {
            for (SectorEntityToken entity : connectedEntities) {
                entity.setMarket(market);
                entity.setFaction(factionId);
            }
        }

        return market;
    }
}
