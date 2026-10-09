package com.bankofstarsector.collection;

import com.bankofstarsector.banking.BankAccount;
import com.bankofstarsector.banking.LoanStatus;
import com.bankofstarsector.core.BankData;
import com.fs.starfarer.api.EveryFrameScript;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.*;
import com.fs.starfarer.api.impl.campaign.RuleBasedInteractionDialogPluginImpl;
import com.fs.starfarer.api.impl.campaign.fleets.FleetFactoryV3;
import com.fs.starfarer.api.impl.campaign.fleets.FleetParamsV3;
import com.fs.starfarer.api.impl.campaign.ids.FleetTypes;
import com.fs.starfarer.api.impl.campaign.ids.MemFlags;
import com.fs.starfarer.api.util.Misc;

import org.apache.log4j.Logger;

import java.io.Serializable;

/**
 * Owns one PBC Collection Fleet. The fleet hunts the player and, on contact,
 * hails them into the "BOSCollectionHail" rules dialog (pay / surrender a ship / refuse).
 * Added with addScript (not transient) so it survives save/load with its fleet.
 */
public class CollectionFleetScript implements EveryFrameScript, Serializable {

    private static final long serialVersionUID = 2L;
    private static final Logger log = Logger.getLogger(CollectionFleetScript.class);

    public static final String MEM_COLLECTION_FLEET = "$bos_collection_fleet";
    public static final String MEM_TARGET_LOAN = "$bos_target_loan";
    public static final String MEM_HAIL_COOLDOWN = "$bos_hail_cooldown";
    public static final String MEM_SETTLED = "$bos_settled";

    private static final float HUNT_DAYS = 120f;
    private static final float HAIL_RANGE = 250f;

    private String loanAccountId;
    private int targetFP;
    private boolean done;
    private boolean fleetSpawned;
    private CampaignFleetAPI fleet;
    private float daysActive;

    public CollectionFleetScript(String loanAccountId, int targetFP) {
        this.loanAccountId = loanAccountId;
        this.targetFP = targetFP;
    }

    @Override
    public boolean isDone() { return done; }

    @Override
    public boolean runWhilePaused() { return false; }

    @Override
    public void advance(float amount) {
        if (done) return;

        if (!fleetSpawned) {
            fleetSpawned = true;
            spawnFleet();
            if (done) {
                // No PBC market to launch from (e.g. conquered in Nexerelin): try again later.
                BankData.get().getCollectionManager().onCollectionFleetGone(loanAccountId);
                return;
            }
        }

        BankData data = BankData.get();
        BankAccount loan = data.getLoanManager().findLoan(loanAccountId);
        boolean resolved = loan == null || loan.status == LoanStatus.PAID_OFF || loan.status == LoanStatus.SEIZED
            || loan.status == LoanStatus.ACTIVE;

        if (fleet == null) {
            done = true;
            return;
        }
        if (!fleet.isAlive()) {
            // Destroyed by the player (despawns we trigger end the script before this point).
            if (!resolved) data.getCollectionManager().onCollectionFleetDefeated(loanAccountId);
            done = true;
            return;
        }
        if (resolved || fleet.getMemoryWithoutUpdate().getBoolean(MEM_SETTLED)) {
            sendHome();
            return;
        }

        daysActive += Global.getSector().getClock().convertToDays(amount);
        if (daysActive > HUNT_DAYS) {
            data.getCollectionManager().onCollectionFleetGone(loanAccountId);
            sendHome();
            return;
        }

        tryHail();
    }

    /** Fleet catches up with the player: open the collection dialog. */
    private void tryHail() {
        CampaignFleetAPI player = Global.getSector().getPlayerFleet();
        if (player == null || fleet.getContainingLocation() != player.getContainingLocation()) return;
        if (fleet.getBattle() != null || player.getBattle() != null) return;
        if (Global.getSector().getCampaignUI().isShowingDialog() || Global.getSector().getCampaignUI().isShowingMenu()) return;
        if (fleet.getMemoryWithoutUpdate().contains(MEM_HAIL_COOLDOWN)) return;
        if (fleet.isHostileTo(player)) return; // refused: it's a fight now, vanilla handles contact

        float dist = Misc.getDistance(fleet.getLocation(), player.getLocation()) - fleet.getRadius() - player.getRadius();
        if (dist > HAIL_RANGE) return;

        fleet.getMemoryWithoutUpdate().set(MEM_HAIL_COOLDOWN, true, 3f);
        Global.getSector().getCampaignUI().showInteractionDialog(
            new RuleBasedInteractionDialogPluginImpl("BOSCollectionHail"), fleet);
    }

    private void sendHome() {
        done = true;
        if (fleet == null || !fleet.isAlive()) return;
        fleet.getMemoryWithoutUpdate().unset(MemFlags.MEMORY_KEY_PURSUE_PLAYER);
        fleet.getMemoryWithoutUpdate().unset(MemFlags.MEMORY_KEY_STICK_WITH_PLAYER_IF_ALREADY_TARGET);
        fleet.clearAssignments();
        Misc.giveStandardReturnToSourceAssignments(fleet);
    }

    private void spawnFleet() {
        try {
            FactionAPI pbc = Global.getSector().getFaction("pbc");
            if (pbc == null) {
                log.error("BOS: PBC faction not found for collection fleet!");
                done = true;
                return;
            }

            com.fs.starfarer.api.campaign.econ.MarketAPI source = null;
            for (com.fs.starfarer.api.campaign.econ.MarketAPI market : Global.getSector().getEconomy().getMarketsCopy()) {
                if ("pbc".equals(market.getFactionId())) {
                    if (source == null || market.getSize() > source.getSize()) source = market;
                }
            }
            if (source == null) {
                log.warn("BOS: No PBC market found for fleet spawn.");
                done = true;
                return;
            }
            SectorEntityToken spawnLocation = source.getPrimaryEntity();

            FleetParamsV3 params = new FleetParamsV3(source, spawnLocation.getLocationInHyperspace(),
                "pbc", null, FleetTypes.PATROL_LARGE,
                targetFP, 0, 0, 0, 0, 0, 0);

            fleet = FleetFactoryV3.createFleet(params);
            if (fleet == null || fleet.isEmpty()) {
                log.error("BOS: Failed to create collection fleet.");
                fleet = null;
                done = true;
                return;
            }

            fleet.setName("PBC Collection Fleet");
            fleet.setNoFactionInName(true);
            fleet.getMemoryWithoutUpdate().set(MEM_COLLECTION_FLEET, true);
            fleet.getMemoryWithoutUpdate().set(MEM_TARGET_LOAN, loanAccountId);
            fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_PURSUE_PLAYER, true);
            fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_STICK_WITH_PLAYER_IF_ALREADY_TARGET, true);
            fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_NEVER_AVOID_PLAYER_SLOWLY, true);
            fleet.getMemoryWithoutUpdate().set(MemFlags.MEMORY_KEY_SOURCE_MARKET, source.getId());

            spawnLocation.getContainingLocation().addEntity(fleet);
            fleet.setLocation(spawnLocation.getLocation().x, spawnLocation.getLocation().y);

            fleet.addAssignment(FleetAssignment.INTERCEPT, Global.getSector().getPlayerFleet(), HUNT_DAYS,
                "collecting on PBC debt");

            log.info("BOS: Collection fleet spawned (" + targetFP + " FP) for loan " + loanAccountId);
        } catch (Exception e) {
            log.error("BOS: Error spawning collection fleet", e);
            fleet = null;
            done = true;
        }
    }
}
