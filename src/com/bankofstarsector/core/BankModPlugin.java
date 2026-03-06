package com.bankofstarsector.core;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import com.bankofstarsector.compat.NexerelinCompat;
import com.bankofstarsector.compat.NexusUICompat;
import com.bankofstarsector.faction.PBCFactionSetup;
import com.bankofstarsector.faction.PBCSystemGenerator;
import com.bankofstarsector.intel.BankingIntelPlugin;

import org.apache.log4j.Logger;

public class BankModPlugin extends BaseModPlugin {

    private static final Logger log = Global.getLogger(BankModPlugin.class);

    public static final String MOD_ID = "bank_of_starsector";
    public static final String VERSION = "0.1.0-beta";

    @Override
    public void onApplicationLoad() throws Exception {
        log.info("Bank of Starsector v" + VERSION + ": Loading...");
        BankSettings.load();
        log.info("Bank of Starsector: Settings loaded.");
    }

    @Override
    public void onNewGame() {
        log.info("Bank of Starsector: New game initialization.");
        PBCSystemGenerator.generate();
        PBCFactionSetup.setup();
        BankData.get(); // force creation
    }

    @Override
    public void onGameLoad(boolean newGame) {
        log.info("Bank of Starsector: Game loaded (newGame=" + newGame + ")");

        BankSettings.load();
        BankData data = BankData.get();

        // Register campaign script
        Global.getSector().addTransientScript(new BankCampaignScript());
        log.info("Bank of Starsector: Campaign script registered.");

        // Register intel plugin
        registerIntelPlugin();
        log.info("Bank of Starsector: Intel plugin registered.");

        // Nexerelin integration
        if (NexerelinCompat.isAvailable()) {
            PBCFactionSetup.setupNexerelinRelationships();
            log.info("Bank of Starsector: Nexerelin integration active.");
        }

        // NexusUI integration
        if (NexusUICompat.isAvailable()) {
            NexusUICompat.registerBankingPage();
            log.info("Bank of Starsector: NexusUI banking page registered.");
        }

        log.info("Bank of Starsector: Fully loaded.");
    }

    @Override
    public void beforeGameSave() {
        log.info("Bank of Starsector: Saving...");
    }

    private void registerIntelPlugin() {
        boolean found = false;
        for (com.fs.starfarer.api.campaign.comm.IntelInfoPlugin intel :
                Global.getSector().getIntelManager().getIntel()) {
            if (intel instanceof BankingIntelPlugin) {
                found = true;
                break;
            }
        }
        if (!found) {
            BankingIntelPlugin intelPlugin = new BankingIntelPlugin();
            Global.getSector().getIntelManager().addIntel(intelPlugin, true);
        }
    }
}
