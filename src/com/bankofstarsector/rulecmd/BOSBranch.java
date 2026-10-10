package com.bankofstarsector.rulecmd;

import com.bankofstarsector.banking.BankAccount;
import com.bankofstarsector.banking.InvestmentType;
import com.bankofstarsector.banking.LoanManager;
import com.bankofstarsector.banking.LoanType;
import com.bankofstarsector.core.BankData;
import com.bankofstarsector.core.BankModPlugin;
import com.bankofstarsector.core.Str;
import com.bankofstarsector.ui.Quote;
import com.fs.starfarer.api.Global;
import com.fs.starfarer.api.campaign.CoreInteractionListener;
import com.fs.starfarer.api.campaign.CoreUITabId;
import com.fs.starfarer.api.campaign.InteractionDialogAPI;
import com.fs.starfarer.api.campaign.OptionPanelAPI;
import com.fs.starfarer.api.campaign.TextPanelAPI;
import com.fs.starfarer.api.campaign.rules.MemKeys;
import com.fs.starfarer.api.campaign.rules.MemoryAPI;
import com.fs.starfarer.api.impl.campaign.rulecmd.BaseCommandPlugin;
import com.fs.starfarer.api.ui.ValueDisplayMode;
import com.fs.starfarer.api.util.Misc;
import com.fs.starfarer.api.util.Misc.Token;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * BOSBranch &lt;addOption|visit|payPastDue|terminal|isAction|action&gt;
 *
 * The Confederation branch office available at every PBC market. Besides paying arrears and opening
 * the terminal, the teller takes loan applications and investments with a free amount (a selector),
 * and shows what the player is signing before they sign.
 *
 * The loan and investment screens use options named "bos_br:..."; one rules.csv row routes them all
 * here ("isAction" as its condition, "action" as its script).
 */
public class BOSBranch extends BaseCommandPlugin implements CoreInteractionListener {

    public static final String OPT_VISIT = "bos_branchVisit";
    public static final String OPT_PAY = "bos_branchPay";
    public static final String OPT_TERMINAL = "bos_branchTerminal";
    public static final String OPT_BACK = "bos_branchBack";
    /** Prefix of the loan and investment screens' options. */
    public static final String PREFIX = "bos_br:";
    private static final String SELECTOR = "bos_br_amount";

    private transient InteractionDialogAPI dialog;
    private transient Map<String, MemoryAPI> memoryMap;

    @Override
    public boolean doesCommandAddOptions() { return true; }

    /** Same slot the market menu used for the static rules.csv option (14). */
    @Override
    public int getOptionOrder(List<Token> params, Map<String, MemoryAPI> memoryMap) {
        return 14;
    }

    @Override
    public boolean execute(String ruleId, InteractionDialogAPI dialog, List<Token> params, Map<String, MemoryAPI> memoryMap) {
        if (params.isEmpty()) return false;
        String action = params.get(0).getString(memoryMap);
        if ("isAction".equals(action)) {
            String option = selectedOption(memoryMap);
            return option != null && option.startsWith(PREFIX);
        }
        if (dialog == null) return false;
        this.dialog = dialog;
        this.memoryMap = memoryMap;
        BankData data = BankData.get();
        TextPanelAPI text = dialog.getTextPanel();

        if ("addOption".equals(action)) {
            // Market main menu entry, added from code so its label can be translated.
            dialog.getOptionPanel().addOption(Str.get("branch.option"), OPT_VISIT);
            return true;
        }

        if ("action".equals(action)) {
            String option = selectedOption(memoryMap);
            if (option != null && option.startsWith(PREFIX)) handle(option.substring(PREFIX.length()).split(":"));
            return true;
        }

        if ("payPastDue".equals(action)) {
            float credits = Global.getSector().getPlayerFleet().getCargo().getCredits().get();
            float paid = 0f;
            for (BankAccount loan : data.getLoanManager().getActiveLoans()) {
                if (loan.amountPastDue <= 1f || credits < loan.amountPastDue) continue;
                float amount = loan.amountPastDue;
                if (data.getLoanManager().makePayment(loan.accountId, amount)) {
                    paid += amount;
                    credits -= amount;
                }
            }
            if (paid > 0f) text.addPara(Str.get("branch.paid"), Misc.getHighlightColor(), Misc.getDGSCredits(paid));
            else text.addPara(Str.get("branch.cannotPay"), Misc.getNegativeHighlightColor());
        } else if ("terminal".equals(action)) {
            dialog.getOptionPanel().clearOptions();
            dialog.getVisualPanel().showCore(CoreUITabId.INTEL, dialog.getInteractionTarget(), BankModPlugin.getTerminal(), this);
            return true;
        } else {
            text.addPara(Str.get("branch.intro"));
        }
        showSummary();
        return true;
    }

    private static String selectedOption(Map<String, MemoryAPI> memoryMap) {
        MemoryAPI local = memoryMap == null ? null : memoryMap.get(MemKeys.LOCAL);
        return local == null ? null : local.getString("$option");
    }

    /** The branch's main screen: the player's file and what they can do here. */
    private void showSummary() {
        BankData data = BankData.get();
        LoanManager lm = data.getLoanManager();
        float debt = lm.getTotalDebt();
        float pastDue = lm.getTotalPastDue();
        dialog.getTextPanel().addPara(Str.get("branch.summary"), Misc.getHighlightColor(),
            data.getCreditScoreManager().getScoreText(), data.getCreditScoreManager().getBracket(),
            Misc.getDGSCredits(debt), Misc.getDGSCredits(pastDue));

        OptionPanelAPI options = dialog.getOptionPanel();
        options.clearOptions();
        if (pastDue > 1f) options.addOption(Str.get("branch.optPay"), OPT_PAY);
        if (!availableLoans().isEmpty()) options.addOption(Str.get("branch.optLoans"), PREFIX + "loans");
        if (data.getBankruptcyManager().canInvest()) options.addOption(Str.get("branch.optInvest"), PREFIX + "invest");
        options.addOption(Str.get("branch.optTerminal"), OPT_TERMINAL);
        options.addOption(Str.get("branch.optLeave"), OPT_BACK);
        options.setShortcut(OPT_BACK, org.lwjgl.input.Keyboard.KEY_ESCAPE, false, false, false, true);
    }

    /** Installment loan types the player can take right now (the credit line is opened in the terminal). */
    private static List<LoanType> availableLoans() {
        BankData data = BankData.get();
        List<LoanType> out = new ArrayList<LoanType>();
        for (LoanType type : LoanType.values()) {
            if (type.isRevolving()) continue;
            if (data.getLoanManager().whyNot(type, data) == null) out.add(type);
        }
        return out;
    }

    private void handle(String[] p) {
        String screen = p[0];
        if ("loans".equals(screen)) showLoans();
        else if ("loan".equals(screen)) showLoanAmount(LoanType.valueOf(p[1]));
        else if ("quote".equals(screen)) showLoanQuote(LoanType.valueOf(p[1]), selectedAmount());
        else if ("sign".equals(screen)) sign(LoanType.valueOf(p[1]), Float.parseFloat(p[2]));
        else if ("invest".equals(screen)) showInvestments();
        else if ("inv".equals(screen)) showInvestAmount(InvestmentType.valueOf(p[1]));
        else if ("iquote".equals(screen)) showInvestQuote(InvestmentType.valueOf(p[1]), selectedAmount());
        else if ("buy".equals(screen)) buy(InvestmentType.valueOf(p[1]), Float.parseFloat(p[2]));
        else showSummary(); // "back"
    }

    /** Read before the options are cleared: the selector goes away with them. */
    private float selectedAmount() {
        OptionPanelAPI options = dialog.getOptionPanel();
        return options.hasSelector(SELECTOR) ? Quote.roundAmount(options.getSelectorValue(SELECTOR)) : 0f;
    }

    private void addBack(String target) {
        OptionPanelAPI options = dialog.getOptionPanel();
        options.addOption(Str.get("branch.optBack"), PREFIX + target);
        options.setShortcut(PREFIX + target, org.lwjgl.input.Keyboard.KEY_ESCAPE, false, false, false, true);
    }

    // ------------------------------------------------------------------ loans

    private void showLoans() {
        BankData data = BankData.get();
        int score = data.getCreditScoreManager().getScore();
        TextPanelAPI text = dialog.getTextPanel();
        OptionPanelAPI options = dialog.getOptionPanel();
        options.clearOptions();
        text.addPara(Str.get("branch.loans.intro"));
        for (LoanType type : availableLoans()) {
            float max = type.getMaxAmountForScore(score);
            float rate = data.getInterestEngine().calculateEffectiveLoanRate(type, score);
            text.addPara(Str.get("branch.loans.line"), Misc.getHighlightColor(), type.getDisplayName(),
                Misc.getDGSCredits(max), Quote.percentText(rate), "" + type.getTermMonths());
            options.addOption(type.getDisplayName(), PREFIX + "loan:" + type.name());
        }
        addBack("back");
    }

    private void showLoanAmount(LoanType type) {
        BankData data = BankData.get();
        float max = type.getMaxAmountForScore(data.getCreditScoreManager().getScore());
        float min = Quote.minLoanAmount(type, max);
        TextPanelAPI text = dialog.getTextPanel();
        OptionPanelAPI options = dialog.getOptionPanel();
        options.clearOptions();
        text.addPara(Str.get("branch.loan.pick"), Misc.getHighlightColor(), type.getDisplayName(),
            Misc.getDGSCredits(min), Misc.getDGSCredits(max));
        text.addPara(type.getDescription());
        options.addSelector(Str.get("branch.amount"), SELECTOR, Misc.getHighlightColor(), 400f, 120f,
            min, max, ValueDisplayMode.VALUE, null);
        options.setSelectorValue(SELECTOR, Quote.roundAmount((min + max) / 2f));
        options.addOption(Str.get("branch.optReview"), PREFIX + "quote:" + type.name());
        addBack("loans");
    }

    private void showLoanQuote(LoanType type, float amount) {
        BankData data = BankData.get();
        int score = data.getCreditScoreManager().getScore();
        float max = type.getMaxAmountForScore(score);
        amount = Math.max(Quote.minLoanAmount(type, max), Math.min(max, amount));
        float rate = data.getInterestEngine().calculateEffectiveLoanRate(type, score);
        addQuote(Quote.loan(type, amount, rate));
        OptionPanelAPI options = dialog.getOptionPanel();
        options.clearOptions();
        options.addOption(Str.f("branch.optSign", Misc.getDGSCredits(amount)), PREFIX + "sign:" + type.name() + ":" + (long) amount);
        options.addOption(Str.get("branch.optChangeAmount"), PREFIX + "loan:" + type.name());
        addBack("back");
    }

    private void sign(LoanType type, float amount) {
        BankData data = BankData.get();
        LoanManager lm = data.getLoanManager();
        int score = data.getCreditScoreManager().getScore();
        float max = type.getMaxAmountForScore(score);
        // Re-checked: the screen may be stale (a month end, a payment) by the time the player signs.
        if (lm.whyNot(type, data) != null || amount < Quote.minLoanAmount(type, max) - 1f || amount > max + 1f) {
            dialog.getTextPanel().addPara(Str.get("branch.loan.refused"), Misc.getNegativeHighlightColor());
            showSummary();
            return;
        }
        lm.takeLoan(type, amount, data.getInterestEngine().calculateEffectiveLoanRate(type, score));
        dialog.getTextPanel().addPara(Str.get(type.isBuilder() ? "branch.loan.signedHeld" : "branch.loan.signed"),
            Misc.getHighlightColor(), Misc.getDGSCredits(amount));
        showSummary();
    }

    // ------------------------------------------------------------------ investments

    private void showInvestments() {
        float credits = Global.getSector().getPlayerFleet().getCargo().getCredits().get();
        TextPanelAPI text = dialog.getTextPanel();
        OptionPanelAPI options = dialog.getOptionPanel();
        options.clearOptions();
        text.addPara(Str.get("branch.invest.intro"));
        for (InvestmentType type : InvestmentType.values()) {
            text.addPara(Str.get("branch.invest.line"), Misc.getHighlightColor(), type.getDisplayName(),
                Quote.percentText(type.baseMonthlyReturn),
                type.lockMonths > 0 ? Str.f("common.months", type.lockMonths) : Str.get("common.none"),
                Misc.getDGSCredits(type.minInvestment));
            String id = PREFIX + "inv:" + type.name();
            options.addOption(type.getDisplayName(), id);
            if (credits < type.minInvestment) {
                options.setEnabled(id, false);
                options.setTooltip(id, Str.get("terminal.invest.noCredits"));
            }
        }
        addBack("back");
    }

    private void showInvestAmount(InvestmentType type) {
        float credits = Global.getSector().getPlayerFleet().getCargo().getCredits().get();
        float min = type.minInvestment;
        float max = (float) Math.floor(credits / 1000f) * 1000f;
        if (max < min) {
            showInvestments();
            return;
        }
        TextPanelAPI text = dialog.getTextPanel();
        OptionPanelAPI options = dialog.getOptionPanel();
        options.clearOptions();
        text.addPara(Str.get("branch.invest.pick"), Misc.getHighlightColor(), type.getDisplayName(),
            Misc.getDGSCredits(min), Misc.getDGSCredits(max));
        text.addPara(type.getDescription());
        options.addSelector(Str.get("branch.amount"), SELECTOR, Misc.getHighlightColor(), 400f, 120f,
            min, max, ValueDisplayMode.VALUE, null);
        options.setSelectorValue(SELECTOR, min);
        options.addOption(Str.get("branch.optReview"), PREFIX + "iquote:" + type.name());
        addBack("invest");
    }

    private void showInvestQuote(InvestmentType type, float amount) {
        float credits = Global.getSector().getPlayerFleet().getCargo().getCredits().get();
        amount = Math.max(type.minInvestment, Math.min(credits, amount));
        addQuote(Quote.investment(type, amount));
        OptionPanelAPI options = dialog.getOptionPanel();
        options.clearOptions();
        options.addOption(Str.f("branch.optInvestNow", Misc.getDGSCredits(amount)), PREFIX + "buy:" + type.name() + ":" + (long) amount);
        options.addOption(Str.get("branch.optChangeAmount"), PREFIX + "inv:" + type.name());
        addBack("back");
    }

    private void buy(InvestmentType type, float amount) {
        BankData data = BankData.get();
        BankAccount inv = data.getBankruptcyManager().canInvest() ? data.getInvestmentManager().invest(type, amount) : null;
        if (inv == null) {
            dialog.getTextPanel().addPara(Str.get("branch.invest.refused"), Misc.getNegativeHighlightColor());
        } else {
            dialog.getTextPanel().addPara(Str.get("branch.invest.done"), Misc.getHighlightColor(),
                Misc.getDGSCredits(amount), type.getDisplayName());
        }
        showSummary();
    }

    private void addQuote(List<Quote.Line> lines) {
        for (Quote.Line l : lines) dialog.getTextPanel().addPara(Str.get(l.key), l.color, l.args);
    }

    public void coreUIDismissed() {
        if (dialog == null) return;
        execute(null, dialog, Misc.tokenize("visit"), memoryMap);
    }
}
