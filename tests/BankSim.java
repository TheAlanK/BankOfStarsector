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
        scenarioThinFile();
        scenarioHistoryBuildsScore();
        scenarioInstantRepayExploitBlocked();
        scenarioLatePaymentHurtsAndFades();
        scenarioRateShopping();
        scenarioCreditBuilder();
        scenarioCreditBuilderDefault();
        scenarioCreditBuilderEligibility();
        scenarioCreditLineEligibility();
        scenarioCreditLineUtilization();
        scenarioCreditLineGracePeriod();
        scenarioCreditLineMissedMinimum();
        scenarioCreditLineLimitIncrease();
        scenarioCreditLineChurnAndClose();

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
        advanceDays(30); monthEnd();          // next reporting cycle: the lender reports the charge-off
        CreditBureau.Result rep = data.getCreditScoreManager().getReport();
        System.out.printf("[never pays] report: tradelines %d, late30 %d late60 %d late90 %d chargeoffs %d%n", data.getCreditScoreManager().getBureau().getTradelines().size(), rep.late30, rep.late60, rep.late90, rep.chargeOffs);
        check("delinquencies and charge-off are on the credit report", rep.chargeOffs >= 1 && rep.late30 + rep.late60 + rep.late90 >= 1);
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

    // ------------------------------------------------------------------ credit bureau scenarios

    // ------------------------------------------------------------------ credit-builder loan (0.3.0)

    static void scenarioCreditBuilder() {
        reset(100_000f);
        BankData data = BankData.get();
        LoanManager lm = data.getLoanManager();
        check("[builder] offered to a thin file", lm.whyNot(LoanType.BUILDER, data) == null);
        BankAccount b = lm.takeLoan(LoanType.BUILDER, 30_000f,
            data.getInterestEngine().calculateEffectiveLoanRate(LoanType.BUILDER, data.getCreditScoreManager().getScore()));
        check("[builder] money is held at the bank, not paid out",
            Math.abs(credits.get() - 100_000f) < 1f && Math.abs(b.heldFunds - 30_000f) < 1f);
        check("[builder] net worth unchanged: the held money is still the player's", Math.abs(data.getNetWorth() - 100_000f) < 1f);
        check("[builder] fixed rate and configured term",
            Math.abs(b.monthlyRate - BankSettings.BUILDER_RATE) < 1e-6 && b.termMonths == BankSettings.BUILDER_TERM_MONTHS);
        check("[builder] does not count toward the loan limit", lm.whyNot(LoanType.EMERGENCY, data) == null);
        check("[builder] only one at a time", "terminal.loans.reasonBuilderOpen".equals(lm.whyNot(LoanType.BUILDER, data)));

        months(6);
        Integer s6 = score();
        float before = credits.get();
        float balance = b.remainingBalance;
        boolean paid = lm.payOff(b.accountId);
        float after = credits.get();
        boolean again = lm.payOff(b.accountId);
        System.out.printf("[builder] score after 6 months: %s | payoff of %.0f released %.0f (credits %.0f -> %.0f) | status %s%n",
            s6, balance, after - before + balance, before, after, b.status);
        check("[builder] six months of history make the file scoreable", s6 != null);
        check("[builder] payoff releases the held money", paid && b.status == LoanStatus.PAID_OFF
            && Math.abs(after - (before - balance + 30_000f)) < 1f && b.heldFunds == 0f);
        check("[builder] released only once", !again && Math.abs(credits.get() - after) < 1f);
    }

    static void scenarioCreditBuilderDefault() {
        reset(0f);
        BankData data = BankData.get();
        LoanManager lm = data.getLoanManager();
        data.setAutopayEnabled(false);
        BankAccount b = lm.takeLoan(LoanType.BUILDER, 30_000f, BankSettings.BUILDER_RATE);
        credits.set(0f);
        int day = 0;
        while (b.heldFunds > 0f && day < 240) { advanceDays(1); day++; if (day % 30 == 0) monthEnd(); }
        advanceDays(30); monthEnd(); // next reporting cycle
        CreditBureau.Result rep = data.getCreditScoreManager().getReport();
        System.out.printf("[builder default] after %d days: status %s, balance left %.0f, held %.0f, fleet %s, charge-offs %d, repossessions %d%n",
            day, b.status, b.remainingBalance, b.heldFunds, data.getCollectionManager().hasActiveCollection(b.accountId),
            rep.chargeOffs, rep.repossessions);
        check("[builder default] the deposit pays the debt", b.heldFunds == 0f && b.remainingBalance < 30_000f * 0.1f);
        check("[builder default] no Collection Fleet is sent", !data.getCollectionManager().hasActiveCollection(b.accountId));
        check("[builder default] reported as a charge-off, not a repossession", rep.chargeOffs >= 1 && rep.repossessions == 0);
    }

    static void scenarioCreditBuilderEligibility() {
        // A good score doesn't need it.
        reset(10_000_000f);
        BankData data = BankData.get();
        data.getLoanManager().takeLoan(LoanType.MEGACORP, 900_000f, 0.035f);
        months(8);
        Integer good = score();
        String whyGood = data.getLoanManager().whyNot(LoanType.BUILDER, data);

        // After bankruptcy it is the only loan offered.
        reset(0f);
        data = BankData.get();
        data.setAutopayEnabled(false);
        data.getLoanManager().takeLoan(LoanType.EMERGENCY, 40_000f, 0.08f);
        credits.set(0f);
        int day = 0;
        while (!data.getBankruptcyManager().canFileBankruptcy(data) && day < 240) { advanceDays(1); day++; if (day % 30 == 0) monthEnd(); }
        data.getBankruptcyManager().fileBankruptcy(data);
        String whyBuilder = data.getLoanManager().whyNot(LoanType.BUILDER, data);
        String whySmall = data.getLoanManager().whyNot(LoanType.SMALL, data);
        System.out.printf("[builder eligibility] score %s -> %s | after bankruptcy: builder %s, small %s%n",
            good, whyGood, whyBuilder, whySmall);
        check("[builder eligibility] not offered once the score is good",
            good != null && good >= BankSettings.BUILDER_MAX_SCORE && "terminal.loans.reasonBuilderScore".equals(whyGood));
        check("[builder eligibility] offered during the bankruptcy lockout, unlike other loans",
            whyBuilder == null && "terminal.loans.reasonBankruptcy".equals(whySmall));
    }

    // ------------------------------------------------------------------ revolving credit line (0.3.0)

    /** A file with ~8 months of on-time history and a good score (650-749). */
    static BankData scoredFile(float cash) {
        reset(cash);
        BankData data = BankData.get();
        data.getLoanManager().takeLoan(LoanType.MEGACORP, 900_000f, 0.035f);
        months(8);
        return data;
    }

    static void scenarioCreditLineEligibility() {
        reset(1_000_000f);
        BankData data = BankData.get();
        String thin = data.getLoanManager().whyNot(LoanType.CREDIT_LINE, data);
        data = scoredFile(10_000_000f);
        LoanManager lm = data.getLoanManager();
        int inq = data.getCreditScoreManager().getReport().inquiries12;
        Integer score = score();
        BankAccount line = lm.openCreditLine(data);
        String second = lm.whyNot(LoanType.CREDIT_LINE, data);
        System.out.printf("[line] thin file: %s | score %s -> limit %.0f, rate %.2f%%/mo | second line: %s%n",
            thin, score, line == null ? 0f : line.creditLimit, line == null ? 0f : line.monthlyRate * 100, second);
        check("[line] not offered to a thin file", "terminal.line.reasonScore".equals(thin));
        check("[line] good score gets the good-bracket limit", line != null && Math.abs(line.creditLimit - BankSettings.LINE_LIMIT_GOOD) < 1f);
        check("[line] opening is a hard inquiry", data.getCreditScoreManager().getReport().inquiries12 == inq + 1);
        check("[line] only one credit line", "terminal.line.reasonOpen".equals(second));
        check("[line] does not count toward the loan limit", lm.getLimitedLoanCount() == 1);
    }

    static Integer lineScoreAt(float utilization, float payDownTo) {
        BankData data = scoredFile(10_000_000f);
        LoanManager lm = data.getLoanManager();
        BankAccount line = lm.openCreditLine(data);
        line.autopayFull = false;
        lm.drawCreditLine(line.accountId, line.creditLimit * utilization);
        months(2);
        if (payDownTo >= 0f) {
            lm.makePayment(line.accountId, Math.max(0f, line.remainingBalance - line.creditLimit * payDownTo));
            months(1);
        }
        return score();
    }

    static void scenarioCreditLineUtilization() {
        Integer low = lineScoreAt(0.10f, -1f);
        Integer high = lineScoreAt(0.90f, -1f);
        Integer lowLater = lineScoreAt(0.10f, 0.10f);
        Integer recovered = lineScoreAt(0.90f, 0.10f);
        CreditBureau.Result r = BankData.get().getCreditScoreManager().getReport();
        System.out.printf("[line utilization] 10%%: %s | 90%%: %s | a month after paying 90%% down to 10%%: %s (10%% throughout: %s), utilization now %.0f%%%n",
            low, high, recovered, lowLater, r.utilization * 100);
        check("[line utilization] high utilization costs many points", low != null && high != null && low - high >= 40);
        check("[line utilization] paying it down recovers at the next report (no memory)",
            recovered != null && lowLater != null && Math.abs(lowLater - recovered) <= 10);
    }

    static void scenarioCreditLineGracePeriod() {
        // Full statement paid every month: never any interest.
        BankData data = scoredFile(10_000_000f);
        LoanManager lm = data.getLoanManager();
        BankAccount line = lm.openCreditLine(data);
        float interestFull = 0f;
        for (int m = 0; m < 3; m++) {
            lm.drawCreditLine(line.accountId, 50_000f);
            months(1);
            interestFull += line.lastInterest;
        }
        // Only the minimum paid: interest from the second statement on.
        data = scoredFile(10_000_000f);
        lm = data.getLoanManager();
        BankAccount carried = lm.openCreditLine(data);
        carried.autopayFull = false;
        lm.drawCreditLine(carried.accountId, 50_000f);
        months(1);
        float first = carried.lastInterest;
        months(1);
        float second = carried.lastInterest;
        System.out.printf("[line grace] statement paid in full x3: interest %.0f | minimum only: first statement %.0f, second %.0f (balance %.0f)%n",
            interestFull, first, second, carried.remainingBalance);
        check("[line grace] paying the statement in full charges no interest", interestFull < 1f && line.remainingBalance < 1f);
        check("[line grace] carrying a balance charges interest", first < 1f && second > 500f);
    }

    static void scenarioCreditLineMissedMinimum() {
        BankData data = scoredFile(10_000_000f);
        LoanManager lm = data.getLoanManager();
        BankAccount line = lm.openCreditLine(data);
        float limit0 = line.creditLimit;
        data.setAutopayEnabled(false);
        // Keep the installment loan current by hand; leave the credit line unpaid.
        lm.drawCreditLine(line.accountId, 100_000f);
        credits.set(0f);
        for (int m = 0; m < 3; m++) {
            advanceDays(30);
            monthEnd();
            credits.set(10_000_000f);
            for (BankAccount loan : lm.getActiveLoans()) if (!loan.loanType.isRevolving() && loan.amountPastDue > 0f) lm.payPastDue(loan.accountId);
            credits.set(0f);
        }
        CreditBureau.Result r = data.getCreditScoreManager().getReport();
        credits.set(1_000_000f);
        boolean drew = lm.drawCreditLine(line.accountId, 10_000f);
        System.out.printf("[line missed] status %s, days overdue %d, late30 %d, restricted %s, limit %.0f -> %.0f, draw allowed: %s%n",
            line.status, line.daysOverdue, r.late30, data.getCollectionManager().isBankingRestricted(), limit0, line.creditLimit, drew);
        check("[line missed] an unpaid minimum is reported 30 days late", r.late30 >= 1);
        check("[line missed] collection starts (banking restricted)", data.getCollectionManager().isBankingRestricted());
        check("[line missed] no draws while late", !drew);
        check("[line missed] limit cut after the late payment, not below the balance",
            line.creditLimit < limit0 && line.creditLimit >= line.remainingBalance - 1f);
    }

    static void scenarioCreditLineLimitIncrease() {
        BankData data = scoredFile(10_000_000f);
        LoanManager lm = data.getLoanManager();
        BankAccount line = lm.openCreditLine(data);
        float limit0 = line.creditLimit;
        for (int m = 0; m < BankSettings.LINE_INCREASE_MONTHS; m++) {
            lm.drawCreditLine(line.accountId, 20_000f);
            months(1);
        }
        System.out.printf("[line increase] after %d on-time statements: limit %.0f -> %.0f%n",
            BankSettings.LINE_INCREASE_MONTHS, limit0, line.creditLimit);
        check("[line increase] limit raised after on-time statements",
            Math.abs(line.creditLimit - limit0 * (1f + BankSettings.LINE_INCREASE_PCT)) < 1f);
    }

    static void scenarioCreditLineChurnAndClose() {
        BankData data = scoredFile(10_000_000f);
        LoanManager lm = data.getLoanManager();
        BankAccount line = lm.openCreditLine(data);
        months(1);
        CreditBureau.Tradeline t = data.getCreditScoreManager().getBureau().find(line.accountId);
        int onTime0 = t.monthsOnTime;
        int inq0 = data.getCreditScoreManager().getReport().inquiries12;
        for (int i = 0; i < 10; i++) {
            lm.drawCreditLine(line.accountId, line.getAvailableCredit());
            lm.makePayment(line.accountId, line.remainingBalance);
        }
        months(1);
        int onTime1 = t.monthsOnTime;
        boolean closedWithBalance;
        lm.drawCreditLine(line.accountId, 10_000f);
        closedWithBalance = lm.closeCreditLine(line.accountId);
        lm.makePayment(line.accountId, line.remainingBalance);
        boolean closed = lm.closeCreditLine(line.accountId);
        months(1);
        System.out.printf("[line churn] 10 draw/repay cycles: on-time months %d -> %d, inquiries %d -> %d | close with balance: %s, close at zero: %s, tradeline open: %s%n",
            onTime0, onTime1, inq0, data.getCreditScoreManager().getReport().inquiries12, closedWithBalance, closed, t.isOpen());
        check("[line churn] many draws and repayments add one month of history, like any month", onTime1 == onTime0 + 1);
        check("[line churn] draws are not inquiries", data.getCreditScoreManager().getReport().inquiries12 == inq0);
        check("[line close] a line with a balance can't be closed", !closedWithBalance);
        check("[line close] closing at zero closes the account on the report", closed && !t.isOpen());
    }

    static Integer score() {
        return BankData.get().getCreditScoreManager().getReport().score;
    }

    static void months(int n) {
        for (int i = 0; i < n; i++) { advanceDays(30); monthEnd(); }
    }

    static void scenarioThinFile() {
        reset(5_000_000f);
        BankData data = BankData.get();
        check("new player has no score (thin file)", score() == null);
        data.getLoanManager().takeLoan(LoanType.SMALL, 150_000f, 0.05f);
        months(3);
        Integer at3 = score();
        months(3);
        Integer at6 = score();
        System.out.printf("[thin file] score after 3 months: %s, after 6 months: %s%n", at3, at6);
        check("still unscoreable with a 3-month-old account", at3 == null);
        check("scoreable once an account is 6 months old", at6 != null);
    }

    static void scenarioHistoryBuildsScore() {
        reset(5_000_000f);
        BankData data = BankData.get();
        data.getLoanManager().takeLoan(LoanType.MEGACORP, 900_000f, 0.035f); // 24-month term, autopay
        months(7);
        Integer early = score();
        months(17);
        Integer later = score();
        CreditBureau.Result r = data.getCreditScoreManager().getReport();
        System.out.printf("[history] score at 7 months: %s, at 24 months: %s (on-time months %d, reasons %s)%n",
            early, later, r.monthsOnTime, r.reasons);
        check("months of on-time payments raise the score", early != null && later != null && later > early + 20);
    }

    /** The reported exploit: save money, take loans and repay them at once to farm score. */
    static void scenarioInstantRepayExploitBlocked() {
        // Control: one loan, kept and paid on time.
        reset(10_000_000f);
        BankData data = BankData.get();
        data.getLoanManager().takeLoan(LoanType.MEGACORP, 900_000f, 0.035f);
        months(8);
        Integer control = score();

        // Same history, plus 4 loans taken and repaid immediately during month 8.
        reset(10_000_000f);
        data = BankData.get();
        data.getLoanManager().takeLoan(LoanType.MEGACORP, 900_000f, 0.035f);
        months(7);
        Integer before = score();
        int onTimeBefore = data.getCreditScoreManager().getReport().monthsOnTime;
        LoanType[] types = {LoanType.EMERGENCY, LoanType.CORPORATE, LoanType.MEGACORP, LoanType.SOVEREIGN};
        for (LoanType t : types) {
            BankAccount quick = data.getLoanManager().takeLoan(t, 100_000f, 0.05f);
            data.getLoanManager().payOff(quick.accountId);
        }
        months(1);
        Integer after = score();
        CreditBureau.Result r = data.getCreditScoreManager().getReport();
        System.out.printf("[exploit] control at month 8: %s | farmer before: %s, after 4 instant loans: %s (inquiries %d, opened %d, on-time months %d -> %d)%n",
            control, before, after, r.inquiries12, r.accountsOpened12, onTimeBefore, r.monthsOnTime);
        check("instant take-and-repay does not raise the score", after != null && control != null && after <= control);
        check("instant loans add no on-time history", r.monthsOnTime == onTimeBefore + 1);
        check("each application left a hard inquiry", r.inquiries12 >= 4);
    }

    static void scenarioLatePaymentHurtsAndFades() {
        // Clean baseline
        reset(5_000_000f);
        BankData data = BankData.get();
        data.getLoanManager().takeLoan(LoanType.SOVEREIGN, 2_000_000f, 0.03f);
        months(11);                // same horizon as the late run below (6+1+1+1+2)
        Integer clean10 = score();
        months(20);
        Integer clean36 = score();

        // Same loan, one payment left 30+ days late around month 7-8, then current again.
        reset(5_000_000f);
        data = BankData.get();
        BankAccount loan = data.getLoanManager().takeLoan(LoanType.SOVEREIGN, 2_000_000f, 0.03f);
        months(6);
        data.setAutopayEnabled(false);
        float cash = credits.get();
        credits.set(0f);
        months(1);                 // bill issued, not paid
        advanceDays(30); monthEnd(); // missed due date: OVERDUE, but under 30 days -> not reported
        advanceDays(30); monthEnd(); // still unpaid 30+ days past due: reported as a 30-day late
        credits.set(cash);
        data.getLoanManager().payPastDue(loan.accountId);
        data.setAutopayEnabled(true);
        months(2);
        Integer late10 = score();
        months(20);
        Integer late36 = score();
        System.out.printf("[late] clean: %s -> %s | one 30-day late: %s -> %s%n", clean10, clean36, late10, late36);
        check("a reported 30-day late payment costs a lot of points", clean10 != null && late10 != null && clean10 - late10 >= 30);
        check("the damage fades with time but does not vanish", late36 != null && clean36 != null
            && (clean36 - late36) < (clean10 - late10) && late36 < clean36);
    }

    static void dumpReport(String tag) {
        CreditBureau.Result r = BankData.get().getCreditScoreManager().getReport();
        System.out.printf("  [%s] score %s pay %.0f amt %.0f len %.0f new %.0f mix %.0f | ontime %d late30 %d open %d closed %d ratio %.2f oldest %.1f avg %.1f inq %d%n", tag, r.score, r.payment, r.amounts, r.length, r.newCredit, r.mix, r.monthsOnTime, r.late30, r.openAccounts, r.closedAccounts, r.balanceRatio, r.oldestMonths, r.averageMonths, r.inquiries12);
    }

    static void scenarioRateShopping() {
        reset(5_000_000f);
        BankData data = BankData.get();
        data.getLoanManager().takeLoan(LoanType.EMERGENCY, 20_000f, 0.08f);
        advanceDays(10);
        data.getLoanManager().takeLoan(LoanType.SMALL, 50_000f, 0.05f);      // same category, inside 45 days
        advanceDays(10);
        data.getLoanManager().takeLoan(LoanType.CORPORATE, 200_000f, 0.04f); // different category
        int inq = data.getCreditScoreManager().getReport().inquiries12;
        System.out.printf("[rate shopping] 3 applications -> %d counted inquiries%n", inq);
        check("same-type applications within 45 days count as one inquiry", inq == 2);
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
