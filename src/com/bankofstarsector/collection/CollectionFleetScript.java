package com.bankofstarsector.collection;

import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;

import org.apache.log4j.Logger;

import java.io.Serializable;

public class CollectionFleetScript implements EveryFrameScript, Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = Logger.getLogger(CollectionFleetScript.class);

    private String loanAccountId;
    private int targetFP;
    private boolean done;
    private boolean fleetSpawned;
    private transient CampaignFleetAPI fleet;

    public CollectionFleetScript(String loanAccountId, int targetFP) {
        this.loanAccountId = loanAccountId;
        this.targetFP = targetFP;
        this.done = false;
        this.fleetSpawned = false;
    }

    @Override
    public boolean isDone() { return done; }

    @Override
    public boolean runWhilePaused() { return false; }

    @Override
    public void advance(float amount) {
        if (done) return;

        // Spawn fleet if not done yet
        if (!fleetSpawned) {
            spawnFleet();
            fleetSpawned = true;
        }

        // Check if fleet is alive and has a valid reference
        if (fleet == null || !fleet.isAlive()) {
            done = true;
            return;
        }

        // Direct fleet toward player
        SectorEntityToken player = Global.getSector().getPlayerFleet();
        if (player != null && fleet.getContainingLocation() == player.getContainingLocation()) {
            // Fleet AI handles pursuit naturally
        }

        // Check if loan was paid off
        com.bankofstarsector.core.BankData data = com.bankofstarsector.core.BankData.get();
        com.bankofstarsector.banking.BankAccount loan = data.getLoanManager().findLoan(loanAccountId);
        if (loan == null || loan.status == com.bankofstarsector.banking.LoanStatus.PAID_OFF) {
            // Loan resolved, recall fleet
            if (fleet != null && fleet.isAlive()) {
                fleet.despawn();
            }
            done = true;
            return;
        }
    }

    private void spawnFleet() {
        try {
            FactionAPI pbc = Global.getSector().getFaction("pbc");
            if (pbc == null) {
                log.error("BOS: PBC faction not found for collection fleet!");
                done = true;
                return;
            }

            // Find a PBC market to spawn from
            SectorEntityToken spawnLocation = null;
            for (com.fs.starfarer.api.campaign.econ.MarketAPI market :
                    Global.getSector().getEconomy().getMarketsCopy()) {
                if ("pbc".equals(market.getFactionId())) {
                    spawnLocation = market.getPrimaryEntity();
                    break;
                }
            }

            if (spawnLocation == null) {
                log.warn("BOS: No PBC market found for fleet spawn.");
                done = true;
                return;
            }

            FleetParamsV3 params = new FleetParamsV3(
                spawnLocation.getLocationInHyperspace(),
                "pbc",
                null,
                FleetTypes.PATROL_LARGE,
                targetFP,    // combat
                0,           // freighter
                0,           // tanker
                0,           // transport
                0,           // liner
                0,           // utility
                0            // quality bonus
            );

            fleet = FleetFactoryV3.createFleet(params);
            if (fleet == null) {
                log.error("BOS: Failed to create collection fleet.");
                done = true;
                return;
            }

            fleet.setName("PBC Collection Fleet");
            fleet.getMemoryWithoutUpdate().set("$bos_collection_fleet", true);
            fleet.getMemoryWithoutUpdate().set("$bos_target_loan", loanAccountId);

            // Add fleet to spawn location's star system
            spawnLocation.getContainingLocation().addEntity(fleet);
            fleet.setLocation(spawnLocation.getLocation().x, spawnLocation.getLocation().y);

            // Assign pursuit of player
            fleet.addAssignment(FleetAssignment.INTERCEPT,
                Global.getSector().getPlayerFleet(), 90f,
                "Collecting on PBC debt");

            log.info("BOS: Collection fleet spawned (" + targetFP + " FP) for loan " + loanAccountId);

        } catch (Exception e) {
            log.error("BOS: Error spawning collection fleet: " + e.getMessage());
            done = true;
        }
    }
}
