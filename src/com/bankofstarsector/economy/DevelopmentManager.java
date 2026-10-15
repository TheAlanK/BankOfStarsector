package com.bankofstarsector.economy;

import com.bankofstarsector.banking.BankAccount;
import com.bankofstarsector.banking.LoanStatus;
import com.bankofstarsector.core.BankData;
import com.bankofstarsector.core.BankSettings;
import com.bankofstarsector.core.Str;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.FactionAPI;
import com.fs.starfarer.api.campaign.RepLevel;
import com.fs.starfarer.api.campaign.econ.Industry;
import com.fs.starfarer.api.campaign.econ.MarketAPI;
import com.fs.starfarer.api.util.Misc;
import org.apache.log4j.Logger;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Development financing contracts (0.4.0 section C). Factions finance each other's buildings, and the
 * Confederation's Clearing Hall holds the funds in escrow and enforces the terms.
 *
 * - Offers to the player: a faction with a treasury surplus that is Favorable or better to the player offers
 *   to pay for a building at one of the player's colonies. Accepting pays the money out as a DEVELOPMENT loan
 *   with nothing due while the building goes up; once it is built the loan is repaid at a low rate, and every
 *   payment goes to the investor. A missed construction deadline turns into a demand at a penalty rate; an
 *   unpaid demand goes to collection by full force, a missed repayment of a built project by minimal force.
 * - The player as investor: the player finances a project at a Favorable faction's market. The faction
 *   accepts or declines on the project's expected value; it then really builds and repays from its treasury,
 *   and if it misses the deadline the Confederation recovers what it can from that treasury.
 * - NPC to NPC: a few deals a month between factions that are Favorable to each other, built for real.
 *
 * Monthly work happens at the month end (after the treasuries are updated), daily checks in the bank's daily
 * tick. Market scans happen once a month, or once a day at most for the player's project list.
 */
public class DevelopmentManager implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Logger log = Logger.getLogger(DevelopmentManager.class);

    public static final String PLAYER = "player";
    public static final String PBC = "pbc";
    /** What month-end billing does with a development loan. */
    public static final int BILL_NOTHING = 0, BILL_INTEREST = 1, BILL_NORMAL = 2;
    /** Days after a building is finished before its first repayment is billed. */
    private static final float START_BILLING_DAYS = 15f;
    /** Closed, declined and expired contracts kept for the logs. */
    private static final int HISTORY = 30;
    /** The Confederation keeps this many times a deal's principal in reserve before it lends from them. */
    private static final float PBC_RESERVE_MULT = 10f;

    private List<DevelopmentContract> contracts;
    private int nextId;
    private transient Random random;
    private transient List<Project> projectCache;
    private transient long projectCacheTs;

    /** A building a market could get: what it costs, how long it takes, what it would earn. */
    public static final class Project {
        public final MarketAPI market;
        public final String industryId, name;
        public final float cost, buildDays, gain;

        Project(MarketAPI market, String industryId, String name, float cost, float buildDays) {
            this.market = market;
            this.industryId = industryId;
            this.name = name;
            this.cost = cost;
            this.buildDays = buildDays;
            this.gain = monthlyGain(cost, market.getSize());
        }

        public float principal() {
            return cost * (1f + BankSettings.DEV_CONTINGENCY);
        }
    }

    private void ensure() {
        if (contracts == null) contracts = new ArrayList<DevelopmentContract>();
        if (random == null) random = new Random();
    }

    /** Off-game tests use a fixed seed. */
    public void setRandom(Random r) {
        random = r;
    }

    // ------------------------------------------------------------------ queries

    public List<DevelopmentContract> getContracts() {
        ensure();
        return Collections.unmodifiableList(contracts);
    }

    public DevelopmentContract find(String id) {
        ensure();
        if (id == null) return null;
        for (DevelopmentContract c : contracts) if (id.equals(c.id)) return c;
        return null;
    }

    /** Offers waiting for the player's answer. */
    public List<DevelopmentContract> offersToPlayer() {
        ensure();
        List<DevelopmentContract> out = new ArrayList<DevelopmentContract>();
        for (DevelopmentContract c : contracts) if (c.state == ContractState.OFFERED && c.recipientIsPlayer()) out.add(c);
        return out;
    }

    /** Open contracts with the player on either side. */
    public List<DevelopmentContract> playerContracts() {
        ensure();
        List<DevelopmentContract> out = new ArrayList<DevelopmentContract>();
        for (DevelopmentContract c : contracts) if (c.involvesPlayer() && c.state.isOpen()) out.add(c);
        return out;
    }

    /** Deals between NPC factions, newest first. */
    public List<DevelopmentContract> sectorDeals() {
        ensure();
        List<DevelopmentContract> out = new ArrayList<DevelopmentContract>();
        for (DevelopmentContract c : contracts) {
            if (!c.involvesPlayer() && c.state != ContractState.DECLINED && c.state != ContractState.EXPIRED) out.add(c);
        }
        Collections.reverse(out);
        return out;
    }

    /** What NPC recipients still owe the player as investor. */
    public float owedToPlayer() {
        ensure();
        float total = 0f;
        for (DevelopmentContract c : contracts) if (c.investorIsPlayer() && c.state.isOpen()) total += c.owed;
        return total;
    }

    private int openPlayerRecipientContracts() {
        int n = 0;
        for (DevelopmentContract c : contracts) {
            if (c.recipientIsPlayer() && (c.state == ContractState.OFFERED || c.state.isOpen())) n++;
        }
        return n;
    }

    private int activeNpcDeals() {
        int n = 0;
        for (DevelopmentContract c : contracts) if (!c.involvesPlayer() && c.state.isOpen()) n++;
        return n;
    }

    private boolean targeted(String marketId, String industryId) {
        for (DevelopmentContract c : contracts) {
            if ((c.state == ContractState.OFFERED || c.state == ContractState.BUILDING)
                && c.marketId.equals(marketId) && c.industryId.equals(industryId)) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ projects and valuation

    /** What a building earns its owner a month: its cost back over DEV_PAYBACK_MONTHS at a size-5 market. */
    public static float monthlyGain(float cost, int marketSize) {
        float sizeFactor = Math.max(0.4f, Math.min(2f, marketSize / 5f));
        return cost / BankSettings.DEV_PAYBACK_MONTHS * sizeFactor;
    }

    /**
     * Whether a recipient wants the deal: what the building earns over the horizon, minus what it costs to
     * build and the interest, minus the risk of missing the deadline; plus goodwill toward the investor and
     * the investor's strength (a strong creditor is a safe partner to have). Above zero: accept.
     */
    public static float recipientUtility(Project p, float principal, float rate, int months, float relation, float investorStrength) {
        float benefit = p.gain * BankSettings.DEV_HORIZON_MONTHS;
        float interest = principal * rate * (months + 1) / 2f; // even principal repayments
        float risk = 0.1f * principal;
        float goodwill = 0.1f * principal * Math.max(-1f, Math.min(1f, relation));
        float strength = 0.05f * principal * Math.max(0f, Math.min(1f, investorStrength));
        return benefit - p.cost - interest - risk + goodwill + strength;
    }

    /** Buildings this market can still get, from the financeable list (one market scan of industries). */
    public List<Project> projectsAt(MarketAPI m) {
        ensure();
        List<Project> out = new ArrayList<Project>();
        if (m == null || m.isHidden() || m.getSize() < 3) return out;
        for (String id : BankSettings.DEV_INDUSTRIES) {
            if (m.hasIndustry(id) || targeted(m.getId(), id)) continue;
            Industry ind;
            try {
                ind = m.instantiateIndustry(id);
            } catch (Throwable t) {
                continue; // not an industry in this game (a mod removed it)
            }
            if (ind == null || !ind.isAvailableToBuild()) continue;
            if (ind.isIndustry() && !hasIndustrySlot(m)) continue;
            float cost = ind.getBuildCost();
            if (cost <= 0f) continue;
            String name = ind.getCurrentName() != null ? ind.getCurrentName() : id;
            out.add(new Project(m, id, name, cost, Math.max(1f, ind.getBuildTime())));
        }
        return out;
    }

    private static boolean hasIndustrySlot(MarketAPI m) {
        try {
            return Misc.getNumIndustries(m) < Misc.getMaxIndustries(m);
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean favorable(FactionAPI from, String toId) {
        if (from == null || toId == null) return false;
        return RepLevel.getLevelFor(from.getRelationship(toId)).isAtWorst(RepLevel.FAVORABLE);
    }

    private static float relation(String a, String b) {
        FactionAPI f = a == null ? null : Global.getSector().getFaction(a);
        return f == null || b == null ? 0f : f.getRelationship(b);
    }

    /** Relations between two parties move together (the player's side is the player faction). */
    private static void relate(String a, String b, float delta) {
        if (delta == 0f || a == null || b == null || a.equals(b)) return;
        FactionAPI fa = Global.getSector().getFaction(a);
        if (fa == null) return;
        fa.setRelationship(b, Math.max(-1f, Math.min(1f, fa.getRelationship(b) + delta)));
    }

    /** 0..1: how solid an investor looks (the player by fleet strength, a faction by its markets). */
    private static float strength(String investorId) {
        if (PLAYER.equals(investorId)) {
            try {
                return Math.min(1f, Global.getSector().getPlayerFleet().getFleetPoints() / 300f);
            } catch (Throwable t) {
                return 0f;
            }
        }
        FactionTreasury t = BankData.get().getSectorEconomy().getTreasury(investorId);
        return t == null ? (PBC.equals(investorId) ? 1f : 0f) : Math.min(1f, t.markets / 10f);
    }

    // ------------------------------------------------------------------ money

    /** What an investor can lend now without dipping below its reserve (credits for the player). */
    private static float lendable(String investorId) {
        if (PLAYER.equals(investorId)) return Global.getSector().getPlayerFleet().getCargo().getCredits().get();
        SectorEconomy eco = BankData.get().getSectorEconomy();
        if (PBC.equals(investorId)) return eco.getLedger().reserves / PBC_RESERVE_MULT;
        FactionTreasury t = eco.getTreasury(investorId);
        if (t == null) return 0f;
        return t.balance - BankSettings.DEV_SURPLUS_MONTHS * Math.max(0f, t.income);
    }

    /** Moves money out of a party's books (the player's is the credits on hand, a faction's its treasury). */
    private static void debit(String partyId, float amount) {
        if (amount == 0f) return;
        if (PLAYER.equals(partyId)) {
            Global.getSector().getPlayerFleet().getCargo().getCredits().subtract(amount);
            return;
        }
        SectorEconomy eco = BankData.get().getSectorEconomy();
        if (PBC.equals(partyId)) {
            eco.getLedger().reserves -= amount;
            return;
        }
        FactionTreasury t = eco.getTreasury(partyId);
        if (t != null) t.balance -= amount;
    }

    /** Pays an NPC party (the player is paid separately: at month end through the report, or on the spot). */
    private static void creditNpc(String partyId, float amount) {
        if (amount == 0f || PLAYER.equals(partyId)) return;
        SectorEconomy eco = BankData.get().getSectorEconomy();
        if (PBC.equals(partyId)) {
            eco.getLedger().reserves += amount;
            return;
        }
        FactionTreasury t = eco.getTreasury(partyId);
        if (t != null) t.balance += amount;
    }

    private DevelopmentContract newContract(String investorId, String recipientId, Project p, float rate) {
        DevelopmentContract c = new DevelopmentContract();
        c.id = "DEV-" + (++nextId);
        c.investorId = investorId;
        c.recipientId = recipientId;
        c.marketId = p.market.getId();
        c.marketName = p.market.getName();
        c.industryId = p.industryId;
        c.industryName = p.name;
        c.buildCost = p.cost;
        c.principal = p.principal();
        c.rate = rate;
        c.penaltyRate = BankSettings.DEV_PENALTY_RATE;
        c.repayMonths = BankSettings.DEV_REPAY_MONTHS;
        c.offeredTs = Global.getSector().getClock().getTimestamp();
        c.deadlineDays = p.buildDays + BankSettings.DEV_SLACK_DAYS; // counted from signing
        contracts.add(c);
        return c;
    }

    private static long now() {
        return Global.getSector().getClock().getTimestamp();
    }

    /** A timestamp this many days from now. */
    private static long daysFromNow(float days) {
        return now() + (long) (days * dayMillis());
    }

    /** Length of a day in timestamp units, measured on the campaign clock. */
    private static double dayMillis() {
        // getElapsedDaysSince(now - X) = X / day: ask the clock for one known span.
        long probe = 1_000_000_000L;
        float days = Global.getSector().getClock().getElapsedDaysSince(now() - probe);
        return days > 0f ? probe / (double) days : 86_400_000d;
    }

    private void sign(DevelopmentContract c) {
        c.acceptedTs = now();
        c.deadlineTs = daysFromNow(c.deadlineDays);
        c.state = ContractState.BUILDING;
        c.owed = c.principal;
    }

    // ------------------------------------------------------------------ offers to the player

    private void maybeOfferToPlayer(List<MarketAPI> markets) {
        if (!BankSettings.DEV_ENABLED || openPlayerRecipientContracts() >= BankSettings.DEV_MAX_PLAYER_CONTRACTS) return;
        BankData data = BankData.get();
        if (data.getCollectionManager().isBankingRestricted()) return;
        if (data.getBankruptcyManager().getState() != com.bankofstarsector.collection.BankruptcyManager.BankruptcyState.NONE) return;
        if (random.nextFloat() >= BankSettings.DEV_OFFER_CHANCE) return;

        List<Project> projects = new ArrayList<Project>();
        for (MarketAPI m : markets) if (m.isPlayerOwned()) projects.addAll(projectsAt(m));
        if (projects.isEmpty()) return;
        sortByGain(projects);

        // The richest willing investor makes the offer for the best project it can afford.
        List<String> investors = investorsFavorableTo(PLAYER);
        for (String investorId : investors) {
            float lendable = lendable(investorId);
            for (Project p : projects) {
                if (p.principal() > lendable) continue;
                DevelopmentContract c = newContract(investorId, PLAYER, p, BankSettings.DEV_RATE);
                c.state = ContractState.OFFERED;
                Global.getSector().getIntelManager().addIntel(new com.bankofstarsector.intel.DevelopmentIntel(c.id));
                Global.getSector().getCampaignUI().addMessage(Str.f("dev.msg.offer", c.investorName(), c.industryName, c.marketName),
                    Misc.getHighlightColor());
                log.info("BOS: development offer " + c.id + " from " + investorId + ": " + c.industryId + " at " + c.marketId);
                return;
            }
        }
    }

    /** Political factions (and the Confederation) Favorable to a party, with money to lend, richest first. */
    private List<String> investorsFavorableTo(String partyId) {
        final SectorEconomy eco = BankData.get().getSectorEconomy();
        List<String> out = new ArrayList<String>();
        for (FactionTreasury t : eco.getTreasuries().values()) {
            if (t.factionId.equals(partyId)) continue;
            FactionAPI f = Global.getSector().getFaction(t.factionId);
            if (f == null || !PoliticalFactions.isPolitical(f) || !favorable(f, partyId)) continue;
            if (lendable(t.factionId) > 0f) out.add(t.factionId);
        }
        Collections.sort(out, new Comparator<String>() {
            public int compare(String a, String b) { return Float.compare(lendable(b), lendable(a)); }
        });
        FactionAPI pbc = Global.getSector().getFaction(PBC);
        if (pbc != null && !PBC.equals(partyId) && favorable(pbc, partyId) && lendable(PBC) > 0f) out.add(PBC);
        return out;
    }

    private static void sortByGain(List<Project> projects) {
        Collections.sort(projects, new Comparator<Project>() {
            public int compare(Project a, Project b) {
                int c = Float.compare(b.gain, a.gain);
                return c != 0 ? c : (a.market.getId() + a.industryId).compareTo(b.market.getId() + b.industryId);
            }
        });
    }

    /** The player accepts an offer: the money is paid out and the construction deadline starts. */
    public String acceptOffer(String contractId) {
        ensure();
        DevelopmentContract c = find(contractId);
        if (c == null || c.state != ContractState.OFFERED) return "dev.reason.gone";
        MarketAPI m = Global.getSector().getEconomy().getMarket(c.marketId);
        if (m == null || !m.isPlayerOwned()) return "dev.reason.colonyLost";
        if (m.hasIndustry(c.industryId)) return "dev.reason.alreadyBuilt";
        if (c.principal > lendable(c.investorId)) return "dev.reason.investorShort";
        sign(c);
        debit(c.investorId, c.principal);
        BankAccount loan = BankData.get().getLoanManager().takeDevelopmentLoan(c.id, c.investorName(), c.principal, c.rate, c.repayMonths);
        c.loanAccountId = loan.accountId;
        log.info("BOS: development contract " + c.id + " signed, " + c.principal + " paid to the player");
        return null;
    }

    public void declineOffer(String contractId) {
        ensure();
        DevelopmentContract c = find(contractId);
        if (c == null || c.state != ContractState.OFFERED) return;
        close(c, ContractState.DECLINED, "dev.outcome.declined");
    }

    // ------------------------------------------------------------------ the player as investor

    /** Projects the player can offer to finance: at markets of factions Favorable to the player (cached a day). */
    public List<Project> projectsForPlayer(int max) {
        ensure();
        if (projectCache != null && Global.getSector().getClock().getElapsedDaysSince(projectCacheTs) < 1f) {
            return projectCache.subList(0, Math.min(max, projectCache.size()));
        }
        List<Project> all = new ArrayList<Project>();
        for (MarketAPI m : Global.getSector().getEconomy().getMarketsCopy()) {
            if (m.isHidden() || m.isPlayerOwned() || m.getSize() < 4) continue;
            FactionAPI f = m.getFaction();
            if (f == null || !PoliticalFactions.isPolitical(f) || !favorable(f, PLAYER)) continue;
            if (BankData.get().getSectorEconomy().getTreasury(f.getId()) == null) continue;
            all.addAll(projectsAt(m));
        }
        sortByGain(all);
        projectCache = all;
        projectCacheTs = now();
        return all.subList(0, Math.min(max, all.size()));
    }

    /** One project by market and building (fresh, not cached), or null if it can't be financed now. */
    public Project project(String marketId, String industryId) {
        MarketAPI m = Global.getSector().getEconomy().getMarket(marketId);
        if (m == null) return null;
        for (Project p : projectsAt(m)) if (p.industryId.equals(industryId)) return p;
        return null;
    }

    /**
     * The player offers to finance a project at the given rate. Null if the faction accepted (the money is
     * paid and the building started), else why not ("dev.reason.*").
     */
    public String offerToFinance(String marketId, String industryId, float rate) {
        ensure();
        Project p = project(marketId, industryId);
        if (p == null) return "dev.reason.gone";
        FactionAPI f = p.market.getFaction();
        if (f == null || !PoliticalFactions.isPolitical(f) || !favorable(f, PLAYER)) return "dev.reason.notFavorable";
        if (p.principal() > lendable(PLAYER)) return "dev.reason.cantAfford";
        float utility = recipientUtility(p, p.principal(), rate, BankSettings.DEV_REPAY_MONTHS, f.getRelationship(PLAYER), strength(PLAYER));
        if (utility <= 0f) return "dev.reason.declined";
        DevelopmentContract c = newContract(PLAYER, f.getId(), p, rate);
        startNpcConstruction(c, p);
        BankData.get().addTransaction("DEVELOPMENT", -c.principal, Str.f("txd.devInvest", c.recipientName(), c.industryName, c.marketName));
        Global.getSector().getIntelManager().addIntel(new com.bankofstarsector.intel.DevelopmentIntel(c.id));
        projectCache = null;
        return null;
    }

    /** The investor pays, the recipient gets the money and really starts the building. */
    private void startNpcConstruction(DevelopmentContract c, Project p) {
        sign(c);
        debit(c.investorId, c.principal);
        creditNpc(c.recipientId, c.principal - p.cost); // the recipient pays for the building out of it
        p.market.addIndustry(p.industryId);
        Industry ind = p.market.getIndustry(p.industryId);
        if (ind != null) ind.startBuilding();
        log.info("BOS: development deal " + c.id + ": " + c.investorId + " finances " + c.industryId + " at " + c.marketId
            + " (" + c.recipientId + ") for " + c.principal);
    }

    // ------------------------------------------------------------------ NPC to NPC

    private void proposeNpcDeals(List<MarketAPI> markets) {
        if (!BankSettings.DEV_ENABLED) return;
        for (int attempt = 0; attempt < BankSettings.DEV_NPC_DEALS_PER_MONTH; attempt++) {
            if (activeNpcDeals() >= BankSettings.DEV_MAX_NPC_ACTIVE) return;
            if (!proposeOneNpcDeal(markets)) return;
        }
    }

    private boolean proposeOneNpcDeal(List<MarketAPI> markets) {
        SectorEconomy eco = BankData.get().getSectorEconomy();
        List<String> recipients = new ArrayList<String>(eco.getTreasuries().keySet());
        Collections.shuffle(recipients, random);
        for (String recipientId : recipients) {
            FactionAPI recipient = Global.getSector().getFaction(recipientId);
            if (recipient == null || !PoliticalFactions.isPolitical(recipient)) continue;
            List<Project> projects = new ArrayList<Project>();
            for (MarketAPI m : markets) if (recipientId.equals(m.getFactionId()) && !m.isPlayerOwned()) projects.addAll(projectsAt(m));
            if (projects.isEmpty()) continue;
            sortByGain(projects);
            for (String investorId : investorsFavorableTo(recipientId)) {
                if (investorId.equals(recipientId)) continue;
                FactionAPI investor = Global.getSector().getFaction(investorId);
                if (investor == null || recipient.isHostileTo(investor)) continue;
                float lendable = lendable(investorId);
                for (Project p : projects) {
                    if (p.principal() > lendable) continue;
                    float utility = recipientUtility(p, p.principal(), BankSettings.DEV_RATE, BankSettings.DEV_REPAY_MONTHS,
                        recipient.getRelationship(investorId), strength(investorId));
                    if (utility <= 0f) continue;
                    DevelopmentContract c = newContract(investorId, recipientId, p, BankSettings.DEV_RATE);
                    startNpcConstruction(c, p);
                    com.bankofstarsector.intel.DevelopmentLogIntel.ensure();
                    return true;
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ daily

    public void advanceDay(BankData data) {
        ensure();
        for (DevelopmentContract c : new ArrayList<DevelopmentContract>(contracts)) {
            switch (c.state) {
                case OFFERED:
                    if (Global.getSector().getClock().getElapsedDaysSince(c.offeredTs) > BankSettings.DEV_OFFER_DAYS) {
                        close(c, ContractState.EXPIRED, "dev.outcome.expired");
                    }
                    break;
                case BUILDING:
                    checkConstruction(c);
                    break;
                case DEMAND:
                    if (DevelopmentContract.daysUntil(c.demandDeadlineTs) > 0) break;
                    if (c.recipientIsPlayer()) toCollection(c);
                    else recoverFromTreasury(c);
                    break;
                default:
                    break;
            }
        }
    }

    private void checkConstruction(DevelopmentContract c) {
        MarketAPI m = Global.getSector().getEconomy().getMarket(c.marketId);
        boolean held = m != null && (c.recipientIsPlayer() ? m.isPlayerOwned() : c.recipientId.equals(m.getFactionId()));
        if (!held) {
            missed(c, "dev.outcome.marketLost");
            return;
        }
        Industry ind = m.getIndustry(c.industryId);
        if (ind != null && !ind.isBuilding() && ind.isFunctional()) {
            built(c);
            return;
        }
        if (DevelopmentContract.daysUntil(c.deadlineTs) < 0) missed(c, "dev.outcome.deadline");
    }

    private void built(DevelopmentContract c) {
        c.state = ContractState.REPAYING;
        c.builtTs = now();
        relate(c.investorId, c.recipientId, BankSettings.DEV_REL_BUILT);
        if (c.recipientIsPlayer()) {
            BankAccount loan = BankData.get().getLoanManager().findLoan(c.loanAccountId);
            if (loan != null) loan.minimalForce = true;
        } else {
            MarketAPI m = Global.getSector().getEconomy().getMarket(c.marketId);
            if (m != null) BankData.get().getSectorEconomy().addVentureStake(m);
        }
        if (c.involvesPlayer()) {
            Global.getSector().getCampaignUI().addMessage(Str.f("dev.msg.built", c.industryName, c.marketName, c.investorName()),
                Misc.getPositiveHighlightColor());
        }
    }

    /** Construction deadline missed (or the market lost): repay with penalty interest by a new deadline. */
    private void missed(DevelopmentContract c, String reason) {
        c.state = ContractState.DEMAND;
        c.outcome = reason;
        c.demandDeadlineTs = daysFromNow(BankSettings.DEV_DEMAND_DAYS);
        relate(c.investorId, c.recipientId, BankSettings.DEV_REL_MISSED);
        if (c.recipientIsPlayer()) {
            BankAccount loan = BankData.get().getLoanManager().findLoan(c.loanAccountId);
            if (loan != null) {
                loan.monthlyRate = c.penaltyRate;
                loan.baseMonthlyRate = c.penaltyRate;
            }
        }
        if (c.involvesPlayer()) {
            Global.getSector().getCampaignUI().addMessage(Str.f(c.recipientIsPlayer() ? "dev.msg.demand" : "dev.msg.demandNpc",
                c.industryName, c.marketName, "" + BankSettings.DEV_DEMAND_DAYS, c.recipientName()), Misc.getNegativeHighlightColor());
        }
    }

    /** The player ignored the demand: the whole balance is due and goes to collection by full force. */
    private void toCollection(DevelopmentContract c) {
        BankAccount loan = BankData.get().getLoanManager().findLoan(c.loanAccountId);
        if (loan == null || !loan.isOpenLoan()) return;
        c.state = ContractState.COLLECTION;
        relate(c.investorId, c.recipientId, BankSettings.DEV_REL_COLLECTION);
        loan.minimalForce = false;
        loan.amountPastDue = loan.remainingBalance;
        loan.currentBill = 0f;
        if (loan.status == LoanStatus.ACTIVE) loan.status = LoanStatus.OVERDUE;
        loan.daysOverdue = Math.max(loan.daysOverdue, BankSettings.DEFAULT_THRESHOLD_DAYS - 1);
        Global.getSector().getCampaignUI().addMessage(Str.f("dev.msg.collection", c.investorName(), Misc.getDGSCredits(loan.remainingBalance)),
            Misc.getNegativeHighlightColor());
    }

    /** An NPC recipient missed its deadline: the Confederation takes what it can from its treasury for the investor. */
    private void recoverFromTreasury(DevelopmentContract c) {
        FactionTreasury t = BankData.get().getSectorEconomy().getTreasury(c.recipientId);
        float recovered = t == null ? 0f : Math.min(c.owed, Math.max(0f, t.balance));
        if (recovered > 0f) {
            debit(c.recipientId, recovered);
            payInvestor(c, recovered, true);
        }
        c.owed -= recovered;
        relate(c.investorId, c.recipientId, BankSettings.DEV_REL_COLLECTION);
        if (c.owed <= 1f) {
            c.owed = 0f;
            close(c, ContractState.CLOSED, "dev.outcome.recovered");
        } else {
            close(c, ContractState.DEFAULTED, "dev.outcome.partial");
        }
        if (c.investorIsPlayer()) {
            Global.getSector().getCampaignUI().addMessage(Str.f("dev.msg.recovered", Misc.getDGSCredits(recovered), c.recipientName()),
                recovered > 0f ? Misc.getHighlightColor() : Misc.getNegativeHighlightColor());
        }
    }

    /** Money for the investor: an NPC's treasury at once; the player now (recoveries) or at month end (installments). */
    private float payInvestor(DevelopmentContract c, float amount, boolean now) {
        c.repaid += amount;
        if (!c.investorIsPlayer()) {
            creditNpc(c.investorId, amount);
            return 0f;
        }
        if (now) {
            Global.getSector().getPlayerFleet().getCargo().getCredits().add(amount);
            BankData.get().addTransaction("DEVELOPMENT", amount, Str.f("txd.devRecovered", c.recipientName()));
            return 0f;
        }
        return amount;
    }

    private void close(DevelopmentContract c, ContractState state, String outcome) {
        c.state = state;
        c.outcome = outcome;
        c.closedTs = now();
        com.bankofstarsector.intel.DevelopmentIntel.endFor(c.id);
    }

    // ------------------------------------------------------------------ the player's loan (recipient)

    /** How month-end billing treats the player's development loan. */
    public int billingMode(BankAccount loan) {
        DevelopmentContract c = find(loan.developmentContractId);
        if (c == null) return BILL_NORMAL;
        switch (c.state) {
            case BUILDING: return BILL_NOTHING;
            case DEMAND: return BILL_INTEREST;
            case REPAYING:
                return Global.getSector().getClock().getElapsedDaysSince(c.builtTs) < START_BILLING_DAYS ? BILL_NOTHING : BILL_NORMAL;
            default: return BILL_NORMAL;
        }
    }

    /** Every payment on the player's development loan goes to the investor (the Clearing Hall passes it on). */
    public void onLoanPayment(BankAccount loan, float amount) {
        DevelopmentContract c = find(loan.developmentContractId);
        if (c == null || amount <= 0f) return;
        c.repaid += amount;
        creditNpc(c.investorId, amount);
    }

    /** The player's development loan closed: paid off (relations improve) or settled by force. */
    public void onLoanClosed(BankAccount loan, boolean paidOff) {
        DevelopmentContract c = find(loan.developmentContractId);
        if (c == null || !c.state.isOpen()) return;
        if (paidOff) relate(c.investorId, c.recipientId, c.state == ContractState.REPAYING ? BankSettings.DEV_REL_CLOSED : 0f);
        close(c, paidOff ? ContractState.CLOSED : ContractState.DEFAULTED, paidOff ? "dev.outcome.repaid" : "dev.outcome.seized");
    }

    // ------------------------------------------------------------------ month end

    /**
     * Month end, after the treasuries are updated: NPC recipients repay (or accrue penalty interest), new offers
     * and deals are made. Returns what the player earns as an investor this month (booked in the report).
     */
    public float monthlyUpdate(BankData data) {
        ensure();
        float toPlayer = 0f;
        for (DevelopmentContract c : new ArrayList<DevelopmentContract>(contracts)) {
            if (c.recipientIsPlayer()) continue; // the player's side is billed as a loan
            if (c.state == ContractState.DEMAND) {
                c.owed += c.owed * c.penaltyRate;
            } else if (c.state == ContractState.REPAYING && Global.getSector().getClock().getElapsedDaysSince(c.builtTs) >= START_BILLING_DAYS) {
                toPlayer += npcInstallment(c);
            }
        }
        if (BankSettings.DEV_ENABLED) {
            List<MarketAPI> markets = Global.getSector().getEconomy().getMarketsCopy();
            maybeOfferToPlayer(markets);
            proposeNpcDeals(markets);
        }
        trimHistory();
        return toPlayer;
    }

    /** One month of an NPC recipient's repayment, from its treasury (it borrows from the PBC if short). */
    private float npcInstallment(DevelopmentContract c) {
        FactionTreasury t = BankData.get().getSectorEconomy().getTreasury(c.recipientId);
        if (t == null) {
            // The recipient no longer holds markets: whatever it still owes is lost.
            close(c, ContractState.DEFAULTED, "dev.outcome.recipientGone");
            return 0f;
        }
        float interest = c.owed * c.rate;
        c.owed += interest;
        float installment = c.monthsRepaid + 1 >= c.repayMonths ? c.owed : Math.min(c.owed, interest + c.principal / c.repayMonths);
        t.balance -= installment;
        c.owed -= installment;
        c.monthsRepaid++;
        float toPlayer = payInvestor(c, installment, false);
        if (c.owed <= 1f) {
            c.owed = 0f;
            relate(c.investorId, c.recipientId, BankSettings.DEV_REL_CLOSED);
            close(c, ContractState.CLOSED, "dev.outcome.repaid");
        }
        return toPlayer;
    }

    private void trimHistory() {
        int closed = 0;
        for (int i = contracts.size() - 1; i >= 0; i--) {
            DevelopmentContract c = contracts.get(i);
            if (c.state.isOpen() || c.state == ContractState.OFFERED) continue;
            if (++closed > HISTORY) contracts.remove(i);
        }
    }

    /** Off-game tests: the number of contracts in each state. */
    public int count(ContractState state) {
        ensure();
        int n = 0;
        for (DevelopmentContract c : contracts) if (c.state == state) n++;
        return n;
    }
}
