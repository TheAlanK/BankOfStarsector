package com.bankofstarsector.faction;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.impl.campaign.ids.*;
import com.fs.starfarer.api.impl.campaign.procgen.StarSystemGenerator;

import org.apache.log4j.Logger;

import java.awt.Color;

public class PBCSystemGenerator {

    private static final Logger log = Logger.getLogger(PBCSystemGenerator.class);
    public static final String FACTION_ID = "pbc";

    public static void generate() {
        SectorAPI sector = Global.getSector();

        // Check if system already exists
        if (sector.getStarSystem("Aurum") != null) {
            log.info("Aurum system already exists, skipping generation.");
            return;
        }

        StarSystemAPI system = sector.createStarSystem("Aurum");
        system.getLocation().set(-4000, 6000);

        // Star
        PlanetAPI star = system.initStar("aurum_star", "star_yellow", 800f, 400f);
        system.setLightColor(new Color(255, 245, 200));

        // Planet 1: Bullion - HQ world
        PlanetAPI bullion = system.addPlanet("pbc_bullion", star, "Bullion", "terran", 30, 150, 3500, 250);
        bullion.setCustomDescriptionId("pbc_bullion");

        MarketAPI bullionMarket = addMarket(bullion, "pbc_bullion_market", FACTION_ID, 7);
        bullionMarket.addCondition(Conditions.POPULATION_7);
        bullionMarket.addCondition(Conditions.HABITABLE);
        bullionMarket.addCondition(Conditions.MILD_CLIMATE);
        bullionMarket.addCondition(Conditions.FARMLAND_ADEQUATE);
        bullionMarket.addCondition(Conditions.ORE_MODERATE);
        bullionMarket.addCondition(Conditions.ORGANICS_COMMON);
        bullionMarket.addIndustry(Industries.POPULATION);
        bullionMarket.addIndustry(Industries.MEGAPORT);
        bullionMarket.addIndustry(Industries.ORBITALWORKS);
        bullionMarket.addIndustry(Industries.MILITARYBASE);
        bullionMarket.addIndustry(Industries.STARFORTRESS);
        bullionMarket.addIndustry(Industries.WAYSTATION);
        bullionMarket.addSubmarket(Submarkets.SUBMARKET_STORAGE);
        bullionMarket.addSubmarket(Submarkets.SUBMARKET_BLACK);
        bullionMarket.addSubmarket(Submarkets.SUBMARKET_OPEN);
        bullionMarket.addSubmarket(Submarkets.GENERIC_MILITARY);

        // Planet 2: Vault - Barren secure world
        PlanetAPI vault = system.addPlanet("pbc_vault", star, "Vault", "barren", 150, 80, 5500, 350);
        vault.setCustomDescriptionId("pbc_vault");

        MarketAPI vaultMarket = addMarket(vault, "pbc_vault_market", FACTION_ID, 5);
        vaultMarket.addCondition(Conditions.POPULATION_5);
        vaultMarket.addCondition(Conditions.NO_ATMOSPHERE);
        vaultMarket.addCondition(Conditions.ORE_ABUNDANT);
        vaultMarket.addCondition(Conditions.RARE_ORE_MODERATE);
        vaultMarket.addIndustry(Industries.POPULATION);
        vaultMarket.addIndustry(Industries.SPACEPORT);
        vaultMarket.addIndustry(Industries.MINING);
        vaultMarket.addIndustry(Industries.REFINING);
        vaultMarket.addIndustry(Industries.PATROLHQ);
        vaultMarket.addIndustry(Industries.BATTLESTATION);
        vaultMarket.addSubmarket(Submarkets.SUBMARKET_STORAGE);
        vaultMarket.addSubmarket(Submarkets.SUBMARKET_OPEN);

        // Planet 3: Ledger - Gas giant
        PlanetAPI ledger = system.addPlanet("pbc_ledger", star, "Ledger", "gas_giant", 270, 300, 8000, 500);
        ledger.setCustomDescriptionId("pbc_ledger");

        // Ledger Station (in orbit around gas giant)
        SectorEntityToken ledgerStation = system.addCustomEntity("pbc_ledger_station",
            "Ledger Station", "station_mining00", FACTION_ID);
        ledgerStation.setCircularOrbitPointingDown(ledger, 0, 500, 40);

        MarketAPI ledgerMarket = addMarket(ledgerStation, "pbc_ledger_market", FACTION_ID, 4);
        ledgerMarket.addCondition(Conditions.POPULATION_4);
        ledgerMarket.addIndustry(Industries.POPULATION);
        ledgerMarket.addIndustry(Industries.SPACEPORT);
        ledgerMarket.addIndustry(Industries.FUELPROD);
        ledgerMarket.addIndustry(Industries.LIGHTINDUSTRY);
        ledgerMarket.addIndustry(Industries.ORBITALSTATION);
        ledgerMarket.addSubmarket(Submarkets.SUBMARKET_STORAGE);
        ledgerMarket.addSubmarket(Submarkets.SUBMARKET_OPEN);

        // System features
        system.addRingBand(star, "misc", "rings_dust0", 256f, 3, Color.WHITE, 256f, 2000, 120f, null, null);
        system.autogenerateHyperspaceJumpPoints(true, true);

        // Comm relay
        SectorEntityToken relay = system.addCustomEntity("pbc_relay", "Aurum Relay",
            "comm_relay", FACTION_ID);
        relay.setCircularOrbitPointingDown(star, 180, 4500, 300);

        // Nav buoy
        SectorEntityToken navBuoy = system.addCustomEntity("pbc_nav_buoy", "Aurum Nav Buoy",
            "nav_buoy", FACTION_ID);
        navBuoy.setCircularOrbitPointingDown(star, 90, 6000, 400);

        // Sensor array
        SectorEntityToken sensor = system.addCustomEntity("pbc_sensor", "Aurum Sensor Array",
            "sensor_array", FACTION_ID);
        sensor.setCircularOrbitPointingDown(star, 270, 6500, 420);

        log.info("Bank of Starsector: Aurum system generated.");
    }

    private static MarketAPI addMarket(SectorEntityToken entity, String marketId, String factionId, int size) {
        SectorAPI sector = Global.getSector();
        MarketAPI market = Global.getFactory().createMarket(marketId, entity.getName(), size);
        market.setFactionId(factionId);
        market.setPrimaryEntity(entity);
        market.getTariff().modifyFlat("default_tariff", 0.3f);
        market.setEconGroup(market.getId());
        entity.setMarket(market);
        entity.setFaction(factionId);
        sector.getEconomy().addMarket(market, true);
        return market;
    }
}
