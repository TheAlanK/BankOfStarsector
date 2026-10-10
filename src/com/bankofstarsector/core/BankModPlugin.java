package com.bankofstarsector.core;

import com.fs.starfarer.api.BaseModPlugin;
import com.fs.starfarer.api.Global;
import com.bankofstarsector.compat.NexerelinCompat;
import com.bankofstarsector.compat.NexusUICompat;
import com.bankofstarsector.faction.PBCFactionSetup;
import com.bankofstarsector.faction.PBCPostInitScript;
import com.bankofstarsector.faction.PBCSystemGenerator;
import com.bankofstarsector.intel.BankingIntelPlugin;

import org.apache.log4j.Logger;

public class BankModPlugin extends BaseModPlugin {

    private static final Logger log = Global.getLogger(BankModPlugin.class);

    public static final String MOD_ID = "bank_of_starsector";
    public static final String VERSION = "0.3.0-beta";

    @Override
    public void onApplicationLoad() throws Exception {
        log.info("Bank of Starsector v" + VERSION + ": Loading...");
        BankSettings.load();
    }

    @Override
    public void onNewGame() {
        log.info("Bank of Starsector: New game - generating Aurum system...");
        // Create system + markets in onNewGame (before economy loads)
        // so the economy's initial processing cycle handles stability/supply/demand
        PBCSystemGenerator.generate(Global.getSector());
    }

    @Override
    public void onNewGameAfterEconomyLoad() {
        log.info("Bank of Starsector: Economy loaded - post-economy setup...");
        PBCSystemGenerator.postEconomySetup();
        PBCFactionSetup.setup();
        BankData.get();
    }

    @Override
    public void onNewGameAfterTimePass() {
        // Sector generation can reset exploration flags after onNewGameAfterEconomyLoad.
        Global.getSector().addScript(new PBCPostInitScript());
    }

    @Override
    public void onGameLoad(boolean newGame) {
        log.info("Bank of Starsector: Game loaded (newGame=" + newGame + ")");

        // LunaLib finishes loading its settings after onApplicationLoad, so re-read them here.
        BankSettings.load();
        com.bankofstarsector.compat.LunaLibCompat.listenForChanges();
        BankData.get();

        Global.getSector().addTransientScript(new BankCampaignScript());
        Global.getSector().getListenerManager().addListener(new BankEconomyListener(), true);
        Global.getSector().addTransientListener(new BankBattleListener());

        registerIntelPlugin();

        if (NexerelinCompat.isAvailable()) {
            log.info("Bank of Starsector: Nexerelin detected (corvus mode: " + NexerelinCompat.isCorvusMode() + ").");
        }
        com.bankofstarsector.ui.BankSnapshot.clear(); // never show the previous save's numbers
        if (NexusUICompat.registerBankingPage()) {
            // NexusUI refreshes pages off the game thread; publish snapshots from the game thread.
            Global.getSector().addTransientScript(new com.bankofstarsector.ui.BankSnapshotScript());
            log.info("Bank of Starsector: NexusUI banking page available.");
        }

        log.info("Bank of Starsector: Fully loaded.");
    }

    private void registerIntelPlugin() {
        if (Global.getSector().getIntelManager().hasIntelOfClass(BankingIntelPlugin.class)) return;
        Global.getSector().getIntelManager().addIntel(new BankingIntelPlugin(), true);
    }

    public static BankingIntelPlugin getTerminal() {
        Object o = Global.getSector().getIntelManager().getFirstIntel(BankingIntelPlugin.class);
        if (o instanceof BankingIntelPlugin) return (BankingIntelPlugin) o;
        BankingIntelPlugin intel = new BankingIntelPlugin();
        Global.getSector().getIntelManager().addIntel(intel, true);
        return intel;
    }
}
