package com.bankofstarsector.core;

import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import org.apache.log4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * TEMPORARY (v0.4.0 development, section B spikes): logs, once a month, whether NPC markets report a
 * usable income and what a sector-wide commodity price index costs. Remove before the 0.4.0 release.
 * Search starsector.log for "BOS-DIAG".
 */
public final class EconomyDiagnostics {

    private static final Logger log = Logger.getLogger(EconomyDiagnostics.class);
    private static final String[] BASKET = {"fuel", "supplies", "metals", "rare_metals", "volatiles", "organics", "food", "heavy_machinery"};

    private EconomyDiagnostics() {}

    public static void logMonth() {
        try {
            List<MarketAPI> markets = Global.getSector().getEconomy().getMarketsCopy();
            log.info("BOS-DIAG markets in economy: " + markets.size());

            // Income of a sample of NPC markets: two per faction, the largest first, up to 24.
            List<MarketAPI> sorted = new ArrayList<MarketAPI>(markets);
            java.util.Collections.sort(sorted, new java.util.Comparator<MarketAPI>() {
                public int compare(MarketAPI a, MarketAPI b) { return b.getSize() - a.getSize(); }
            });
            java.util.Map<String, Integer> perFaction = new java.util.HashMap<String, Integer>();
            int logged = 0;
            float totalNet = 0f;
            int npcCount = 0, npcPositive = 0, npcZero = 0;
            for (MarketAPI m : sorted) {
                if (m.isPlayerOwned() || m.isHidden()) continue;
                npcCount++;
                float net = m.getNetIncome();
                totalNet += net;
                if (net > 1f) npcPositive++;
                else if (Math.abs(net) <= 1f) npcZero++;
                Integer n = perFaction.get(m.getFactionId());
                if (logged < 24 && (n == null || n < 2)) {
                    perFaction.put(m.getFactionId(), n == null ? 1 : n + 1);
                    logged++;
                    log.info(String.format("BOS-DIAG income %-18s %-26s size %d | net %.0f gross %.0f industry %.0f upkeep %.0f export %.0f stability %.1f",
                        m.getFactionId(), m.getName(), m.getSize(), net, m.getGrossIncome(), m.getIndustryIncome(),
                        m.getIndustryUpkeep(), m.getExportIncome(false), m.getStabilityValue()));
                }
            }
            log.info(String.format("BOS-DIAG NPC markets: %d, net income > 0: %d, ~0: %d, total net %.0f",
                npcCount, npcPositive, npcZero, totalNet));

            // Player colonies for comparison.
            for (MarketAPI m : markets) {
                if (!m.isPlayerOwned()) continue;
                log.info(String.format("BOS-DIAG player %-26s size %d | net %.0f gross %.0f industry %.0f upkeep %.0f",
                    m.getName(), m.getSize(), m.getNetIncome(), m.getGrossIncome(), m.getIndustryIncome(), m.getIndustryUpkeep()));
            }

            // Commodity basket on the three largest markets, then the cost of a full sector index.
            for (int i = 0; i < Math.min(3, sorted.size()); i++) {
                MarketAPI m = sorted.get(i);
                StringBuilder sb = new StringBuilder();
                for (String c : BASKET) sb.append(c).append('=').append(String.format("%.0f", m.getSupplyPrice(c, 1, false))).append(' ');
                log.info("BOS-DIAG prices " + m.getName() + ": " + sb);
            }
            long t0 = System.nanoTime();
            double index = 0;
            int samples = 0;
            Set<String> seen = new HashSet<String>();
            for (MarketAPI m : markets) {
                if (m.isHidden() || !seen.add(m.getId())) continue;
                for (String c : BASKET) {
                    index += m.getSupplyPrice(c, 1, false);
                    samples++;
                }
            }
            log.info(String.format("BOS-DIAG price index over %d samples: average %.1f, took %.2f ms",
                samples, samples > 0 ? index / samples : 0, (System.nanoTime() - t0) / 1e6));
        } catch (Throwable t) {
            log.warn("BOS-DIAG failed: " + t);
        }
    }
}
