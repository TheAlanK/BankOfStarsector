package com.bankofstarsector.ui;

import com.bankofstarsector.banking.*;
import com.bankofstarsector.core.BankData;
import com.fs.starfarer.api.Global;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Immutable picture of the player's bank account, built on the game thread and read by
 * other threads (NexusUI refresh thread, NexusUI HTTP server). Mirrors NexusUI's own
 * GameDataBridge design: game state is only touched on the game thread, readers get a
 * volatile snapshot.
 */
public final class BankSnapshot {

    private static volatile BankSnapshot latest = null;

    public final String date;
    public final float netWorth, credits, debt, invested, held, late, dueNow, warSurcharge, disruption, sovereignDebt;
    public final boolean autopay, restricted;
    public final int score;
    public final String bracket, bankruptcyState, bankruptcyLabel, scoreText;
    public final List<Line> loans;
    /** Score at each monthly report, newest first (0 = no score), and what moved it ("factor.key:delta" or ""). */
    public final List<Integer> scoreHistory;
    public final List<String> scoreChanges;
    /** Installment loans: projected repayment if paid on time (parallel to the loans list's order). */
    public final List<Schedule> schedules;

    public static final class Schedule {
        public final String name;
        public final int monthsLeft;
        public final float nextPayment, remainingInterest, remainingTotal;

        Schedule(String name, LoanSchedule s) {
            this.name = name;
            this.monthsLeft = s.rows.size();
            this.nextPayment = s.firstPayment;
            this.remainingInterest = s.totalInterest;
            this.remainingTotal = s.totalPaid;
        }
    }
    /** Credit line (0.3.0); lineId is null when the player has none. */
    public final String lineId;
    public final float lineBalance, lineLimit, lineAvailable, lineUtilization, lineMinimumDue, lineStatementDue, lineLastInterest;
    public final boolean lineAutopayFull, lineLate;
    public final List<Line> investments;

    /** One row in a list: label, value, and whether it should be shown as a problem. */
    public static final class Line {
        public final String label;
        public final String detail;
        public final boolean bad;

        Line(String label, String detail, boolean bad) {
            this.label = label;
            this.detail = detail;
            this.bad = bad;
        }
    }

    private BankSnapshot(BankData data) {
        LoanManager lm = data.getLoanManager();
        InvestmentManager im = data.getInvestmentManager();
        InterestEngine engine = data.getInterestEngine();
        date = Global.getSector().getClock().getDateString();
        credits = Global.getSector().getPlayerFleet().getCargo().getCredits().get();
        debt = lm.getTotalDebt();
        invested = im.getTotalValue();
        held = lm.getTotalHeldFunds();
        netWorth = data.getNetWorth();
        late = lm.getTotalLate();
        dueNow = lm.getTotalCurrentBills();
        autopay = data.isAutopayEnabled();
        restricted = data.getCollectionManager().isBankingRestricted();
        score = data.getCreditScoreManager().getScore();
        scoreText = data.getCreditScoreManager().getScoreText();
        bracket = data.getCreditScoreManager().getBracket();
        warSurcharge = engine.getWarSurcharge();
        disruption = engine.getMarketDisruptionModifier();
        sovereignDebt = data.getAssetSeizureManager().getTotalSovereignDebt();
        bankruptcyState = data.getBankruptcyManager().getState().name();
        bankruptcyLabel = com.bankofstarsector.collection.BankruptcyManager.stateName(data.getBankruptcyManager().getState());

        List<Line> l = new ArrayList<Line>();
        List<Schedule> sch = new ArrayList<Schedule>();
        for (BankAccount loan : lm.getActiveLoans()) {
            if (loan.loanType.isRevolving()) continue; // shown in its own card
            LoanSchedule projected = LoanSchedule.remaining(loan);
            sch.add(new Schedule(loan.loanType.getDisplayName(), projected));
            String detail = LoanManager.formatCredits(loan.remainingBalance) + " | " + loan.getStatusDisplay()
                + " | " + com.bankofstarsector.core.Str.f("nexus.monthsLeft", projected.rows.size());
            if (loan.heldFunds > 0f) detail += " | " + com.bankofstarsector.core.Str.f("nexus.loanHeld", LoanManager.formatCredits(loan.heldFunds));
            l.add(new Line(loan.loanType.getDisplayName(), detail, loan.status != LoanStatus.ACTIVE));
        }
        loans = Collections.unmodifiableList(l);
        schedules = Collections.unmodifiableList(sch);
        CreditScoreManager csm = data.getCreditScoreManager();
        scoreHistory = Collections.unmodifiableList(new ArrayList<Integer>(csm.getScoreHistory()));
        List<String> ch = new ArrayList<String>();
        for (int i = 0; i < scoreHistory.size(); i++) ch.add(csm.getScoreChange(i));
        scoreChanges = Collections.unmodifiableList(ch);

        BankAccount line = lm.getCreditLine();
        lineId = line != null ? line.accountId : null;
        lineBalance = line != null ? line.remainingBalance : 0f;
        lineLimit = line != null ? line.creditLimit : 0f;
        lineAvailable = line != null ? line.getAvailableCredit() : 0f;
        lineUtilization = line != null ? line.getUtilization() : 0f;
        lineMinimumDue = line != null ? line.amountPastDue : 0f;
        lineStatementDue = line != null ? line.getStatementRemaining() : 0f;
        lineLastInterest = line != null ? line.lastInterest : 0f;
        lineAutopayFull = line != null && line.autopayFull;
        lineLate = line != null && line.getLateAmount() > 1f;

        List<Line> inv = new ArrayList<Line>();
        for (BankAccount a : im.getActiveInvestments()) {
            float pct = a.investedAmount > 0 ? (a.currentValue - a.investedAmount) / a.investedAmount * 100f : 0f;
            inv.add(new Line(a.investmentType.getDisplayName(),
                LoanManager.formatCredits(a.currentValue) + String.format(" | %+.1f%%", pct) + " | " + a.getStatusDisplay(),
                pct < 0));
        }
        investments = Collections.unmodifiableList(inv);
    }

    /** Game thread only. */
    public static void capture() {
        if (Global.getSector() == null || Global.getSector().getPlayerFleet() == null) return;
        latest = new BankSnapshot(BankData.get());
    }

    /** Any thread. May be null before the first capture. */
    public static BankSnapshot get() {
        return latest;
    }

    public static void clear() {
        latest = null;
    }

    public String toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("date", date);
            o.put("netWorth", netWorth);
            o.put("credits", credits);
            o.put("debt", debt);
            o.put("invested", invested);
            o.put("heldFunds", held);
            o.put("pastDue", late);
            o.put("dueByMonthEnd", dueNow);
            o.put("autopay", autopay);
            o.put("bankingRestricted", restricted);
            o.put("creditScore", "--".equals(scoreText) ? JSONObject.NULL : (Object) Integer.valueOf(score));
            o.put("bracket", bracket);
            o.put("warSurcharge", warSurcharge);
            o.put("marketDisruption", disruption);
            o.put("sovereignDebt", sovereignDebt);
            o.put("bankruptcy", bankruptcyState);
            JSONArray ls = new JSONArray();
            for (Line x : loans) ls.put(new JSONObject().put("name", x.label).put("detail", x.detail).put("problem", x.bad));
            o.put("loans", ls);
            JSONArray ss = new JSONArray();
            for (Schedule x : schedules) {
                ss.put(new JSONObject().put("name", x.name).put("monthsLeft", x.monthsLeft).put("nextPayment", x.nextPayment)
                    .put("remainingInterest", x.remainingInterest).put("remainingTotal", x.remainingTotal));
            }
            o.put("loanSchedules", ss);
            JSONArray hs = new JSONArray();
            for (int i = 0; i < scoreHistory.size(); i++) {
                String c = scoreChanges.get(i);
                int colon = c.lastIndexOf(':');
                JSONObject h = new JSONObject().put("monthsAgo", i)
                    .put("score", scoreHistory.get(i) > 0 ? (Object) scoreHistory.get(i) : JSONObject.NULL);
                if (colon > 0) h.put("factor", c.substring("factor.".length(), colon)).put("factorChange", Integer.parseInt(c.substring(colon + 1)));
                hs.put(h);
            }
            o.put("scoreHistory", hs);
            if (lineId == null) {
                o.put("creditLine", JSONObject.NULL);
            } else {
                o.put("creditLine", new JSONObject()
                    .put("balance", lineBalance).put("limit", lineLimit).put("available", lineAvailable)
                    .put("utilization", lineUtilization).put("minimumDue", lineMinimumDue)
                    .put("statementDue", lineStatementDue).put("lastInterest", lineLastInterest)
                    .put("autopayFullStatement", lineAutopayFull).put("late", lineLate));
            }
            JSONArray is = new JSONArray();
            for (Line x : investments) is.put(new JSONObject().put("name", x.label).put("detail", x.detail).put("loss", x.bad));
            o.put("investments", is);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
