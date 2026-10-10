import com.bankofstarsector.banking.*;
import com.bankofstarsector.core.*;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.SettingsAPI;
import com.fs.starfarer.api.campaign.SectorAPI;
import com.fs.starfarer.api.campaign.econ.MonthlyReport;
import com.fs.starfarer.api.impl.campaign.shared.SharedData;
import com.fs.starfarer.api.util.MutableValue;

import java.lang.reflect.*;
import java.util.*;

/**
 * Off-game simulation of Bank of Starsector's loan lifecycle. A deep-stub SectorAPI (java proxies)
 * stands in for the campaign; the mod's compiled classes run unmodified.
 */
public class BankSim {

    static long now = 1_000_000_000L;
    static final long DAY = 24L * 3600L * 1000L; // placeholder; clock below converts with this
    static MutableValue credits = new MutableValue(0f);
    static Map<String, Object> persistent = new HashMap<String, Object>();
    static List<String> messages = new ArrayList<String>();
    static int failures = 0;

    public static void main(String[] args) throws Exception {
        Global.setSettings(stub(SettingsAPI.class));
        Global.setSector(stub(SectorAPI.class));
        BankSettings.load(); // no settings file off-game -> defaults

        scenarioPaysOnSchedule();
        scenarioNeverPays();
        scenarioCureAfterLate();
        scenarioIdsSurviveReload();
        scenarioDefaultSeizesInvestments();
        scenarioSeizureCoversWholeLoan();
        scenarioTranslations();

        System.out.println(failures == 0 ? "\nALL SCENARIOS PASSED" : "\nFAILURES: " + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    // ------------------------------------------------------------------ scenarios

    static void scenarioPaysOnSchedule() {
        reset(2_000_000f);
        BankData data = BankData.get();
        LoanType type = LoanType.CORPORATE;
        float rate = data.getInterestEngine().calculateEffectiveLoanRate(type, data.getCreditScoreManager().getScore());
        BankAccount loan = data.getLoanManager().takeLoan(type, 400_000f, rate);
        int scoreBefore = data.getCreditScoreManager().getScore();
        float totalPaid = 0f;
        for (int m = 1; m <= 13 && loan.status != LoanStatus.PAID_OFF; m++) {
            float before = credits.get();
            advanceDays(30);
            monthEnd();
            totalPaid += before - credits.get();
        }
        System.out.printf("[schedule] %s %.0f at %.2f%%/mo -> status %s after %d months, paid %.0f, score %d -> %d%n",
            type.displayName, 400_000f, rate * 100, loan.status, loan.monthsElapsed, totalPaid,
            scoreBefore, data.getCreditScoreManager().getScore());
        check("loan paid off within term", loan.status == LoanStatus.PAID_OFF);
        check("total paid exceeds principal (interest charged)", totalPaid > 400_000f);
        check("never late", loan.missedPayments == 0);
        check("score improved", data.getCreditScoreManager().getScore() > scoreBefore);
    }

    static void scenarioNeverPays() {
        reset(0f);
        BankData data = BankData.get();
        data.setAutopayEnabled(false);
        BankAccount loan = data.getLoanManager().takeLoan(LoanType.SMALL, 100_000f, 0.05f);
        credits.set(0f);
        int scoreBefore = data.getCreditScoreManager().getScore();
        advanceDays(30); monthEnd();          // first bill: due, not late
        check("first bill is not late yet", loan.status == LoanStatus.ACTIVE && loan.getLateAmount() < 1f);
        advanceDays(30); monthEnd();          // second month end: first bill now late
        check("unpaid bill becomes OVERDUE a month later", loan.status == LoanStatus.OVERDUE);
        int day = 0;
        while (loan.status != LoanStatus.DEFAULTED && day < 200) { advanceDays(1); day++; if (day % 30 == 0) monthEnd(); }
        System.out.printf("[never pays] defaulted after %d more days, days overdue %d, missed %d, past due %.0f, score %d -> %d, collection dispatched: %s%n",
            day, loan.daysOverdue, loan.missedPayments, loan.amountPastDue, scoreBefore,
            data.getCreditScoreManager().getScore(), data.getCollectionManager().hasActiveCollection(loan.accountId));
        check("defaults at the threshold", loan.status == LoanStatus.DEFAULTED && loan.daysOverdue >= BankSettings.DEFAULT_THRESHOLD_DAYS);
        check("collection fleet was dispatched", data.getCollectionManager().hasActiveCollection(loan.accountId));
        check("penalty interest raised the rate", loan.monthlyRate > loan.baseMonthlyRate);
        check("score fell", data.getCreditScoreManager().getScore() < scoreBefore);
        check("bankruptcy is available", data.getBankruptcyManager().canFileBankruptcy(data));
    }

    static void scenarioCureAfterLate() {
        reset(0f);
        BankData data = BankData.get();
        data.setAutopayEnabled(false);
        BankAccount loan = data.getLoanManager().takeLoan(LoanType.EMERGENCY, 40_000f, 0.08f);
        credits.set(0f);
        advanceDays(30); monthEnd();
        advanceDays(30); monthEnd();
        advanceDays(40);
        check("overdue before payment", loan.status == LoanStatus.OVERDUE);
        credits.set(1_000_000f);
        boolean ok = data.getLoanManager().payPastDue(loan.accountId);
        System.out.printf("[cure] paid=%s status=%s daysOverdue=%d late=%.0f restricted=%s%n", ok, loan.status,
            loan.daysOverdue, loan.getLateAmount(), data.getCollectionManager().isBankingRestricted());
        check("paying the amount due cures the loan", ok && loan.status == LoanStatus.ACTIVE && loan.daysOverdue == 0);
        check("rate back to contract rate", Math.abs(loan.monthlyRate - loan.baseMonthlyRate) < 1e-6);
        check("banking no longer restricted", !data.getCollectionManager().isBankingRestricted());
    }

    static void scenarioIdsSurviveReload() throws Exception {
        reset(1_000_000f);
        BankData data = BankData.get();
        BankAccount a = data.getLoanManager().takeLoan(LoanType.EMERGENCY, 10_000f, 0.08f);
        BankAccount b = data.getInvestmentManager().invest(InvestmentType.SAVINGS, 20_000f);
        // Simulate a 0.1.x save: the counter field did not exist (deserializes as 0).
        Field f = BankData.class.getDeclaredField("nextAccountNumber");
        f.setAccessible(true);
        f.setInt(data, 0);
        BankAccount c = BankData.get().getLoanManager().takeLoan(LoanType.EMERGENCY, 10_000f, 0.08f);
        System.out.printf("[ids] %s, %s, after reload: %s%n", a.accountId, b.accountId, c.accountId);
        check("ids stay unique after reload", !c.accountId.equals(a.accountId) && !c.accountId.equals(b.accountId)
            && c.accountId.endsWith("-3"));
    }

    static void scenarioDefaultSeizesInvestments() {
        reset(200_000f);
        BankData data = BankData.get();
        data.setAutopayEnabled(false);
        BankAccount inv = data.getInvestmentManager().invest(InvestmentType.BONDS, 50_000f);
        BankAccount loan = data.getLoanManager().takeLoan(LoanType.SMALL, 150_000f, 0.05f);
        credits.set(0f);
        int day = 0;
        while (loan.status != LoanStatus.DEFAULTED && loan.isOpenLoan() && day < 300) {
            advanceDays(1); day++; if (day % 30 == 0) monthEnd();
            if (loan.status == LoanStatus.DEFAULTED || inv.currentValue < 1f) break;
        }
        System.out.printf("[seize] after %d days: investment value %.0f (was ~50k+), loan %s balance %.0f%n",
            day, inv.currentValue, loan.status, loan.remainingBalance);
        check("default seized the investment held at the bank", inv.currentValue < 1f);
        check("seized value paid down the loan", loan.remainingBalance < 150_000f * 1.2f);
    }

    static void scenarioSeizureCoversWholeLoan() {
        reset(1_000_000f);
        BankData data = BankData.get();
        data.setAutopayEnabled(false);
        BankAccount inv = data.getInvestmentManager().invest(InvestmentType.SAVINGS, 500_000f);
        BankAccount loan = data.getLoanManager().takeLoan(LoanType.EMERGENCY, 30_000f, 0.08f);
        credits.set(0f);
        int day = 0;
        while (loan.isOpenLoan() && day < 300) { advanceDays(1); day++; if (day % 30 == 0) monthEnd(); }
        System.out.printf("[seize all] loan %s after %d days, investment left %.0f, score %d%n",
            loan.status, day, inv.currentValue, data.getCreditScoreManager().getScore());
        check("loan closed by seizure is marked SEIZED (not PAID_OFF)", loan.status == LoanStatus.SEIZED);
        check("only what was owed was taken", inv.currentValue > 400_000f);
    }

    static void scenarioTranslations() {
        reset(100_000f);
        BankData data = BankData.get();
        BankAccount loan = data.getLoanManager().takeLoan(LoanType.SMALL, 50_000f, 0.05f);
        Str.load("pt_BR");
        String pt = loan.getStatusDisplay() + " | " + LoanType.SMALL.getDisplayName() + " | " + data.getCreditScoreManager().getBracket();
        Str.load("en");
        String en = loan.getStatusDisplay() + " | " + LoanType.SMALL.getDisplayName() + " | " + data.getCreditScoreManager().getBracket();
        System.out.println("[i18n] pt_BR: " + pt + "   en: " + en);
        check("pt_BR table loads and translates", pt.startsWith("Em dia | Crédito para Pequenos Negócios"));
        check("en table loads", en.startsWith("Current | Small Business Loan"));
        check("unknown language falls back to English", Str.f("status.overdue", 5).equals("OVERDUE (5 days)"));
        Str.load("xx");
        check("unsupported language resolves to en", "en".equals(Str.language()));
    }

    // ------------------------------------------------------------------ helpers

    static void reset(float startCredits) {
        persistent.clear();
        credits.set(startCredits);
        now = 1_000_000_000L;
    }

    static final BankCampaignScript daily = new BankCampaignScript();
    static final BankEconomyListener econ = new BankEconomyListener();

    static void advanceDays(int days) {
        daily.advance(0f); // initialise day stamp
        for (int i = 0; i < days; i++) {
            now += DAY;
            daily.advance(0f);
        }
    }

    /** Last economy tick of the month, then the vanilla CoreScript settlement. */
    static void monthEnd() {
        econ.reportEconomyTick(4);
        MonthlyReport r = SharedData.getData().getCurrentReport();
        r.computeTotals();
        credits.set(credits.get() + r.getRoot().totalIncome - r.getRoot().totalUpkeep);
        SharedData.getData().rollOverReport();
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "  PASS " : "  FAIL ") + what);
        if (!ok) failures++;
    }

    // ------------------------------------------------------------------ deep stubs

    @SuppressWarnings("unchecked")
    static <T> T stub(Class<T> type) {
        return (T) Proxy.newProxyInstance(BankSim.class.getClassLoader(), new Class<?>[]{type}, new InvocationHandler() {
            public Object invoke(Object proxy, Method m, Object[] a) throws Throwable {
                String n = m.getName();
                if (n.equals("getPersistentData")) return persistent;
                if (n.equals("getCredits")) return credits;
                if (n.equals("getTimestamp")) return now;
                if (n.equals("getElapsedDaysSince")) return (float) ((now - (Long) a[0]) / (double) DAY);
                if (n.equals("getInt") && a != null && "economyIterPerMonth".equals(a[0])) return 5;
                if (n.equals("getMergedJSONForMod")) throw new java.io.IOException("off-game");
                if (n.equals("loadJSON") && a != null && a.length > 0 && String.valueOf(a[0]).endsWith(".json")) {
                    java.nio.file.Path pth = java.nio.file.Paths.get(System.getProperty("bos.mod", "."), String.valueOf(a[0]));
                    if (!java.nio.file.Files.exists(pth)) throw new java.io.IOException("missing " + pth);
                    return new org.json.JSONObject(new String(java.nio.file.Files.readAllBytes(pth), "UTF-8"));
                }
                if (n.equals("isModEnabled")) return false;
                if (n.equals("addMessage")) { messages.add(String.valueOf(a[0])); return null; }
                if (n.equals("toString")) return "stub:" + type.getSimpleName();
                if (n.equals("hashCode")) return System.identityHashCode(proxy);
                if (n.equals("equals")) return proxy == a[0];
                return defaultFor(m.getReturnType());
            }
        });
    }

    static Object defaultFor(Class<?> r) {
        if (r == void.class) return null;
        if (r == boolean.class) return false;
        if (r == int.class) return 0;
        if (r == long.class) return 0L;
        if (r == float.class) return 0f;
        if (r == double.class) return 0d;
        if (List.class.isAssignableFrom(r) || r == Collection.class) return new ArrayList<Object>();
        if (Set.class.isAssignableFrom(r)) return new HashSet<Object>();
        if (Map.class.isAssignableFrom(r)) return new HashMap<Object, Object>();
        if (r.isInterface()) return stub(r);
        return null;
    }
}
