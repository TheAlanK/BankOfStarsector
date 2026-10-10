package com.bankofstarsector.banking;

import com.bankofstarsector.core.BankSettings;
import com.fs.starfarer.api.Global;

import java.io.Serializable;
import java.util.*;

/**
 * Credit bureau: keeps a credit report (tradelines, inquiries, public records) and computes a
 * FICO-style score from it.
 *
 * Modelled on how real bureaus work (FICO / VantageScore / Serasa):
 *  - Factors and weights (FICO): payment history 35%, amounts owed 30%, length of history 15%,
 *    new credit 10%, credit mix 10%. Score range 300-850.
 *  - Lenders report once per month. Payments, payoffs and balances only reach the report at the
 *    monthly reporting cycle, so paying a loan off the day it was taken produces no positive
 *    history at all - only an inquiry and a young account.
 *  - Hard inquiries are recorded at application, stay 24 months and count for 12. Inquiries for
 *    the same kind of credit within 45 days are grouped as one (rate-shopping rule).
 *  - Delinquencies are reported from 30 days late (30/60/90/120+), then charge-off; they stay
 *    84 months (7 years) with fading weight. Bankruptcy is a public record for 120 months.
 *  - Minimum scoring criteria: at least one account open 6+ months and one account reported in
 *    the last 6 months; otherwise the file is "thin" and unscoreable.
 *  - The bureau returns reason codes: the factors that cost the most points.
 * The exact FICO scorecards are proprietary; the point tables below are an approximation that
 * respects the published weights and behaviours.
 */
public class CreditBureau implements Serializable {

    private static final long serialVersionUID = 1L;

    public static final float DAYS_PER_MONTH = 30f;
    public static final int MIN_SCORE = 300, MAX_SCORE = 850;

    // Factor maxima (sum 550 = 850 - 300), proportional to the FICO weights.
    public static final float PAYMENT_MAX = 192f, AMOUNTS_MAX = 165f, LENGTH_MAX = 83f, NEW_MAX = 55f, MIX_MAX = 55f;

    /** LINE is the sovereign installment loan (kept for saves); REVOLVING is the credit line (0.3.0). */
    public enum Category { PERSONAL, BUSINESS, LINE, REVOLVING }

    public enum EventType { LATE_30, LATE_60, LATE_90, LATE_120, CHARGE_OFF, REPOSSESSION }

    public static Category categoryOf(LoanType t) {
        switch (t) {
            case CORPORATE:
            case MEGACORP: return Category.BUSINESS;
            case SOVEREIGN: return Category.LINE;
            case CREDIT_LINE: return Category.REVOLVING;
            default: return Category.PERSONAL;
        }
    }

    /** One account as the bureau sees it. Survives the loan itself being removed by the bank. */
    public static class Tradeline implements Serializable {
        private static final long serialVersionUID = 1L;
        public String accountId;
        public LoanType loanType;
        public Category category;
        public float originalAmount;
        public float reportedBalance;
        public long openedTs;
        public long closedTs;          // 0 = open
        public boolean closedByEnforcement;
        public long lastReportedTs;
        public int monthsReported;
        public int monthsOnTime;
        public boolean pastDueNow;
        public boolean chargedOff;
        /** Revolving accounts (0.3.0): the reported credit limit. */
        public float creditLimit;
        public List<Event> events = new ArrayList<Event>();

        public boolean isOpen() { return closedTs == 0L; }

        public boolean isDerogatory() {
            return chargedOff || closedByEnforcement || !events.isEmpty();
        }
    }

    public static class Event implements Serializable {
        private static final long serialVersionUID = 1L;
        public EventType type;
        public long ts;

        Event(EventType type, long ts) {
            this.type = type;
            this.ts = ts;
        }
    }

    public static class Inquiry implements Serializable {
        private static final long serialVersionUID = 1L;
        public Category category;
        public long ts;
        /** False when grouped with an earlier same-category inquiry inside the rate-shopping window. */
        public boolean counts;

        Inquiry(Category category, long ts, boolean counts) {
            this.category = category;
            this.ts = ts;
            this.counts = counts;
        }
    }

    /** Result of one scoring run: score (or null if unscoreable), factor points and reasons. */
    public static class Result implements Serializable {
        private static final long serialVersionUID = 1L;
        public Integer score;
        public float payment, amounts, length, newCredit, mix;
        public List<String> reasons = new ArrayList<String>();
        // report summary for the terminal
        public int openAccounts, closedAccounts, monthsOnTime, inquiries12, accountsOpened12;
        public int late30, late60, late90, late120, chargeOffs, repossessions;
        public float oldestMonths, averageMonths, balanceRatio;
        /** Revolving utilization (statement balance / limit of open credit lines). Only meaningful when hasRevolving(). */
        public float utilization, revolvingBalance, revolvingLimit;

        /** Not a -1 sentinel: a Result cached in an older save loads these fields as 0. */
        public boolean hasRevolving() { return revolvingLimit > 0f || revolvingBalance > 0f; }
        public boolean bankruptcyOnFile, pastDueNow;
    }

    private List<Tradeline> tradelines = new ArrayList<Tradeline>();
    private List<Inquiry> inquiries = new ArrayList<Inquiry>();
    private long bankruptcyTs = 0L;
    private Result last;

    // ------------------------------------------------------------------ time helpers

    static long now() {
        return Global.getSector().getClock().getTimestamp();
    }

    static float monthsSince(long ts) {
        if (ts == 0L) return 0f;
        return Global.getSector().getClock().getElapsedDaysSince(ts) / DAYS_PER_MONTH;
    }

    // ------------------------------------------------------------------ report updates

    /** Application for credit: a hard inquiry (grouped with same-type inquiries inside the window). */
    public void recordInquiry(LoanType type) {
        Category c = categoryOf(type);
        boolean counts = true;
        for (Inquiry i : inquiries) {
            if (i.category == c && i.counts && monthsSince(i.ts) * DAYS_PER_MONTH < BankSettings.INQUIRY_DEDUPE_DAYS) {
                counts = false;
                break;
            }
        }
        inquiries.add(new Inquiry(c, now(), counts));
        recompute();
    }

    /** New account. Lenders report it with the next cycle, but the opening date is the real one. */
    public void openTradeline(BankAccount loan) {
        if (find(loan.accountId) != null) return;
        Tradeline t = new Tradeline();
        t.accountId = loan.accountId;
        t.loanType = loan.loanType;
        t.category = categoryOf(loan.loanType);
        t.originalAmount = loan.principal;
        t.reportedBalance = loan.remainingBalance;
        t.creditLimit = loan.creditLimit;
        t.openedTs = loan.createdTimestamp != 0L ? loan.createdTimestamp : now();
        tradelines.add(t);
        recompute();
    }

    public void recordBankruptcy() {
        bankruptcyTs = now();
        recompute();
    }

    /**
     * Default: the lender reports the charge-off when it happens. Recording it at the next monthly
     * report instead would miss defaults that a seizure or a deposit settles before month end.
     */
    public void recordChargeOff(String accountId) {
        Tradeline t = find(accountId);
        if (t == null || t.chargedOff) return;
        t.chargedOff = true;
        t.events.add(new Event(EventType.CHARGE_OFF, now()));
        recompute();
    }

    /**
     * Monthly reporting cycle: every open account reports its balance and status; closures,
     * delinquencies, charge-offs and repossessions are recorded; old items age off.
     */
    public void monthlyReport(LoanManager lm) {
        long ts = now();
        for (Tradeline t : tradelines) {
            if (!t.isOpen()) continue;
            BankAccount loan = lm.findLoan(t.accountId);
            t.lastReportedTs = ts;
            if (loan == null || loan.status == LoanStatus.PAID_OFF || loan.status == LoanStatus.SEIZED) {
                t.closedTs = ts;
                t.reportedBalance = 0f;
                t.pastDueNow = false;
                if (loan != null && loan.status == LoanStatus.SEIZED) {
                    t.closedByEnforcement = true;
                    if (loan.loanType.isBuilder()) {
                        // Settled from its own deposit after default: lenders report a charge-off.
                        if (!t.chargedOff) t.events.add(new Event(EventType.CHARGE_OFF, ts));
                        t.chargedOff = true;
                    } else {
                        t.events.add(new Event(EventType.REPOSSESSION, ts));
                    }
                }
                continue;
            }
            t.monthsReported++;
            // Lenders report a credit line's statement balance, even if it is paid in full afterwards;
            // only paying down before the statement lowers what the bureau sees.
            t.reportedBalance = loan.loanType.isRevolving() ? loan.statementBalance : loan.remainingBalance;
            t.creditLimit = loan.creditLimit;
            int d = loan.status == LoanStatus.ACTIVE ? 0 : loan.daysOverdue;
            t.pastDueNow = d >= 30;
            if (loan.status == LoanStatus.DEFAULTED) {
                // Normally already recorded at the default (recordChargeOff); this covers older saves.
                // A charged-off account then reports its balance, not a new late mark every month.
                if (!t.chargedOff) {
                    t.chargedOff = true;
                    t.events.add(new Event(EventType.CHARGE_OFF, ts));
                }
            } else if (d >= 120) {
                t.events.add(new Event(EventType.LATE_120, ts));
            } else if (d >= 90) {
                t.events.add(new Event(EventType.LATE_90, ts));
            } else if (d >= 60) {
                t.events.add(new Event(EventType.LATE_60, ts));
            } else if (d >= 30) {
                t.events.add(new Event(EventType.LATE_30, ts));
            } else {
                // Lenders don't report lateness under 30 days.
                t.monthsOnTime++;
            }
        }
        purge();
        recompute();
    }

    /** Ages items off the report: derogatory marks 84 months, closed clean accounts 120, inquiries 24. */
    private void purge() {
        for (Tradeline t : tradelines) {
            Iterator<Event> it = t.events.iterator();
            while (it.hasNext()) if (monthsSince(it.next().ts) > BankSettings.DEROGATORY_MONTHS) it.remove();
        }
        Iterator<Tradeline> ti = tradelines.iterator();
        while (ti.hasNext()) {
            Tradeline t = ti.next();
            if (t.isOpen()) continue;
            float age = monthsSince(t.closedTs);
            if (t.isDerogatory() ? age > BankSettings.DEROGATORY_MONTHS : age > BankSettings.CLOSED_ACCOUNT_MONTHS) ti.remove();
        }
        Iterator<Inquiry> ii = inquiries.iterator();
        while (ii.hasNext()) if (monthsSince(ii.next().ts) > 24f) ii.remove();
        if (bankruptcyTs != 0L && monthsSince(bankruptcyTs) > BankSettings.BANKRUPTCY_RECORD_MONTHS) bankruptcyTs = 0L;
    }

    // ------------------------------------------------------------------ scoring

    public Result getResult() {
        if (last == null) recompute();
        return last;
    }

    public boolean isScoreable() {
        boolean aged = false, recent = false;
        for (Tradeline t : tradelines) {
            if (monthsSince(t.openedTs) >= BankSettings.MIN_SCORING_MONTHS) aged = true;
            long reported = t.isOpen() ? now() : t.closedTs;
            if (monthsSince(reported) <= BankSettings.MIN_SCORING_MONTHS) recent = true;
        }
        return aged && recent;
    }

    private static float decay(float monthsAgo) {
        if (monthsAgo < 12f) return 1f;
        if (monthsAgo < 24f) return 0.75f;
        if (monthsAgo < 48f) return 0.5f;
        return 0.25f;
    }

    private static float severity(EventType e) {
        switch (e) {
            case LATE_30: return 45f;
            case LATE_60: return 75f;
            case LATE_90: return 100f;
            case LATE_120: return 125f;
            case CHARGE_OFF: return 155f;
            default: return 165f; // repossession
        }
    }

    public void recompute() {
        Result r = new Result();
        Map<String, Float> lost = new LinkedHashMap<String, Float>();

        // ---- Payment history (35%)
        float worst = 0f, others = 0f;
        boolean seriousRecent = false;
        int onTime = 0;
        float newestMark = -1f;
        for (Tradeline t : tradelines) {
            onTime += t.monthsOnTime;
            if (t.pastDueNow) r.pastDueNow = true;
            for (Event e : t.events) {
                float w = severity(e.type) * decay(monthsSince(e.ts));
                if (w > worst) { others += worst; worst = w; } else others += w;
                switch (e.type) {
                    case LATE_30: r.late30++; break;
                    case LATE_60: r.late60++; break;
                    case LATE_90: r.late90++; break;
                    case LATE_120: r.late120++; break;
                    case CHARGE_OFF: r.chargeOffs++; break;
                    default: r.repossessions++; break;
                }
                if (severity(e.type) >= 100f && monthsSince(e.ts) < 24f) seriousRecent = true;
                float age = monthsSince(e.ts);
                if (newestMark < 0f || age < newestMark) newestMark = age;
            }
        }
        float publicRecord = 0f;
        if (bankruptcyTs != 0L) {
            r.bankruptcyOnFile = true;
            float m = monthsSince(bankruptcyTs);
            publicRecord = 190f * (m < 24f ? 1f : m < 60f ? 0.7f : 0.4f);
        }
        r.monthsOnTime = onTime;
        float clean = (float) Math.sqrt(Math.min(1f, onTime / 36f));
        float base = PAYMENT_MAX * (0.55f + 0.45f * clean);
        // Scorecard switch: any derogatory mark moves the file off the clean-file scorecard, so the
        // first slip on a spotless report costs the most (FICO: one 30-day late, -60..-110 on good scores).
        float scorecard = newestMark >= 0f ? 25f * decay(newestMark) : 0f;
        float delinquency = Math.min(PAYMENT_MAX, worst + 0.3f * others + scorecard);
        float pastDuePenalty = r.pastDueNow ? 40f : 0f;
        r.payment = clamp(base - delinquency - publicRecord - pastDuePenalty, 0f, PAYMENT_MAX);
        lost.put(seriousRecent ? "reason.seriousDelinquency" : "reason.delinquency", delinquency);
        lost.put("reason.publicRecord", publicRecord);
        lost.put("reason.pastDueNow", pastDuePenalty);
        lost.put("reason.limitedPaymentHistory", PAYMENT_MAX - base);

        // ---- Amounts owed (30%)
        // Installment loans are judged by balance vs original amount; credit lines by utilization
        // (balance / limit), which weighs more and has no memory: paying it down helps at the next report.
        float bal = 0f, orig = 0f, revBal = 0f, revLimit = 0f;
        int withBalance = 0;
        for (Tradeline t : tradelines) {
            if (t.isOpen()) {
                r.openAccounts++;
                if (t.category == Category.REVOLVING) {
                    revBal += t.reportedBalance;
                    revLimit += t.creditLimit;
                } else {
                    bal += t.reportedBalance;
                    orig += t.originalAmount;
                }
                if (t.reportedBalance > 1f) withBalance++;
            } else {
                r.closedAccounts++;
            }
        }
        float ratioPts;
        if (orig <= 0f) {
            ratioPts = 140f; // no installment debt: good, but not the best possible
            r.balanceRatio = 0f;
        } else {
            float ratio = bal / orig;
            r.balanceRatio = ratio;
            ratioPts = ratio <= 0.3f ? 165f : ratio <= 0.5f ? 150f : ratio <= 0.7f ? 130f
                : ratio <= 0.85f ? 105f : ratio <= 0.95f ? 85f : 70f;
        }
        float utilPts = -1f;
        if (revLimit > 0f || revBal > 0f) {
            float u = revLimit > 0f ? revBal / revLimit : 1f;
            r.utilization = u;
            r.revolvingBalance = revBal;
            r.revolvingLimit = revLimit;
            // 1-10% is best; 0% scores slightly lower (no recent revolving use), as in FICO.
            utilPts = u <= 0f ? 155f : u <= 0.1f ? 165f : u <= 0.3f ? 150f : u <= 0.5f ? 120f
                : u <= 0.75f ? 90f : u <= 0.9f ? 60f : 35f;
        }
        float utilWeight = utilPts < 0f ? 0f : orig > 0f ? 0.65f : 1f;
        float amountsPts = utilWeight == 0f ? ratioPts : utilWeight * utilPts + (1f - utilWeight) * ratioPts;
        float countPenalty = withBalance >= 5 ? 30f : withBalance == 4 ? 20f : withBalance == 3 ? 10f : 0f;
        r.amounts = clamp(amountsPts - countPenalty, 0f, AMOUNTS_MAX);
        lost.put("reason.balanceRatio", (1f - utilWeight) * (AMOUNTS_MAX - ratioPts));
        lost.put("reason.utilization", utilWeight * (AMOUNTS_MAX - utilPts));
        lost.put("reason.tooManyBalances", countPenalty);

        // ---- Length of credit history (15%)
        float oldest = 0f, sumAge = 0f;
        for (Tradeline t : tradelines) {
            float age = monthsSince(t.openedTs);
            oldest = Math.max(oldest, age);
            sumAge += age;
        }
        r.oldestMonths = oldest;
        r.averageMonths = tradelines.isEmpty() ? 0f : sumAge / tradelines.size();
        float oldestPts = 40f * Math.min(1f, oldest / 96f);
        float avgPts = 43f * Math.min(1f, r.averageMonths / 60f);
        r.length = oldestPts + avgPts;
        lost.put("reason.shortHistory", LENGTH_MAX - r.length);

        // ---- New credit (10%)
        int inq = 0;
        for (Inquiry i : inquiries) if (i.counts && monthsSince(i.ts) <= 12f) inq++;
        r.inquiries12 = inq;
        float inqPts = inq == 0 ? 25f : inq == 1 ? 20f : inq == 2 ? 15f : inq == 3 ? 9f : inq == 4 ? 4f : 0f;
        int opened = 0;
        float mostRecent = Float.MAX_VALUE;
        for (Tradeline t : tradelines) {
            float age = monthsSince(t.openedTs);
            if (age <= 12f) opened++;
            mostRecent = Math.min(mostRecent, age);
        }
        r.accountsOpened12 = opened;
        float openPts = opened == 0 ? 15f : opened == 1 ? 12f : opened == 2 ? 7f : opened == 3 ? 3f : 0f;
        float recentPts = mostRecent >= 12f ? 15f : mostRecent >= 6f ? 11f : mostRecent >= 3f ? 7f : 3f;
        r.newCredit = inqPts + openPts + recentPts;
        lost.put("reason.inquiries", 25f - inqPts);
        lost.put("reason.newAccounts", 15f - openPts);
        lost.put("reason.recentOpening", 15f - recentPts);

        // ---- Credit mix (10%)
        Set<Category> kinds = EnumSet.noneOf(Category.class);
        for (Tradeline t : tradelines) kinds.add(t.category);
        // FICO rewards handling both installment and revolving credit; the top of the scale needs both.
        boolean revolving = kinds.contains(Category.REVOLVING);
        int installmentKinds = kinds.size() - (revolving ? 1 : 0);
        if (kinds.isEmpty()) r.mix = 0f;
        else if (!revolving) r.mix = installmentKinds == 1 ? 25f : installmentKinds == 2 ? 35f : 40f;
        else r.mix = installmentKinds == 0 ? 25f : installmentKinds == 1 ? 45f : installmentKinds == 2 ? 50f : MIX_MAX;
        lost.put("reason.mix", MIX_MAX - r.mix);

        r.score = isScoreable()
            ? Math.round(clamp(MIN_SCORE + r.payment + r.amounts + r.length + r.newCredit + r.mix, MIN_SCORE, MAX_SCORE))
            : null;

        // ---- Reason codes: biggest point losses first (up to 4, a 5th if inquiries qualify)
        List<Map.Entry<String, Float>> entries = new ArrayList<Map.Entry<String, Float>>(lost.entrySet());
        Collections.sort(entries, new Comparator<Map.Entry<String, Float>>() {
            public int compare(Map.Entry<String, Float> a, Map.Entry<String, Float> b) { return Float.compare(b.getValue(), a.getValue()); }
        });
        for (Map.Entry<String, Float> e : entries) {
            if (e.getValue() < 3f) break;
            if (r.reasons.size() >= 4) {
                if ("reason.inquiries".equals(e.getKey())) r.reasons.add(e.getKey());
                continue;
            }
            r.reasons.add(e.getKey());
        }
        if (r.score == null) r.reasons.add(0, "reason.thinFile");
        last = r;
    }

    private static float clamp(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public Tradeline find(String accountId) {
        for (Tradeline t : tradelines) if (t.accountId.equals(accountId)) return t;
        return null;
    }

    public List<Tradeline> getTradelines() { return tradelines; }
    public List<Inquiry> getInquiries() { return inquiries; }
}
