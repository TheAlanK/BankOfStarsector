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
        scenarioQuoteMatchesBilling();
        scenarioQuoteTextFormats();
        scenarioScheduleMatchesBilling();
        scenarioScoreChartExplainsChange();
        scenarioColonyAppraisal();
        scenarioSecuredLoanSigning();
        scenarioReceivership();
        scenarioForeclosureAndAuction();
        scenarioCollateralLoss();
        scenarioInsurancePremium();
        scenarioInsuranceClaims();
        scenarioInsuranceLapseAndCap();
        scenarioSectorScansAreCached();
        scenarioMoneyFlowsCalm();
        scenarioSovereignDebtCycle();
        scenarioLegacyDebtMigration();
        scenarioRelationshipBanking();
        scenarioRelationshipFromBehaviour();
        scenarioModdedFactions();
        scenarioWarSurchargeScales();
        scenarioOtherInsurance();
        scenarioRecoveredHull();
        scenarioDevelopmentBuiltAndRepaid();
        scenarioDevelopmentDemandPaid();
        scenarioDevelopmentCollectionFullForce();
        scenarioDevelopmentMinimalForce();
        scenarioDevelopmentNpcDeals();
        scenarioDevelopmentPlayerInvests();

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

    // ------------------------------------------------------------------ quotes before signing (0.3.0)

    static void scenarioQuoteMatchesBilling() {
        Object[][] cases = {
            {LoanType.EMERGENCY, 40_000f, 0.08f},
            {LoanType.CORPORATE, 400_000f, 0.06f},
            {LoanType.BUILDER, 30_000f, BankSettings.BUILDER_RATE},
        };
        for (Object[] c : cases) {
            LoanType type = (LoanType) c[0];
            float amount = (Float) c[1], rate = (Float) c[2];
            LoanSchedule quote = LoanSchedule.project(amount, rate, type.getTermMonths());
            reset(5_000_000f);
            BankAccount loan = BankData.get().getLoanManager().takeLoan(type, amount, rate);
            float start = credits.get();
            float first = -1f;
            int months = 0;
            while (loan.status != LoanStatus.PAID_OFF && months < 60) {
                float before = credits.get();
                advanceDays(30);
                monthEnd();
                if (first < 0f) first = before - credits.get();
                months++;
            }
            // A credit-builder payoff releases the held amount back to the player.
            float paid = start - credits.get() + (type.isBuilder() ? amount : 0f);
            System.out.printf("[quote] %s %.0f: quoted first %.0f, total %.0f, %d months | billed first %.0f, total %.0f, %d months%n",
                type.name(), amount, quote.firstPayment, quote.totalPaid, quote.rows.size(), first, paid, months);
            check("[quote] " + type.name() + ": quoted installment and total match what is billed",
                Math.abs(quote.firstPayment - first) < 1f && Math.abs(quote.totalPaid - paid) < 2f && quote.rows.size() == months);
        }
    }

    static void scenarioQuoteTextFormats() {
        int bad = 0, lines = 0;
        for (String lang : new String[]{"en", "pt_BR"}) {
            Str.load(lang);
            List<com.bankofstarsector.ui.Quote.Line> all = new ArrayList<com.bankofstarsector.ui.Quote.Line>();
            for (LoanType t : LoanType.values()) if (!t.isRevolving()) all.addAll(com.bankofstarsector.ui.Quote.loan(t, 50_000f, 0.05f));
            for (InvestmentType t : InvestmentType.values()) all.addAll(com.bankofstarsector.ui.Quote.investment(t, 100_000f));
            all.addAll(com.bankofstarsector.ui.Quote.securedLoan("Alpha", 3_000_000f, 1_000_000f, 0.02f));
            for (com.bankofstarsector.ui.Quote.Line l : all) {
                lines++;
                String format = Str.get(l.key);
                int slots = format.split("%s", -1).length - 1;
                String stray = format.replace("%s", "");
                if (slots != l.args.length || stray.contains("%")) {
                    System.out.println("  bad quote line (" + lang + "): " + format + " with " + l.args.length + " args");
                    bad++;
                }
            }
        }
        Str.load("en");
        System.out.printf("[quote text] %d lines checked in en and pt_BR, %d bad%n", lines, bad);
        check("[quote text] every quote line has one argument per %s and no literal percent sign", bad == 0);
    }

    // ------------------------------------------------------------------ amortization table and score chart (0.3.0)

    static void scenarioScheduleMatchesBilling() {
        reset(5_000_000f);
        BankAccount loan = BankData.get().getLoanManager().takeLoan(LoanType.CORPORATE, 400_000f, 0.06f);
        months(4);
        LoanSchedule rest = LoanSchedule.remaining(loan);
        float start = credits.get();
        int months = 0;
        while (loan.status != LoanStatus.PAID_OFF && months < 60) { months(1); months++; }
        float paid = start - credits.get();
        System.out.printf("[schedule] after 4 months: projected %d installments, %.0f to pay | billed %d, %.0f%n",
            rest.rows.size(), rest.totalPaid, months, paid);
        check("[schedule] the remaining schedule matches what is billed", rest.rows.size() == months && Math.abs(rest.totalPaid - paid) < 2f);
        check("[schedule] interest + principal add up to each payment",
            Math.abs(rest.rows.get(0).interest + rest.rows.get(0).principal - rest.rows.get(0).payment) < 0.01f);
    }

    static void scenarioScoreChartExplainsChange() {
        reset(5_000_000f);
        BankData data = BankData.get();
        data.getLoanManager().takeLoan(LoanType.MEGACORP, 900_000f, 0.035f);
        months(3);
        String thin = data.getCreditScoreManager().getScoreChange(0);
        data = scoredFile(10_000_000f);
        LoanManager lm = data.getLoanManager();
        BankAccount line = lm.openCreditLine(data);
        line.autopayFull = false;
        months(2);
        lm.drawCreditLine(line.accountId, line.creditLimit * 0.9f);
        months(1);
        CreditScoreManager csm = data.getCreditScoreManager();
        String change = csm.getScoreChange(0);
        List<Integer> h = csm.getScoreHistory();
        System.out.printf("[score chart] thin month: '%s' | after maxing the line: %s -> %s, main factor %s%n",
            thin, h.get(1), h.get(0), change);
        check("[score chart] no explanation while there is no score", thin.isEmpty());
        check("[score chart] maxing out the credit line is explained by amounts owed",
            change.startsWith("factor.amounts:-") && h.get(0) < h.get(1));
    }

    // ------------------------------------------------------------------ fleet insurance (0.3.0)

    static final List<Object> fleetMembers = new ArrayList<Object>();
    static int marketScans = 0;

    /** NexusUI captures twice a second and the terminal prices every loan type: the sector scans must not repeat. */
    static void scenarioSectorScansAreCached() {
        BankData data = scoredFile(1_000_000f);
        for (int i = 0; i < 300; i++) colony("m" + i, i % 3 == 0 ? "hegemony" : i % 3 == 1 ? "tritachyon" : "persean", 5, 0f);
        ship("a", 500_000f);
        data.getInsuranceManager().buy(InsurancePlan.STANDARD, data);
        advanceDays(1);
        com.bankofstarsector.ui.BankSnapshot.capture(); // first capture of the day computes the numbers
        int before = marketScans;
        long t0 = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            com.bankofstarsector.ui.BankSnapshot.capture();
            for (LoanType t : LoanType.values()) data.getInterestEngine().calculateEffectiveLoanRate(t, 650);
        }
        long sameDay = System.nanoTime() - t0;
        int scansSameDay = marketScans - before;
        advanceDays(1);
        before = marketScans;
        com.bankofstarsector.ui.BankSnapshot.capture();
        int scansNextDay = marketScans - before;
        System.out.printf("[performance] 300 markets: 100 snapshots + 800 loan quotes in one day -> %d market scans, %.1f ms total | next day: %d scans%n",
            scansSameDay, sameDay / 1e6, scansNextDay);
        check("[performance] sector-wide numbers are not recomputed within a game day", scansSameDay == 0);
        check("[performance] they are refreshed the next day", scansNextDay >= 1);
    }

    static com.fs.starfarer.api.fleet.FleetMemberAPI ship(final String id, final float baseValue) {
        Object m = Proxy.newProxyInstance(BankSim.class.getClassLoader(),
            new Class<?>[]{com.fs.starfarer.api.fleet.FleetMemberAPI.class}, new InvocationHandler() {
                public Object invoke(Object proxy, Method mt, Object[] a) {
                    String n = mt.getName();
                    if (n.equals("getId") || n.equals("getShipName")) return id;
                    if (n.equals("getBaseValue")) return baseValue;
                    if (n.equals("hashCode")) return id.hashCode();
                    if (n.equals("equals")) return proxy == a[0];
                    if (n.equals("toString")) return "ship:" + id;
                    return defaultFor(mt.getReturnType());
                }
            });
        fleetMembers.add(m);
        return (com.fs.starfarer.api.fleet.FleetMemberAPI) m;
    }

    static void lose(com.fs.starfarer.api.fleet.FleetMemberAPI m, boolean recovered) {
        if (!recovered) fleetMembers.remove(m);
        BankData.get().getInsuranceManager().onShipsLost(java.util.Collections.singletonList(m));
    }

    static void scenarioInsurancePremium() {
        reset(10_000_000f);
        BankData data = BankData.get();
        ship("a", 500_000f);
        ship("b", 1_000_000f);
        InsuranceManager ins = data.getInsuranceManager();
        float thin = ins.premiumFor(InsurancePlan.STANDARD, data);           // no score: credit x1.5
        float comprehensive = ins.premiumFor(InsurancePlan.COMPREHENSIVE, data);
        data = scoredFile(10_000_000f);
        ship("a", 500_000f);
        ship("b", 1_000_000f);
        ins = data.getInsuranceManager();
        float good = ins.premiumFor(InsurancePlan.STANDARD, data);           // 650-749: x1.0
        ship("c", 1_500_000f);
        float bigger = ins.premiumFor(InsurancePlan.STANDARD, data);
        System.out.printf("[insurance premium] 1.5M fleet: no score %.0f, good score %.0f, comprehensive (no score) %.0f | 3M fleet %.0f%n",
            thin, good, comprehensive, bigger);
        check("[insurance premium] 0.8% x 60% x fleet value at a good score", Math.abs(good - 0.008f * 0.6f * 1_500_000f) < 1f);
        check("[insurance premium] worse credit pays more (credit-based insurance score)", Math.abs(thin / good - 1.5f) < 0.01f);
        check("[insurance premium] follows the fleet's value", Math.abs(bigger / good - 2f) < 0.01f);
        check("[insurance premium] comprehensive cover costs more", comprehensive > thin);
    }

    static void scenarioInsuranceClaims() {
        BankData data = scoredFile(10_000_000f);
        InsuranceManager ins = data.getInsuranceManager();
        com.fs.starfarer.api.fleet.FleetMemberAPI a = ship("a", 500_000f);
        com.fs.starfarer.api.fleet.FleetMemberAPI b = ship("b", 1_000_000f);
        com.fs.starfarer.api.fleet.FleetMemberAPI c = ship("c", 800_000f);
        advanceDays(1); // the ships are seen in the fleet
        ins.buy(InsurancePlan.STANDARD, data);

        lose(c, false);                                  // inside the policy's waiting period
        advanceDays(3);
        String early = ins.getClaims().get(0).outcome;

        months(1);                                        // premium paid; waiting period over
        float before = credits.get();
        lose(a, false);
        advanceDays(3);
        float paid = credits.get() - before;

        lose(b, true);                                    // disabled, then recovered after the battle
        advanceDays(3);
        int claimsAfterRecovery = ins.getClaims().size();

        com.fs.starfarer.api.fleet.FleetMemberAPI fresh = ship("fresh", 600_000f);
        advanceDays(5);
        lose(fresh, false);                               // bought 5 days before the loss
        advanceDays(3);
        String newShip = ins.getClaims().get(0).outcome;

        System.out.printf("[insurance claims] loss in waiting period: %s | covered loss of 500k paid %.0f | recovered ship: %d claims | 5-day-old ship: %s | claims factor x%.2f%n",
            early, paid, claimsAfterRecovery, newShip, ins.claimsFactor());
        check("[insurance claims] no claims in the policy's waiting period", "claim.reason.waiting".equals(early));
        check("[insurance claims] a covered loss pays coverage x value - deductible", Math.abs(paid - (0.6f * 500_000f - 10_000f)) < 1f);
        check("[insurance claims] a ship recovered after the battle is not a loss", claimsAfterRecovery == 2);
        check("[insurance claims] ships new to the fleet are not covered yet", "claim.reason.newShip".equals(newShip));
        check("[insurance claims] each paid claim raises the premium", Math.abs(ins.claimsFactor() - 1.25f) < 1e-4);
    }

    static void scenarioInsuranceLapseAndCap() {
        // Monthly cap: three 1M ships, insured value 1.8M, cap 0.9M a month.
        BankData data = scoredFile(10_000_000f);
        InsuranceManager ins = data.getInsuranceManager();
        com.fs.starfarer.api.fleet.FleetMemberAPI x = ship("x", 1_000_000f);
        com.fs.starfarer.api.fleet.FleetMemberAPI y = ship("y", 1_000_000f);
        ship("z", 1_000_000f);
        advanceDays(1);
        ins.buy(InsurancePlan.STANDARD, data);
        months(2);
        float before = credits.get();
        lose(x, false);
        lose(y, false);
        advanceDays(3);
        float paid = credits.get() - before;
        String second = ins.getClaims().get(0).outcome;

        // Lapse: no money at month end.
        data = scoredFile(10_000_000f);
        ins = data.getInsuranceManager();
        com.fs.starfarer.api.fleet.FleetMemberAPI old = ship("old", 500_000f);
        ship("other", 500_000f);
        advanceDays(1);
        ins.buy(InsurancePlan.STANDARD, data);
        months(2);
        credits.set(0f);
        data.setAutopayEnabled(false);
        months(1);
        boolean lapsed = ins.isLapsed();
        lose(old, false);
        advanceDays(3);
        String lapsedClaim = ins.getClaims().get(0).outcome;
        credits.set(0f);
        months(1);
        boolean cancelled = !ins.hasPolicy();
        System.out.printf("[insurance limits] two 1M losses in a month: paid %.0f (second: %s) | unpaid premium: lapsed %s, claim %s, second miss cancels: %s%n",
            paid, second, lapsed, lapsedClaim, cancelled);
        check("[insurance limits] payouts stop at the monthly cap (50% of the insured value)", Math.abs(paid - 900_000f) < 1f
            && "claim.reason.partial".equals(second));
        check("[insurance limits] an unpaid premium suspends the cover", lapsed && "claim.reason.lapsed".equals(lapsedClaim));
        check("[insurance limits] a second unpaid premium cancels the policy", cancelled);
    }

    // ------------------------------------------------------------------ real money flows (0.4.0)

    static float priceLevel = 1f;

    /** A calm sector: three political factions with profitable markets, no wars. */
    static void economySector() {
        for (int i = 0; i < 3; i++) { Colony c = colony("heg" + i, "hegemony", 4, 100_000f); c.grossIncome = 300_000f; }
        for (int i = 0; i < 2; i++) { Colony c = colony("tt" + i, "tritachyon", 5, 80_000f); c.grossIncome = 200_000f; }
        for (int i = 0; i < 2; i++) { Colony c = colony("pl" + i, "persean", 4, 50_000f); c.grossIncome = 150_000f; }
    }

    static Map<InvestmentType, BankAccount> investInEverything(BankData data) {
        Map<InvestmentType, BankAccount> held = new LinkedHashMap<InvestmentType, BankAccount>();
        for (InvestmentType t : InvestmentType.values()) held.put(t, data.getInvestmentManager().invest(t, 1_000_000f));
        return held;
    }

    static void scenarioMoneyFlowsCalm() {
        reset(100_000_000f);
        economySector();
        BankData data = BankData.get();
        months(1); // the first month end sets up the treasuries and the baselines
        Map<InvestmentType, BankAccount> held = investInEverything(data);
        Map<InvestmentType, Float> start = new LinkedHashMap<InvestmentType, Float>();
        for (Map.Entry<InvestmentType, BankAccount> e : held.entrySet()) start.put(e.getKey(), e.getValue().currentValue);
        months(12);
        com.bankofstarsector.economy.PbcLedger ledger = data.getSectorEconomy().getLedger();
        float sumReturns = 0f;
        for (BankAccount a : held.values()) sumReturns += a.lastReturn;
        float coupons = 0f;
        for (com.bankofstarsector.economy.FactionTreasury t : data.getSectorEconomy().getTreasuries().values()) coupons += t.couponsPaid;
        StringBuilder sb = new StringBuilder();
        Map<InvestmentType, Float> annual = new LinkedHashMap<InvestmentType, Float>();
        for (Map.Entry<InvestmentType, BankAccount> e : held.entrySet()) {
            float r = e.getValue().currentValue / start.get(e.getKey()) - 1f;
            annual.put(e.getKey(), r);
            sb.append(String.format("%s %.2f%% ", e.getKey().name(), r * 100));
        }
        System.out.println("[money flows] calm sector, 12 months: " + sb.toString().trim());
        System.out.printf("[money flows] last month: investments returned %.0f; ledger by payer %.0f (treasuries %.0f, PBC %.0f, commodity markets %.0f, companies %.0f, military spending %.0f, fees %.0f) | coupons debited from treasuries %.0f | PBC reserves %.0f%n",
            sumReturns, ledger.playerReturnsThisMonth(), ledger.paidByTreasuries, ledger.paidByPbc, ledger.paidByCommodityMarkets,
            ledger.paidByCompanies, ledger.paidByMilitarySpending, ledger.feesIn, coupons, ledger.reserves);
        check("[money flows] every credit returned has a payer in the ledger", Math.abs(sumReturns - ledger.playerReturnsThisMonth()) < 1f);
        check("[money flows] bond coupons are taken from the issuers' treasuries", Math.abs(coupons - ledger.paidByTreasuries) < 1f && coupons > 0f);
        check("[money flows] savings ~3%: the PBC's deposit rate", Math.abs(annual.get(InvestmentType.SAVINGS) - 0.030f) < 0.002f);
        check("[money flows] bonds ~4.6%: the coupon locked in at purchase", Math.abs(annual.get(InvestmentType.BONDS) - 0.046f) < 0.003f);
        check("[money flows] commodities ~3% with flat prices: the collateral's interest", Math.abs(annual.get(InvestmentType.COMMODITIES) - 0.030f) < 0.002f);
        check("[money flows] venture ~11%: companies' earnings minus the fee", Math.abs(annual.get(InvestmentType.VENTURE) - 0.11f) < 0.01f);
        check("[money flows] military ~8%: military spending minus the fee", Math.abs(annual.get(InvestmentType.MILITARY) - 0.08f) < 0.01f);

        // No borrow-to-invest arbitrage: the best expected return stays under the cheapest cash loan.
        float best = 0f;
        for (InvestmentType t : InvestmentType.values()) best = Math.max(best, data.getInterestEngine().expectedAnnualReturn(t));
        float cheapestLoan = Float.MAX_VALUE;
        for (LoanType t : LoanType.values()) {
            if (t.isBuilder() || t.isDevelopment()) continue; // not cash the player can borrow at the counter
            float monthly = data.getInterestEngine().calculateEffectiveLoanRate(t, 850);
            cheapestLoan = Math.min(cheapestLoan, (float) (Math.pow(1 + monthly, 12) - 1));
        }
        System.out.printf("[money flows] best expected return %.1f%%/yr vs cheapest cash loan %.1f%%/yr%n", best * 100, cheapestLoan * 100);
        check("[money flows] no investment out-earns the cheapest loan", best < cheapestLoan);

        // Commodity futures follow the price index.
        BankAccount commodities = held.get(InvestmentType.COMMODITIES);
        float before = commodities.currentValue;
        priceLevel = 1.10f;
        months(1);
        float jump = commodities.currentValue / before - 1f;
        System.out.printf("[money flows] commodity prices +10%%: commodity futures %+.2f%% that month%n", jump * 100);
        check("[money flows] commodity futures follow the Sector's prices", Math.abs(jump - 0.1025f) < 0.003f);

        // A war raises military spending, and the military fund earns more.
        float calmMilitary = held.get(InvestmentType.MILITARY).lastReturn;
        relations.put(key("hegemony", "tritachyon"), -0.8f);
        months(1);
        float warMilitary = held.get(InvestmentType.MILITARY).lastReturn;
        System.out.printf("[money flows] war: military fund %.0f -> %.0f a month%n", calmMilitary, warMilitary);
        check("[money flows] wars pay military contractors more", warMilitary > calmMilitary * 1.1f);

        // A venture stake whose market is lost is written off.
        BankAccount venture = held.get(InvestmentType.VENTURE);
        int stakes = data.getSectorEconomy().getVentureStakes();
        colonies.get("heg0").inEconomy = false;
        float v0 = venture.currentValue;
        months(1);
        System.out.printf("[money flows] one of %d venture stakes lost: venture %+.1f%% that month (%s)%n", stakes,
            (venture.currentValue / v0 - 1f) * 100, venture.lastSourceKey);
        check("[money flows] a lost market is written off the venture portfolio",
            venture.currentValue < v0 * (1f - 0.8f / stakes) && "flow.ventureLoss".equals(venture.lastSourceKey));
    }

    /** A save from before 0.4.0: the old random simulation left the Hegemony owing far past its ceiling. */
    static void scenarioLegacyDebtMigration() {
        reset(100_000_000f);
        economySector();
        BankData data = BankData.get();
        data.getAssetSeizureManager().simulateFactionDebt("hegemony", 50_000_000f);
        BankAccount bonds = data.getInvestmentManager().invest(InvestmentType.BONDS, 1_000_000f);
        months(1);
        com.bankofstarsector.economy.FactionTreasury t = data.getSectorEconomy().getTreasury("hegemony");
        float debt = data.getAssetSeizureManager().getFactionDebt("hegemony");
        System.out.printf("[sovereign] legacy debt 50M -> %.0f after the first month (annual gross %.0f), defaulted: %s, fund bond %.0f%n",
            debt, 12f * t.gross, t.defaultedTs != 0L, bonds.currentValue);
        check("[sovereign] legacy debt from older saves is restructured, not defaulted",
            t.defaultedTs == 0L && debt <= 0.5f * 12f * t.gross + 1f && bonds.currentValue > 990_000f);
    }

    static void scenarioSovereignDebtCycle() {
        reset(100_000_000f);
        economySector();
        // The League runs a deficit: its markets cost more than they earn.
        for (int i = 0; i < 2; i++) { colonies.get("pl" + i).netIncome = -150_000f; colonies.get("pl" + i).grossIncome = 100_000f; }
        BankData data = BankData.get();
        months(1);
        BankAccount bond = data.getInvestmentManager().invest(InvestmentType.BONDS, 1_000_000f, "persean");
        float coupon = bond.bondYield;
        com.bankofstarsector.economy.FactionTreasury t = data.getSectorEconomy().getTreasury("persean");
        float firstYield = t.yield;
        float peakDebt = 0f, peakYield = 0f;
        int month = 0;
        while (t.defaultedTs == 0L && month < 36) {
            months(1);
            month++;
            peakDebt = Math.max(peakDebt, data.getAssetSeizureManager().getFactionDebt("persean"));
            peakYield = Math.max(peakYield, t.yield);
        }
        System.out.printf("[sovereign] League deficit 300k/mo: bought at %.2f%%, yield %.2f%% -> %.2f%%, debt peaked at %.0f, default after %d months, bond now %.0f (%s), PBC wrote off %.0f in total%n",
            coupon * 100, firstYield * 100, peakYield * 100, peakDebt, month, bond.currentValue, bond.lastSourceKey,
            data.getSectorEconomy().getLedger().totalWriteOffs + data.getSectorEconomy().getLedger().writeOffs);
        check("[sovereign] a faction in deficit borrows from the PBC", peakDebt > 1_000_000f);
        check("[sovereign] its yield rises with its debt", peakYield > firstYield + 0.01f);
        check("[sovereign] past its debt ceiling it defaults", t.defaultedTs != 0L);
        check("[sovereign] bondholders take the haircut", bond.currentValue < 600_000f && "flow.bondsDefault".equals(bond.lastSourceKey));
    }

    // ------------------------------------------------------------------ relationship banking (0.4.0)

    static void scenarioRelationshipBanking() {
        BankData data = scoredFile(10_000_000f);
        CreditScoreManager csm = data.getCreditScoreManager();
        InterestEngine engine = data.getInterestEngine();
        int bureau = csm.getScore();
        relations.put(key("pbc", "player"), 0.6f);
        int friendScore = csm.getUnderwritingScore();
        float friendRate = engine.calculateEffectiveLoanRate(LoanType.MEGACORP, friendScore);
        float friendReturn = engine.expectedAnnualReturn(InvestmentType.BONDS);
        relations.put(key("pbc", "player"), 0f);
        float neutralRate = engine.calculateEffectiveLoanRate(LoanType.MEGACORP, csm.getUnderwritingScore());
        float neutralReturn = engine.expectedAnnualReturn(InvestmentType.BONDS);
        int neutralLoans = csm.getMaxLoans();
        relations.put(key("pbc", "player"), -1f);
        int enemyScore = csm.getUnderwritingScore();
        float enemyRate = engine.calculateEffectiveLoanRate(LoanType.MEGACORP, enemyScore);
        int enemyLoans = csm.getMaxLoans();
        System.out.printf("[relationship] bureau %d | friend (+60): internal %d, megacorp %.3f%%/mo, bonds %.2f%%/yr | neutral: %.3f%%, %.2f%%, %d loans | enemy (-100): internal %d, %.3f%%, %d loans%n",
            bureau, friendScore, friendRate * 100, friendReturn * 100, neutralRate * 100, neutralReturn * 100, neutralLoans,
            enemyScore, enemyRate * 100, enemyLoans);
        check("[relationship] internal score = bureau score + 25 x relation", friendScore == bureau + 15 && enemyScore == bureau - 25);
        check("[relationship] the bureau score itself is unchanged", csm.getScore() == bureau);
        check("[relationship] a friend of the bank pays less, an enemy more", friendRate < neutralRate && neutralRate < enemyRate);
        check("[relationship] the rate discount is small (at most 10% at the best relation)", friendRate >= neutralRate * 0.94f * 0.99f);
        check("[relationship] a good relation raises returns a little", friendReturn > neutralReturn && friendReturn - neutralReturn < 0.004f);
        check("[relationship] a bad relation can cost a bracket (fewer loans)", enemyLoans < neutralLoans);
    }

    static void scenarioRelationshipFromBehaviour() {
        // On-time payments raise the relation, and banking alone stops at the cap.
        reset(10_000_000f);
        BankData data = BankData.get();
        data.getLoanManager().takeLoan(LoanType.MEGACORP, 900_000f, 0.035f);
        months(12);
        float afterYear = relation("pbc", "player");
        relations.put(key("pbc", "player"), 0.74f);
        months(6);
        float capped = relation("pbc", "player");

        // Taking and repaying at once earns nothing.
        reset(10_000_000f);
        data = BankData.get();
        BankAccount instant = data.getLoanManager().takeLoan(LoanType.EMERGENCY, 40_000f, 0.08f);
        data.getLoanManager().payOff(instant.accountId);
        float instantRel = relation("pbc", "player");

        // Late payments and a default cost goodwill.
        reset(0f);
        data = BankData.get();
        data.setAutopayEnabled(false);
        BankAccount loan = data.getLoanManager().takeLoan(LoanType.SMALL, 100_000f, 0.05f);
        credits.set(0f);
        int day = 0;
        while (loan.status != LoanStatus.DEFAULTED && day < 240) { advanceDays(1); day++; if (day % 30 == 0) monthEnd(); }
        float badRel = relation("pbc", "player");
        months(24); // years in default: still not hostile
        float floorRel = relation("pbc", "player");
        System.out.printf("[relationship] 12 months on time: %+.3f | at the cap after 6 more months: %.3f | instant take-and-repay: %+.3f | missed payments + default: %+.3f, after 2 years in default: %+.3f%n",
            afterYear, capped, instantRel, badRel, floorRel);
        check("[relationship] on-time payments raise the relation", afterYear > 0.015f && afterYear < 0.05f);
        check("[relationship] banking alone never raises it past the cap", capped <= 0.75f + 1e-4);
        check("[relationship] taking and repaying a loan at once earns no goodwill", Math.abs(instantRel) < 1e-6);
        check("[relationship] missed payments and a default lower it", badRel <= BankSettings.RELATION_DEFAULT + 2 * BankSettings.RELATION_LATE + 1e-4);
        check("[relationship] payments alone never make the PBC hostile", floorRel > -0.5f && floorRel < badRel);
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

    // ------------------------------------------------------------------ mod compatibility (0.4.0)

    /**
     * The user's sector: the Hiver Swarm holds size 4+ worlds and is hostile to everyone. Older versions put it
     * on the Sovereign Desk, so every faction counted a war with it and it ran up a sovereign debt.
     */
    static void scenarioModdedFactions() {
        reset(100_000_000f);
        economySector();
        for (int i = 0; i < 4; i++) colony("hive" + i, "HIVER", 6, 200_000f);
        pirateLike.add("HIVER");
        for (String other : new String[]{"hegemony", "tritachyon", "persean", "pbc"}) relations.put(key("HIVER", other), -0.75f);
        BankData data = BankData.get();
        InterestEngine engine = data.getInterestEngine();

        BankSettings.POLITICAL_FACTIONS.add("HIVER"); // what older versions did
        months(1);
        int warsBefore = engine.getWarCount();
        float surchargeBefore = engine.getWarSurcharge();
        boolean deskBefore = data.getSectorEconomy().getTreasury("HIVER") != null;
        data.getAssetSeizureManager().simulateFactionDebt("HIVER", 2_000_000f);

        BankSettings.POLITICAL_FACTIONS.clear();       // this version: pirate behaviour in its faction file
        int msgs = messages.size();
        months(1);
        int warsAfter = engine.getWarCount();
        boolean deskAfter = data.getSectorEconomy().getTreasury("HIVER") != null;
        float debtAfter = data.getAssetSeizureManager().getFactionDebt("HIVER");
        boolean announced = false;
        for (String m : messages.subList(msgs, messages.size())) announced |= m.contains("HIVER");
        List<String> majors = new ArrayList<String>();
        for (com.fs.starfarer.api.campaign.FactionAPI f : engine.getMajorFactions()) majors.add(f.getId());

        BankSettings.NON_POLITICAL_FACTIONS.add("persean");
        boolean perseanOff = true;
        for (com.fs.starfarer.api.campaign.FactionAPI f : engine.getMajorFactions()) perseanOff &= !"persean".equals(f.getId());
        BankSettings.NON_POLITICAL_FACTIONS.clear();

        System.out.printf("[modded factions] swarm on the desk: %d wars, surcharge %.0f%%, treasury %s | off the desk: majors %s, %d wars, treasury %s, old debt %.0f, default announced %s%n",
            warsBefore, surchargeBefore * 100, deskBefore, majors, warsAfter, deskAfter, debtAfter, announced);
        check("[modded factions] a faction hostile to everyone inflated the war count", warsBefore == 3 && deskBefore);
        check("[modded factions] pirate behaviour in its faction file keeps it off the Sovereign Desk",
            !majors.contains("HIVER") && majors.size() == 3 && warsAfter == 0 && !deskAfter);
        check("[modded factions] its old sovereign debt is closed quietly, not announced as a default", debtAfter == 0f && !announced);
        check("[modded factions] bos_settings.json can keep any faction off the desk", perseanOff);
    }

    /** States holding a size 4 market each, and wars between the first ones listed in pairs. */
    static float surchargeWith(String[] states, String[][] wars) {
        reset(1_000_000f);
        for (String s : states) colony(s + "_world", s, 4, 50_000f);
        for (String[] w : wars) relations.put(key(w[0], w[1]), -0.8f);
        advanceDays(1);
        return BankData.get().getInterestEngine().getWarSurcharge();
    }

    /** Big faction lists: 15 wars among 13 states must not pin the surcharge at its cap; vanilla-sized sectors are unchanged. */
    static void scenarioWarSurchargeScales() {
        String[] vanilla = {"hegemony", "tritachyon", "persean", "sindrian_diktat", "luddic_church", "independent"};
        String[][] three = {{"hegemony", "tritachyon"}, {"sindrian_diktat", "persean"}, {"luddic_church", "tritachyon"}};
        String[][] five = {{"hegemony", "tritachyon"}, {"sindrian_diktat", "persean"}, {"luddic_church", "tritachyon"},
            {"hegemony", "independent"}, {"persean", "luddic_church"}};
        float vanilla3 = surchargeWith(vanilla, three);
        float vanilla5 = surchargeWith(vanilla, five);
        String[] modded = {"hegemony", "tritachyon", "persean", "sindrian_diktat", "luddic_church", "independent",
            "interstellarimperium", "uaf", "ironshell", "scalartech", "diableavionics", "valkyrian", "MVS"};
        String[][] fifteen = {{"hegemony", "interstellarimperium"}, {"hegemony", "uaf"}, {"hegemony", "valkyrian"}, {"hegemony", "persean"},
            {"hegemony", "diableavionics"}, {"interstellarimperium", "luddic_church"}, {"interstellarimperium", "ironshell"},
            {"interstellarimperium", "diableavionics"}, {"tritachyon", "luddic_church"}, {"luddic_church", "diableavionics"},
            {"uaf", "sindrian_diktat"}, {"persean", "ironshell"}, {"ironshell", "diableavionics"}, {"MVS", "hegemony"}, {"scalartech", "valkyrian"}};
        float modded15 = surchargeWith(modded, fifteen);
        int states = BankData.get().getInterestEngine().getStateCount();
        float small = surchargeWith(new String[]{"hegemony", "tritachyon", "persean"}, new String[][]{{"hegemony", "tritachyon"}});
        System.out.printf("[war surcharge] 6 states: 3 wars %.1f%%, 5 wars %.1f%% | %d states, 15 wars: %.1f%% (was 25%% capped) | 3 states, 1 war: %.1f%%%n",
            vanilla3 * 100, vanilla5 * 100, states, modded15 * 100, small * 100);
        check("[war surcharge] vanilla-sized sectors keep 5% per war and the 25% cap",
            Math.abs(vanilla3 - 0.15f) < 1e-4 && Math.abs(vanilla5 - 0.25f) < 1e-4);
        check("[war surcharge] 15 wars among 13 states count by their share of the 78 pairs (14.4%)",
            states == 13 && Math.abs(modded15 - 15 * 0.05f * 15f / 78f) < 1e-4);
        check("[war surcharge] a small sector is not scaled up", Math.abs(small - 0.05f) < 1e-4);
    }

    static Map<String, Float> otherPolicies = new HashMap<String, Float>(), otherPaid = new HashMap<String, Float>();

    /** Nexerelin's insurer, as the tests see it: per-ship policies, and what it paid after a battle. */
    static void fakeOtherInsurer() {
        otherPolicies.clear();
        otherPaid.clear();
        com.bankofstarsector.compat.OtherInsurance.source = new com.bankofstarsector.compat.OtherInsurance.Source() {
            public float insuredAmount(String id) { Float v = otherPolicies.get(id); return v != null ? v : 0f; }
            public float paidFor(String id, float maxAgeDays) { Float v = otherPaid.get(id); return v != null ? v : 0f; }
        };
    }

    /** The other insurer settles a loss: pays and closes the policy (Nexerelin does it right after the battle). */
    static void otherInsurerPays(String id) {
        Float amount = otherPolicies.remove(id);
        if (amount == null) return;
        otherPaid.put(id, amount);
        credits.set(credits.get() + amount);
    }

    static void scenarioOtherInsurance() {
        BankData data = scoredFile(10_000_000f);
        fakeOtherInsurer();
        InsuranceManager ins = data.getInsuranceManager();
        com.fs.starfarer.api.fleet.FleetMemberAPI a = ship("a", 1_000_000f);
        com.fs.starfarer.api.fleet.FleetMemberAPI b = ship("b", 1_000_000f);
        ship("c", 1_000_000f);
        otherPolicies.put("a", 800_000f); // Nexerelin's default: 80% of the base value
        otherPolicies.put("b", 300_000f); // a smaller policy
        float standard = ins.premiumFor(InsurancePlan.STANDARD, data);
        float comprehensive = ins.premiumFor(InsurancePlan.COMPREHENSIVE, data);
        advanceDays(1);
        ins.buy(InsurancePlan.COMPREHENSIVE, data);
        months(1);

        otherInsurerPays("a");            // the other insurer settles before the bank hears of the loss...
        lose(a, false);
        lose(b, false);                   // ...or after it
        otherInsurerPays("b");
        float before = credits.get();
        advanceDays(3);
        float paid = credits.get() - before;
        InsuranceManager.Claim cb = ins.getClaims().get(0), ca = ins.getClaims().get(1);

        System.out.printf("[other insurance] premiums on the excess: standard %.0f, comprehensive %.0f | ship a (80%% elsewhere): %s %.0f | ship b (30%% elsewhere): %s %.0f%n",
            standard, comprehensive, ca.outcome, ca.payout, cb.outcome, cb.payout);
        check("[other insurance] the premium is charged only on the cover this policy adds",
            Math.abs(standard - 0.008f * (0f + 300_000f + 600_000f)) < 1f && Math.abs(comprehensive - 0.013f * (0f + 500_000f + 800_000f)) < 1f);
        check("[other insurance] a ship fully paid by the other insurer is not paid twice",
            ca.payout == 0f && "claim.reason.otherInsurance".equals(ca.outcome));
        check("[other insurance] a ship partly insured elsewhere is paid the excess: both together = this policy alone",
            Math.abs(cb.payout + 300_000f - (0.8f * 1_000_000f - 5_000f)) < 1f && "claim.reason.excess".equals(cb.outcome) && Math.abs(paid - cb.payout) < 1f);
    }

    /** Ship Mastery System's graveyard (or any mod) brings a hull back after the insurer paid for it. */
    static void scenarioRecoveredHull() {
        reset(10_000_000f); // no loans: the month end bills only insurance
        BankData data = BankData.get();
        InsuranceManager ins = data.getInsuranceManager();
        com.fs.starfarer.api.fleet.FleetMemberAPI x = ship("x", 1_000_000f);
        ship("y", 1_000_000f);
        advanceDays(1);
        ins.buy(InsurancePlan.STANDARD, data);
        months(1);
        lose(x, false);
        advanceDays(3);
        float payout = ins.getClaims().get(0).payout;

        advanceDays(5);
        fleetMembers.add(x);              // salvaged from the graveyard
        int msgs = messages.size();
        advanceDays(1);
        float due = ins.getRecoveryDue();
        boolean told = messages.size() > msgs;
        advanceDays(1);
        float dueNextDay = ins.getRecoveryDue();
        float premium = ins.premiumFor(InsurancePlan.STANDARD, data);
        float before = credits.get();
        advanceDays(15);
        monthEnd();
        float charged = before - credits.get();

        System.out.printf("[recovered hull] paid %.0f for the lost hull | back in the fleet: %.0f billed back (next day %.0f), told %s | month end charged %.0f (premium %.0f), still due %.0f%n",
            payout, due, dueNextDay, told, charged, premium, ins.getRecoveryDue());
        check("[recovered hull] a hull back in the fleet bills its payout back, once", payout > 0f && due == payout && dueNextDay == payout && told
            && ins.getClaims().get(0).recovered);
        check("[recovered hull] the payout is charged at month end with the premium", Math.abs(charged - (payout + premium)) < 1f && ins.getRecoveryDue() == 0f);
    }

    // ------------------------------------------------------------------ development financing (0.4.0 section C)

    /** A calm sector, a size-6 player colony with room for any building, and the Hegemony Favorable to the player. */
    static BankData developmentSector() {
        reset(10_000_000f);
        economySector();
        colony("home", "player", 6, 50_000f);
        relations.put(key("hegemony", "player"), 0.3f);
        BankData data = BankData.get();
        data.getDevelopmentManager().setRandom(new Random(7));
        months(1); // treasuries
        return data;
    }

    /** The Hegemony offers to finance a building at the player's colony; the player accepts. */
    static com.bankofstarsector.economy.DevelopmentContract acceptedOffer(BankData data) {
        BankSettings.DEV_OFFER_CHANCE = 1f;
        months(1);
        BankSettings.DEV_OFFER_CHANCE = 0f;
        List<com.bankofstarsector.economy.DevelopmentContract> offers = data.getDevelopmentManager().offersToPlayer();
        if (offers.isEmpty()) return null;
        com.bankofstarsector.economy.DevelopmentContract c = offers.get(0);
        String why = data.getDevelopmentManager().acceptOffer(c.id);
        return why == null ? c : null;
    }

    static float treasury(BankData data, String fid) {
        com.bankofstarsector.economy.FactionTreasury t = data.getSectorEconomy().getTreasury(fid);
        return t == null ? 0f : t.balance;
    }

    /** Built on time and repaid at the low rate: the investor gets its money back with interest, relations improve. */
    static void scenarioDevelopmentBuiltAndRepaid() {
        BankData data = developmentSector();
        float relation0 = relation("hegemony", "player");
        float credits0 = credits.get();
        float heg0 = treasury(data, "hegemony");
        BankSettings.DEV_OFFER_CHANCE = 1f;
        months(1);
        BankSettings.DEV_OFFER_CHANCE = 0f;
        List<com.bankofstarsector.economy.DevelopmentContract> offers = data.getDevelopmentManager().offersToPlayer();
        com.bankofstarsector.economy.DevelopmentContract c = offers.isEmpty() ? null : offers.get(0);
        boolean offered = c != null && "hegemony".equals(c.investorId) && "home".equals(c.marketId);
        float hegBefore = treasury(data, "hegemony");
        float before = credits.get();
        String why = c == null ? "none" : data.getDevelopmentManager().acceptOffer(c.id);
        float paidOut = credits.get() - before;
        float hegPaid = hegBefore - treasury(data, "hegemony");
        BankAccount loan = c == null ? null : data.getLoanManager().findLoan(c.loanAccountId);
        months(1);
        boolean nothingBilled = loan != null && loan.amountPastDue == 0f && Math.abs(loan.remainingBalance - c.principal) < 1f;
        colonies.get("home").dev.put(c.industryId, new float[]{0f, 0f}); // the player builds it
        advanceDays(1);
        com.bankofstarsector.economy.ContractState afterBuild = c.state;
        float relationBuilt = relation("hegemony", "player");
        months(26);
        System.out.printf("[development] offer from %s: %s at %s for %.0f (accept: %s) | paid out %.0f, from the treasury %.0f | billed while building: %s | %s after building | after 26 months: %s, repaid %.0f | relation %.2f -> %.2f -> %.2f%n",
            c == null ? "-" : c.investorId, c == null ? "-" : c.industryId, c == null ? "-" : c.marketId, c == null ? 0f : c.principal, why,
            paidOut, hegPaid, !nothingBilled, afterBuild, c == null ? "-" : c.state, c == null ? 0f : c.repaid, relation0, relationBuilt, relation("hegemony", "player"));
        check("[development] a Favorable faction with a surplus offers to finance a building at the player's colony", offered);
        check("[development] accepting pays the principal out of the investor's treasury", why == null && Math.abs(paidOut - c.principal) < 1f
            && Math.abs(hegPaid - c.principal) < 1f && loan != null && loan.loanType == LoanType.DEVELOPMENT);
        check("[development] nothing is billed while the building goes up", nothingBilled);
        check("[development] built: repayments start, relations improve", afterBuild == com.bankofstarsector.economy.ContractState.REPAYING
            && Math.abs(relationBuilt - relation0 - BankSettings.DEV_REL_BUILT) < 1e-4);
        check("[development] repaid at the low rate: closed, the investor got principal + interest, relations improve again",
            c.state == com.bankofstarsector.economy.ContractState.CLOSED && c.repaid > c.principal * 1.1f && c.repaid < c.principal * 1.2f
            && relation("hegemony", "player") > relationBuilt + BankSettings.DEV_REL_CLOSED - 1e-4);
    }

    /** Deadline missed, then the demand paid: penalty rate, relations down, closed. */
    static void scenarioDevelopmentDemandPaid() {
        BankData data = developmentSector();
        com.bankofstarsector.economy.DevelopmentContract c = acceptedOffer(data);
        float relation0 = relation("hegemony", "player");
        advanceDays((int) c.deadlineDays + 2); // never built
        BankAccount loan = data.getLoanManager().findLoan(c.loanAccountId);
        com.bankofstarsector.economy.ContractState missed = c.state;
        float rate = loan.monthlyRate;
        float relationMissed = relation("hegemony", "player");
        boolean paid = data.getLoanManager().payOff(loan.accountId);
        System.out.printf("[development demand] deadline %.0f days: %s, rate %.1f%%/mo, relation %.2f -> %.2f | paid off: %s -> %s (%s)%n",
            c.deadlineDays, missed, rate * 100, relation0, relationMissed, paid, c.state, c.outcome);
        check("[development demand] a missed deadline becomes a demand at the penalty rate, and relations drop",
            missed == com.bankofstarsector.economy.ContractState.DEMAND && Math.abs(rate - BankSettings.DEV_PENALTY_RATE) < 1e-6
            && Math.abs(relationMissed - relation0 - BankSettings.DEV_REL_MISSED) < 1e-4);
        check("[development demand] paying the demand closes the contract", paid && c.state == com.bankofstarsector.economy.ContractState.CLOSED);
    }

    /** Demand unpaid: full force (default, Collection Fleet at full size, relations down again). */
    static void scenarioDevelopmentCollectionFullForce() {
        BankData data = developmentSector();
        com.bankofstarsector.economy.DevelopmentContract c = acceptedOffer(data);
        credits.set(0f);
        data.setAutopayEnabled(false);
        advanceDays((int) c.deadlineDays + 2);
        float relationMissed = relation("hegemony", "player");
        advanceDays(BankSettings.DEV_DEMAND_DAYS + 3);
        BankAccount loan = data.getLoanManager().findLoan(c.loanAccountId);
        System.out.printf("[development collection] demand unpaid: %s, loan %s, fleet %s, minimal force %s, relation %.2f -> %.2f%n",
            c.state, loan.status, data.getCollectionManager().hasActiveCollection(loan.accountId), loan.minimalForce,
            relationMissed, relation("hegemony", "player"));
        check("[development collection] an unpaid demand goes to collection by full force",
            c.state == com.bankofstarsector.economy.ContractState.COLLECTION && loan.status == LoanStatus.DEFAULTED
            && data.getCollectionManager().hasActiveCollection(loan.accountId) && !loan.minimalForce);
        check("[development collection] relations drop again", relation("hegemony", "player") < relationMissed - 0.1f);
    }

    /** Built, then repayments missed: collection by minimal force (a smaller fleet). */
    static void scenarioDevelopmentMinimalForce() {
        BankData data = developmentSector();
        com.bankofstarsector.economy.DevelopmentContract c = acceptedOffer(data);
        colonies.get("home").dev.put(c.industryId, new float[]{0f, 0f});
        advanceDays(1);
        months(1);
        credits.set(0f);
        data.setAutopayEnabled(false);
        months(5);
        BankAccount loan = data.getLoanManager().findLoan(c.loanAccountId);
        int minimal = com.bankofstarsector.collection.CollectionManager.fleetPointsFor(loan, 0);
        loan.minimalForce = false;
        int full = com.bankofstarsector.collection.CollectionManager.fleetPointsFor(loan, 0);
        loan.minimalForce = true;
        System.out.printf("[development minimal force] built, repayments missed: %s, %d days overdue, fleet %s, %d FP (full force %d)%n",
            loan.status, loan.daysOverdue, data.getCollectionManager().hasActiveCollection(loan.accountId), minimal, full);
        check("[development minimal force] missed repayments of a built project bring a smaller Collection Fleet",
            data.getCollectionManager().hasActiveCollection(loan.accountId) && minimal < full);
    }

    /** NPC to NPC: a deal between Favorable factions builds for real, gets repaid; the cap holds; absent factions never take part. */
    static void scenarioDevelopmentNpcDeals() {
        reset(1_000_000f);
        economySector();
        relations.put(key("hegemony", "tritachyon"), 0.3f);
        relations.put(key("hegemony", "uaf"), 0.5f);     // a modded faction with no markets in this sector
        relations.put(key("tritachyon", "uaf"), 0.5f);
        BankData data = BankData.get();
        com.bankofstarsector.economy.DevelopmentManager dm = data.getDevelopmentManager();
        dm.setRandom(new Random(3));
        months(1);
        BankSettings.DEV_NPC_DEALS_PER_MONTH = 1;
        months(1);
        BankSettings.DEV_NPC_DEALS_PER_MONTH = 0;
        List<com.bankofstarsector.economy.DevelopmentContract> deals = dm.sectorDeals();
        com.bankofstarsector.economy.DevelopmentContract d = deals.isEmpty() ? null : deals.get(0);
        Colony market = d == null ? null : colonies.get(d.marketId);
        boolean building = market != null && market.dev.containsKey(d.industryId) && market.dev.get(d.industryId)[0] > 0f;
        advanceDays(31);
        boolean built = market != null && market.dev.get(d.industryId)[0] == 0f && d.state == com.bankofstarsector.economy.ContractState.REPAYING;
        months(26);
        System.out.printf("[development npc] deal: %s finances %s's %s at %s for %.0f | building: %s, built: %s | after 26 months: %s, repaid %.0f%n",
            d == null ? "-" : d.investorId, d == null ? "-" : d.recipientId, d == null ? "-" : d.industryId, d == null ? "-" : d.marketId,
            d == null ? 0f : d.principal, building, built, d == null ? "-" : d.state, d == null ? 0f : d.repaid);
        check("[development npc] Favorable factions sign a deal and the recipient really builds",
            d != null && building && built && ("hegemony".equals(d.investorId) || "tritachyon".equals(d.investorId)));
        check("[development npc] the recipient repays the investor from its treasury", d != null
            && d.state == com.bankofstarsector.economy.ContractState.CLOSED && d.repaid > d.principal);

        // The cap: at most DEV_MAX_NPC_ACTIVE under way, however many are proposed.
        BankSettings.DEV_NPC_DEALS_PER_MONTH = 3;
        BankSettings.DEV_MAX_NPC_ACTIVE = 2;
        int maxActive = 0;
        for (int i = 0; i < 8; i++) {
            months(1);
            int active = dm.count(com.bankofstarsector.economy.ContractState.BUILDING) + dm.count(com.bankofstarsector.economy.ContractState.REPAYING)
                + dm.count(com.bankofstarsector.economy.ContractState.DEMAND);
            maxActive = Math.max(maxActive, active);
        }
        BankSettings.DEV_NPC_DEALS_PER_MONTH = 0;
        boolean uafIn = false;
        for (com.bankofstarsector.economy.DevelopmentContract x : dm.getContracts()) uafIn |= "uaf".equals(x.investorId) || "uaf".equals(x.recipientId);
        System.out.printf("[development npc] 3 proposals a month, cap 2: at most %d under way, %d deals in all | a faction without markets took part: %s%n",
            maxActive, dm.sectorDeals().size(), uafIn);
        check("[development npc] never more deals under way than the cap", maxActive <= 2 && maxActive > 0);
        check("[development npc] factions take part only when they exist (hold markets)", !uafIn);
    }

    /** The player as investor: a profitable project is accepted and repaid; a bad one declined; a missed deadline recovered. */
    static void scenarioDevelopmentPlayerInvests() {
        reset(5_000_000f);
        economySector();
        colony("heg_big", "hegemony", 6, 120_000f);
        colony("heg_small", "hegemony", 3, 20_000f);
        relations.put(key("hegemony", "player"), 0.3f);
        BankData data = BankData.get();
        com.bankofstarsector.economy.DevelopmentManager dm = data.getDevelopmentManager();
        months(1);
        String small = dm.offerToFinance("heg_small", "mining", 0.01f);
        float before = credits.get();
        String big = dm.offerToFinance("heg_big", "mining", 0.01f);
        float paid = before - credits.get();
        com.bankofstarsector.economy.DevelopmentContract c = null;
        for (com.bankofstarsector.economy.DevelopmentContract x : dm.playerContracts()) if ("heg_big".equals(x.marketId)) c = x;
        boolean building = colonies.get("heg_big").dev.containsKey("mining");
        advanceDays(31);
        com.bankofstarsector.economy.ContractState built = c == null ? null : c.state;
        float beforeMonths = credits.get();
        months(2);
        float received = c == null ? 0f : c.repaid;
        System.out.printf("[development investor] size 3 project: %s | size 6 at 1%%/mo: %s, paid %.0f, building %s, then %s, repaid %.0f in 2 months (credits %+.0f)%n",
            small, big == null ? "accepted" : big, paid, building, built, received, credits.get() - beforeMonths);
        check("[development investor] a bad deal (a small market) is declined", "dev.reason.declined".equals(small));
        check("[development investor] a profitable one is accepted: the player pays and the faction builds",
            big == null && c != null && Math.abs(paid - c.principal) < 1f && building && built == com.bankofstarsector.economy.ContractState.REPAYING);
        check("[development investor] the faction repays the player at month end", received > 0f && Math.abs((credits.get() - beforeMonths) - received) < 1f);

        // A second project; the market changes hands before it is built: demand, then recovery from the treasury.
        String second = dm.offerToFinance("heg0", "mining", 0.005f);
        com.bankofstarsector.economy.DevelopmentContract d = null;
        for (com.bankofstarsector.economy.DevelopmentContract x : dm.playerContracts()) if ("heg0".equals(x.marketId)) d = x;
        float relation0 = relation("hegemony", "player");
        colonies.get("heg0").factionId = "persean";
        advanceDays(1);
        com.bankofstarsector.economy.ContractState lost = d == null ? null : d.state;
        advanceDays(BankSettings.DEV_DEMAND_DAYS - 2);
        com.bankofstarsector.economy.FactionTreasury t = data.getSectorEconomy().getTreasury("hegemony");
        t.balance = 50_000f; // a short treasury: only part can be recovered
        float beforeRecovery = credits.get();
        advanceDays(3);
        float recovered = credits.get() - beforeRecovery;
        System.out.printf("[development investor] second project: %s | market lost: %s | recovered %.0f of %.0f -> %s, relation %.2f -> %.2f%n",
            second == null ? "accepted" : second, lost, recovered, d == null ? 0f : d.principal, d == null ? "-" : d.state,
            relation0, relation("hegemony", "player"));
        check("[development investor] a market lost before completion is a missed deadline", lost == com.bankofstarsector.economy.ContractState.DEMAND);
        check("[development investor] the Clearing Hall recovers what the treasury holds, the rest defaults, relations drop",
            Math.abs(recovered - 50_000f) < 1f && d.state == com.bankofstarsector.economy.ContractState.DEFAULTED
            && relation("hegemony", "player") < relation0 - 0.15f);
    }

    // ------------------------------------------------------------------ helpers

    static void reset(float startCredits) {
        persistent.clear();
        colonies.clear();
        factions.clear();
        relations.clear();
        fleetMembers.clear();
        pirateLike.clear();
        BankSettings.DEV_OFFER_CHANCE = 0f;          // development scenarios turn offers and deals on themselves
        BankSettings.DEV_NPC_DEALS_PER_MONTH = 0;
        BankSettings.DEV_MAX_NPC_ACTIVE = 4;
        com.bankofstarsector.compat.OtherInsurance.source = null;
        BankSettings.POLITICAL_FACTIONS.clear();
        BankSettings.NON_POLITICAL_FACTIONS.clear();
        priceLevel = 1f;
        credits.set(startCredits);
        now = 1_000_000_000L;
    }

    static final BankCampaignScript daily = new BankCampaignScript();
    static final BankEconomyListener econ = new BankEconomyListener();

    static void advanceDays(int days) {
        daily.advance(0f); // initialise day stamp
        for (int i = 0; i < days; i++) {
            now += DAY;
            advanceConstruction();
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

    // ------------------------------------------------------------------ fake colonies and factions

    static final Map<String, Colony> colonies = new LinkedHashMap<String, Colony>();
    static final Map<String, Object> factions = new HashMap<String, Object>();
    static final Map<String, Float> relations = new HashMap<String, Float>();
    /** Factions whose faction file sets "pirateBehavior" (modded swarms and raiders). */
    static final Set<String> pirateLike = new HashSet<String>();

    /** A market the tests can shape: size, hazard, income, conditions, industries, owner. */
    static final class Colony {
        String id, name, factionId;
        int size;
        float hazard = 1f, netIncome, grossIncome = -1f, stability = 7f;
        boolean inEconomy = true;
        final Set<String> conditions = new LinkedHashSet<String>();
        final List<float[]> industries = new ArrayList<float[]>(); // {buildCost, improved 0/1}
        /** Buildings added for development contracts: id -> {building 0/1, days left}. */
        final Map<String, float[]> dev = new LinkedHashMap<String, float[]>();
        final Set<String> unavailable = new HashSet<String>();
        /** Construction doesn't progress (a disrupted market). */
        boolean stalled;
        com.fs.starfarer.api.campaign.econ.MarketAPI proxy;
    }

    static final Set<String> STRUCTURES = new HashSet<String>(Arrays.asList("patrolhq", "waystation", "grounddefenses"));
    static float devCost(String id) { return STRUCTURES.contains(id) ? 100_000f : 200_000f; }
    static float devTime(String id) { return STRUCTURES.contains(id) ? 20f : 30f; }

    /** A building of a colony's development list, as the game's Industry. */
    static Object devIndustry(final Colony c, final String id) {
        return Proxy.newProxyInstance(BankSim.class.getClassLoader(),
            new Class<?>[]{com.fs.starfarer.api.campaign.econ.Industry.class}, new InvocationHandler() {
                public Object invoke(Object proxy, Method m, Object[] a) {
                    String n = m.getName();
                    float[] e = c.dev.get(id);
                    if (n.equals("getId") || n.equals("getCurrentName")) return id;
                    if (n.equals("getBuildCost")) return devCost(id);
                    if (n.equals("getBuildTime")) return devTime(id);
                    if (n.equals("isAvailableToBuild")) return !c.unavailable.contains(id);
                    if (n.equals("isIndustry")) return !STRUCTURES.contains(id);
                    if (n.equals("isStructure")) return STRUCTURES.contains(id);
                    if (n.equals("isBuilding")) return e != null && e[0] > 0f;
                    if (n.equals("isFunctional")) return e != null && e[0] == 0f;
                    if (n.equals("startBuilding")) { c.dev.put(id, new float[]{1f, devTime(id)}); return null; }
                    if (n.equals("hashCode")) return id.hashCode();
                    if (n.equals("equals")) return proxy == a[0];
                    if (n.equals("toString")) return "industry:" + id;
                    return defaultFor(m.getReturnType());
                }
            });
    }

    /** One day of construction everywhere (the game advances industries with the economy). */
    static void advanceConstruction() {
        for (Colony c : colonies.values()) {
            if (c.stalled) continue;
            for (float[] e : c.dev.values()) if (e[0] > 0f && --e[1] <= 0f) e[0] = 0f;
        }
    }

    static Colony colony(String id, String factionId, int size, float netIncome) {
        final Colony c = new Colony();
        c.id = id; c.name = id; c.factionId = factionId; c.size = size; c.netIncome = netIncome;
        c.proxy = (com.fs.starfarer.api.campaign.econ.MarketAPI) Proxy.newProxyInstance(BankSim.class.getClassLoader(),
            new Class<?>[]{com.fs.starfarer.api.campaign.econ.MarketAPI.class}, new InvocationHandler() {
                public Object invoke(Object proxy, Method m, Object[] a) throws Throwable {
                    String n = m.getName();
                    if (n.equals("getId")) return c.id;
                    if (n.equals("getName")) return c.name;
                    if (n.equals("getFactionId")) return c.factionId;
                    if (n.equals("setFactionId")) { c.factionId = (String) a[0]; return null; }
                    if (n.equals("getFaction")) return faction(c.factionId);
                    if (n.equals("isPlayerOwned")) return "player".equals(c.factionId);
                    if (n.equals("getSize")) return c.size;
                    if (n.equals("getHazardValue")) return c.hazard;
                    if (n.equals("getNetIncome")) return c.netIncome;
                    if (n.equals("getGrossIncome")) return c.grossIncome >= 0f ? c.grossIncome : Math.max(0f, c.netIncome) * 2f;
                    if (n.equals("getStabilityValue")) return c.stability;
                    if (n.equals("getSupplyPrice")) return 100f * priceLevel;
                    if (n.equals("isInEconomy")) return c.inEconomy;
                    if (n.equals("isHidden")) return false;
                    if (n.equals("hasCondition")) return c.conditions.contains((String) a[0]);
                    if (n.equals("addCondition") && a[0] instanceof String) { c.conditions.add((String) a[0]); return a[0]; }
                    if (n.equals("removeCondition")) { c.conditions.remove((String) a[0]); return null; }
                    if (n.equals("getConditions")) {
                        List<Object> out = new ArrayList<Object>();
                        for (final String cid : c.conditions) out.add(withId(com.fs.starfarer.api.campaign.econ.MarketConditionAPI.class, cid));
                        return out;
                    }
                    if (n.equals("getIndustries")) {
                        List<Object> out = new ArrayList<Object>();
                        for (float[] ind : c.industries) out.add(industry(ind[0], ind[1] > 0f));
                        return out;
                    }
                    if (n.equals("getConstructionQueue")) return new com.fs.starfarer.api.impl.campaign.econ.impl.ConstructionQueue();
                    if (n.equals("hasIndustry")) return c.dev.containsKey((String) a[0]);
                    if (n.equals("instantiateIndustry")) return devIndustry(c, (String) a[0]);
                    if (n.equals("addIndustry") && a.length == 1) { c.dev.put((String) a[0], new float[]{0f, 0f}); return null; }
                    if (n.equals("getIndustry")) return c.dev.containsKey((String) a[0]) ? devIndustry(c, (String) a[0]) : null;
                    if (n.equals("hashCode")) return System.identityHashCode(proxy);
                    if (n.equals("equals")) return proxy == a[0];
                    if (n.equals("toString")) return "market:" + c.id;
                    if (n.equals("getStarSystem") || n.equals("getLocationInHyperspace")) return null;
                    return defaultFor(m.getReturnType());
                }
            });
        colonies.put(id, c);
        return c;
    }

    @SuppressWarnings("unchecked")
    static <T> T withId(Class<T> type, final String id) {
        return (T) Proxy.newProxyInstance(BankSim.class.getClassLoader(), new Class<?>[]{type}, new InvocationHandler() {
            public Object invoke(Object proxy, Method m, Object[] a) {
                if (m.getName().equals("getId")) return id;
                return defaultFor(m.getReturnType());
            }
        });
    }

    static Object industry(final float buildCost, final boolean improved) {
        return Proxy.newProxyInstance(BankSim.class.getClassLoader(),
            new Class<?>[]{com.fs.starfarer.api.campaign.econ.Industry.class}, new InvocationHandler() {
                public Object invoke(Object proxy, Method m, Object[] a) {
                    if (m.getName().equals("getBuildCost")) return buildCost;
                    if (m.getName().equals("isImproved")) return improved;
                    if (m.getName().equals("getAICoreId") || m.getName().equals("getSpecialItem")) return null;
                    return defaultFor(m.getReturnType());
                }
            });
    }

    static com.fs.starfarer.api.campaign.FactionAPI faction(final String id) {
        Object f = factions.get(id);
        if (f == null) {
            f = Proxy.newProxyInstance(BankSim.class.getClassLoader(),
                new Class<?>[]{com.fs.starfarer.api.campaign.FactionAPI.class}, new InvocationHandler() {
                    public Object invoke(Object proxy, Method m, Object[] a) {
                        String n = m.getName();
                        if (n.equals("getId") || n.equals("getDisplayName")) return id;
                        if (n.equals("isPlayerFaction")) return "player".equals(id);
                        if (n.equals("getCustomBoolean")) return "pirateBehavior".equals(a[0]) && pirateLike.contains(id);
                        if (n.equals("getRelationship")) return relation(id, (String) a[0]);
                        if (n.equals("adjustRelationship") && a[0] instanceof String) {
                            float cur = relation(id, (String) a[0]) + ((Number) a[1]).floatValue();
                            // RepLevel cap (a third argument): FRIENDLY tops out below COOPERATIVE (0.75)
                            if (a.length > 2 && a[2] != null && ((Number) a[1]).floatValue() > 0f) cur = Math.min(cur, 0.75f);
                            // ...and INHOSPITABLE stays above hostile (-0.5)
                            if (a.length > 2 && a[2] != null && ((Number) a[1]).floatValue() < 0f) cur = Math.max(cur, -0.49f);
                            relations.put(key(id, (String) a[0]), Math.max(-1f, Math.min(1f, cur)));
                            return a.length > 2 ? (Object) Boolean.TRUE : null;
                        }
                        if (n.equals("setRelationship") && a[0] instanceof String) {
                            relations.put(key(id, (String) a[0]), ((Number) a[1]).floatValue());
                            return null;
                        }
                        if (n.equals("isHostileTo")) {
                            String other = a[0] instanceof String ? (String) a[0]
                                : ((com.fs.starfarer.api.campaign.FactionAPI) a[0]).getId();
                            return relation(id, other) <= -0.5f;
                        }
                        if (n.equals("hashCode")) return id.hashCode();
                        if (n.equals("equals")) return proxy == a[0];
                        if (n.equals("toString")) return "faction:" + id;
                        return defaultFor(m.getReturnType());
                    }
                });
            factions.put(id, f);
        }
        return (com.fs.starfarer.api.campaign.FactionAPI) f;
    }

    static String key(String a, String b) { return a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a; }

    static float relation(String a, String b) {
        Float r = relations.get(key(a, b));
        return r == null ? 0f : r;
    }

    // ------------------------------------------------------------------ colony-secured loans (0.3.0)

    /** Alpha: size 6, 100k/month, rich ore + adequate farmland + habitable, two industries (one improved). */
    static Colony alpha() {
        Colony c = colony("alpha", "player", 6, 100_000f);
        c.conditions.add("ore_rich");
        c.conditions.add("farmland_adequate");
        c.conditions.add("habitable");
        c.industries.add(new float[]{150_000f, 1f});
        c.industries.add(new float[]{300_000f, 0f});
        return c;
    }

    /** The PBC and two bidders with real territory; pirates (never bidders) and a faction hostile to the PBC. */
    static void sectorFactions() {
        colony("pbc_hq", "pbc", 6, 0f);
        colony("heg_capital", "hegemony", 7, 0f);
        colony("tt_hub", "tritachyon", 5, 0f);
        colony("pirate_den", "pirates", 5, 0f);
        colony("church_world", "luddic_church", 6, 0f);
        relations.put(key("luddic_church", "pbc"), -0.6f);
    }

    static void scenarioColonyAppraisal() {
        reset(0f);
        Colony c = alpha();
        ColonyAppraisal a = ColonyAppraisal.of(c.proxy);
        // development 100k x 2.5^3 = 1,562,500; structures 150k x 1.25 + 300k = 487,500;
        // resources (4 + 2 + 2) x 60k = 480,000; income 12 x 100k = 1,200,000; hazard 100% -> x1.0
        float expected = 1_562_500f + 487_500f + 480_000f + 1_200_000f;
        c.hazard = 2f;
        ColonyAppraisal hazardous = ColonyAppraisal.of(c.proxy);
        System.out.printf("[appraisal] development %.0f, structures %.0f, resources %.0f, income %.0f, x%.2f = %.0f | hazard 200%%: %.0f | max loan %.0f%n",
            a.development, a.structures, a.resources, a.income, a.hazardMult, a.total, hazardous.total,
            LoanManager.securedMaxFor(colonies.get("alpha").proxy));
        check("[appraisal] development, structures, resources and income add up", Math.abs(a.total - expected) < 1f);
        check("[appraisal] a hazardous world is worth less", Math.abs(hazardous.total - expected * 0.5f) < 1f);
    }

    static void scenarioSecuredLoanSigning() {
        BankData data = scoredFile(1_000_000f);
        Colony c = alpha();
        LoanManager lm = data.getLoanManager();
        int score = data.getCreditScoreManager().getScore();
        float rate = data.getInterestEngine().calculateEffectiveLoanRate(LoanType.SECURED, score);
        float unsecured = data.getInterestEngine().calculateEffectiveLoanRate(LoanType.MEGACORP, score);
        float before = credits.get();
        float max = LoanManager.securedMaxFor(c.proxy);
        BankAccount tooMuch = lm.takeSecuredLoan(c.proxy, max * 1.5f, rate);
        BankAccount loan = lm.takeSecuredLoan(c.proxy, 1_000_000f, rate);
        BankAccount twice = lm.takeSecuredLoan(c.proxy, 100_000f, rate);
        System.out.printf("[secured] rate %.2f%% vs megacorp %.2f%% | paid out %.0f | lien: %s | second pledge: %s%n",
            rate * 100, unsecured * 100, credits.get() - before, c.conditions.contains("bos_lien"), twice);
        check("[secured] cheaper than unsecured credit", rate < unsecured);
        check("[secured] can't borrow more than the loan-to-value allows", tooMuch == null);
        check("[secured] signing pays out and puts a lien on the colony", loan != null
            && Math.abs(credits.get() - before - 1_000_000f) < 1f && c.conditions.contains("bos_lien")
            && "alpha".equals(loan.collateralMarketId));
        check("[secured] a colony secures one loan at a time", twice == null);
        credits.set(10_000_000f);
        lm.payOff(loan.accountId);
        check("[secured] paying off releases the lien", loan.status == LoanStatus.PAID_OFF && !c.conditions.contains("bos_lien"));
    }

    /** A defaulted colony-secured loan on Alpha (autopay off, no credits). */
    static BankAccount defaultedSecuredLoan(BankData data, Colony c) {
        float rate = data.getInterestEngine().calculateEffectiveLoanRate(LoanType.SECURED, data.getCreditScoreManager().getScore());
        BankAccount loan = data.getLoanManager().takeSecuredLoan(c.proxy, 1_000_000f, rate);
        data.setAutopayEnabled(false);
        credits.set(0f);
        int day = 0;
        while (loan.status != LoanStatus.DEFAULTED && day < 240) { advanceDays(1); day++; if (day % 30 == 0) monthEnd(); }
        advanceDays(1);
        return loan;
    }

    static float garnishedThisMonth(BankData data) {
        float total = 0f;
        for (BankData.TransactionRecord t : data.getTransactionHistory()) if ("GARNISH".equals(t.type)) total -= t.amount;
        return total;
    }

    static void scenarioReceivership() {
        BankData data = scoredFile(1_000_000f);
        Colony c = alpha();
        BankAccount loan = defaultedSecuredLoan(data, c);
        com.bankofstarsector.collection.ForeclosureManager fm = data.getForeclosureManager();
        int stage = fm.getStage(loan);
        boolean marked = c.conditions.contains("bos_receivership");
        boolean fleet = data.getCollectionManager().hasActiveCollection(loan.accountId);
        float garnishedBefore = garnishedThisMonth(data);
        advanceDays(29);
        monthEnd();
        float taken = garnishedThisMonth(data) - garnishedBefore;
        advanceDays(BankSettings.FORECLOSURE_DELAY_DAYS + 30);
        int stageLater = fm.getStage(loan);
        float pbcRel = relation("pbc", "player");
        credits.set(10_000_000f);
        data.getLoanManager().payPastDue(loan.accountId);
        advanceDays(1);
        System.out.printf("[receivership] stage %d, condition %s, fleet %s | month end took %.0f of the colony's income | without Nexerelin after %d days: stage %d, PBC relation %.2f | paid: status %s, stage %d, condition %s%n",
            stage, marked, fleet, taken, BankSettings.FORECLOSURE_DELAY_DAYS + 30,
            stageLater, pbcRel, loan.status, fm.getStage(loan), c.conditions.contains("bos_receivership"));
        check("[receivership] default puts the colony in receivership (condition on the colony)",
            stage == com.bankofstarsector.collection.ForeclosureManager.STAGE_RECEIVERSHIP && marked);
        check("[receivership] no Collection Fleet for a loan secured by a colony", !fleet);
        check("[receivership] the colony's whole income goes to the loan", Math.abs(taken - 100_000f) < 1f);
        check("[receivership] without Nexerelin there is no foreclosure war",
            stageLater == com.bankofstarsector.collection.ForeclosureManager.STAGE_RECEIVERSHIP && pbcRel > -0.5f);
        check("[receivership] paying the amount due lifts it", loan.status == LoanStatus.ACTIVE
            && fm.getStage(loan) == com.bankofstarsector.collection.ForeclosureManager.STAGE_NONE
            && !c.conditions.contains("bos_receivership") && c.conditions.contains("bos_lien"));
    }

    static void setNexerelin(boolean on) throws Exception {
        java.lang.reflect.Field f = com.bankofstarsector.compat.NexerelinCompat.class.getDeclaredField("available");
        f.setAccessible(true);
        f.set(null, on ? Boolean.TRUE : null);
    }

    @SuppressWarnings("unchecked")
    static void scenarioForeclosureAndAuction() throws Exception {
        setNexerelin(true); // invasion classes are not on the test classpath: the launch fails and is reported as such
        try {
            BankData data = scoredFile(1_000_000f);
            sectorFactions();
            relations.put(key("pbc", "player"), 0.2f);
            Colony c = alpha();
            BankAccount loan = defaultedSecuredLoan(data, c);
            com.bankofstarsector.collection.ForeclosureManager fm = data.getForeclosureManager();
            advanceDays(BankSettings.FORECLOSURE_DELAY_DAYS + 1);
            int stage = fm.getStage(loan);
            float warRel = relation("pbc", "player");

            // The PBC takes the colony; the next day it is auctioned.
            float owed = loan.remainingBalance;
            float appraisal = ColonyAppraisal.of(c.proxy).total;
            java.lang.reflect.Field sr = com.bankofstarsector.collection.ForeclosureManager.class.getDeclaredField("savedRelation");
            sr.setAccessible(true);
            Float beforeWar = ((Map<String, Float>) sr.get(fm)).get(loan.accountId);
            c.factionId = "pbc";
            float before = credits.get();
            advanceDays(1);
            float surplus = credits.get() - before;
            System.out.printf("[foreclosure] stage %d, PBC relation %.2f during | auction: %s now owns alpha, owed %.0f, appraisal %.0f, returned %.0f, loan %s, relation after %.2f, conditions %s%n",
                stage, warRel, c.factionId, owed, appraisal, surplus, loan.status, relation("pbc", "player"), c.conditions);
            check("[foreclosure] unpaid receivership turns into a foreclosure war (Nexerelin)",
                stage == com.bankofstarsector.collection.ForeclosureManager.STAGE_FORECLOSURE && warRel <= -0.5f);
            check("[auction] the PBC never keeps the colony; a major faction not hostile to it buys it",
                ("hegemony".equals(c.factionId) || "tritachyon".equals(c.factionId)));
            check("[auction] the price pays the loan and the surplus goes to the player",
                loan.status == LoanStatus.SEIZED && surplus >= appraisal * BankSettings.AUCTION_RESERVE_PCT - owed - 1f);
            // Back to the pre-war relation, which already carries the goodwill lost for missing payments.
            check("[auction] relations go back to what they were before the foreclosure war",
                beforeWar != null && Math.abs(relation("pbc", "player") - beforeWar) < 1e-4 && beforeWar > -0.5f);
            check("[auction] lien and receivership are gone", c.conditions.isEmpty() || !c.conditions.contains("bos_lien")
                && !c.conditions.contains("bos_receivership"));

            List<com.bankofstarsector.collection.ForeclosureManager.Bid> bids =
                com.bankofstarsector.collection.ForeclosureManager.collectBids(c.proxy, appraisal, new Random(1));
            StringBuilder who = new StringBuilder();
            for (com.bankofstarsector.collection.ForeclosureManager.Bid b : bids) who.append(b.faction.getId()).append(' ');
            System.out.println("[auction] bidders: " + who.toString().trim());
            boolean noPirates = true, noHostile = true;
            for (com.bankofstarsector.collection.ForeclosureManager.Bid b : bids) {
                if ("pirates".equals(b.faction.getId())) noPirates = false;
                if ("luddic_church".equals(b.faction.getId())) noHostile = false;
            }
            check("[auction] pirates and factions hostile to the PBC don't bid", noPirates && noHostile && bids.size() == 2);
        } finally {
            setNexerelin(false);
        }
    }

    static void scenarioCollateralLoss() {
        // Captured by another faction while current: the loan simply continues, unsecured.
        BankData data = scoredFile(1_000_000f);
        Colony c = alpha();
        float rate = data.getInterestEngine().calculateEffectiveLoanRate(LoanType.SECURED, data.getCreditScoreManager().getScore());
        BankAccount captured = data.getLoanManager().takeSecuredLoan(c.proxy, 500_000f, rate);
        c.factionId = "hegemony";
        advanceDays(1);
        boolean unsecured = captured.collateralMarketId == null && captured.status == LoanStatus.ACTIVE;

        // Abandoned while current: the whole balance falls due.
        data = scoredFile(1_000_000f);
        c = alpha();
        BankAccount abandoned = data.getLoanManager().takeSecuredLoan(c.proxy, 500_000f, rate);
        c.inEconomy = false;
        advanceDays(1);
        System.out.printf("[collateral] captured: unsecured %s | abandoned: status %s, due now %.0f of %.0f%n",
            unsecured, abandoned.status, abandoned.amountPastDue, abandoned.remainingBalance);
        check("[collateral] captured by another faction: the loan continues unsecured", unsecured);
        check("[collateral] abandoned: the whole balance is due at once (default)",
            abandoned.status == LoanStatus.DEFAULTED && abandoned.amountPastDue >= abandoned.remainingBalance - 1f);
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
                if (n.equals("getFaction") && a != null && a.length == 1 && a[0] instanceof String) return faction((String) a[0]);
                if (n.equals("getPlayerFaction")) return faction("player");
                if (n.equals("getMarket") && a != null && a.length == 1 && a[0] instanceof String) {
                    Colony c = colonies.get((String) a[0]);
                    return c != null && c.inEconomy ? c.proxy : null;
                }
                if (n.equals("getMembersListCopy")) return new ArrayList<Object>(fleetMembers);
                if (n.equals("getMarketsCopy")) {
                    marketScans++;
                    List<Object> out = new ArrayList<Object>();
                    for (Colony c : colonies.values()) if (c.inEconomy) out.add(c.proxy);
                    return out;
                }
                if (n.equals("addMessage")) { messages.add(String.valueOf(a[0])); return null; }
                if (n.equals("getMod") && m.getReturnType() == com.fs.starfarer.api.combat.StatBonus.class) {
                    com.fs.starfarer.api.combat.StatBonus b = new com.fs.starfarer.api.combat.StatBonus(); // e.g. a market's industry slots
                    b.modifyFlat("sim", 4f);
                    return b;
                }
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
