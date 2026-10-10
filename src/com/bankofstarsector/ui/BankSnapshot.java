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
        for (BankAccount loan : lm.getActiveLoans()) {
            String detail = LoanManager.formatCredits(loan.remainingBalance) + " | " + loan.getStatusDisplay();
            if (loan.heldFunds > 0f) detail += " | " + com.bankofstarsector.core.Str.f("nexus.loanHeld", LoanManager.formatCredits(loan.heldFunds));
            l.add(new Line(loan.loanType.getDisplayName(), detail, loan.status != LoanStatus.ACTIVE));
        }
        loans = Collections.unmodifiableList(l);

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
            JSONArray is = new JSONArray();
            for (Line x : investments) is.put(new JSONObject().put("name", x.label).put("detail", x.detail).put("loss", x.bad));
            o.put("investments", is);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
