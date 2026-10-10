package com.bankofstarsector.core;

import com.fs.starfarer.api.Global;

import org.apache.log4j.Logger;
import org.json.JSONObject;

/**
 * Tunables. Defaults live here; data/config/bos_settings.json overrides them
 * (other mods can merge into that file).
 */
public class BankSettings {

    private static final Logger log = Logger.getLogger(BankSettings.class);
    public static final String SETTINGS_FILE = "data/config/bos_settings.json";

    // Language: auto (system locale), en, pt_BR
    public static String LANGUAGE = "auto";

    // Interest rate modifiers
    public static float WAR_SURCHARGE = 0.25f;
    public static float WAR_SURCHARGE_PER_WAR = 0.05f;
    public static float OVERDUE_PENALTY_PER_MONTH = 0.50f;
    public static float MARKET_DISRUPTION_RATE_MODIFIER = 0.10f;

    // Credit score
    public static int CREDIT_SCORE_MIN = 300;
    public static int CREDIT_SCORE_MAX = 850;
    public static int CREDIT_SCORE_DEFAULT = 550;
    public static int SCORE_ON_TIME_PAYMENT = 5;
    public static int SCORE_LOAN_PAYOFF = 15;
    public static int SCORE_LATE_PAYMENT = -15;
    public static int SCORE_MISSED_PAYMENT = -25;
    public static int SCORE_DEFAULT = -150;
    public static int SCORE_INVESTMENT_PER_100K = 1;
    public static int SCORE_MAX_INVESTMENT_BONUS = 5;
    public static int SCORE_COLONY_INCOME_BONUS = 3;
    public static int SCORE_DECAY_PER_MONTH = -1;
    public static int SCORE_NATURAL_DRIFT_TARGET = 600;

    // Collection
    public static int OVERDUE_PHASE1_DAYS = 30;
    public static int OVERDUE_PHASE2_DAYS = 60;
    public static int OVERDUE_PHASE3_DAYS = 90;
    public static int DEFAULT_THRESHOLD_DAYS = 90;
    public static float COLLECTION_FLEET_FP_PER_DEBT = 10000f;
    public static int COLLECTION_FLEET_MIN_FP = 30;
    public static int COLLECTION_FLEET_MAX_FP = 200;
    public static float COLLECTION_FLEET_ESCALATION = 1.5f;
    public static int COLLECTION_FLEET_RETRY_DAYS = 30;
    public static float COLLECTION_REFUSAL_REP_PENALTY = -0.15f;
    public static float SHIP_SURRENDER_VALUE_FRACTION = 0.6f;

    // Bankruptcy
    public static float BANKRUPTCY_DEBT_REDUCTION = 0.80f;
    public static int BANKRUPTCY_NO_LOANS_MONTHS = 24;
    public static int BANKRUPTCY_NO_INVEST_MONTHS = 12;
    public static int BANKRUPTCY_FULL_RECOVERY_MONTHS = 36;
    public static float BANKRUPTCY_RELATION_PENALTY = -0.30f;
    public static float BANKRUPTCY_COLONY_INCOME_PENALTY = 0.10f;
    public static int BANKRUPTCY_SCORE_RECOVERY_PER_MONTH = 5;

    // Asset seizure
    public static float GARNISH_PERCENTAGE = 0.30f;

    // Early withdrawal
    public static float EARLY_WITHDRAWAL_PENALTY = 0.50f;

    public static void load() {
        try {
            JSONObject j = Global.getSettings().getMergedJSONForMod(SETTINGS_FILE, BankModPlugin.MOD_ID);
            LANGUAGE = j.optString("language", LANGUAGE);
            WAR_SURCHARGE = f(j, "warSurchargeMax", WAR_SURCHARGE);
            WAR_SURCHARGE_PER_WAR = f(j, "warSurchargePerWar", WAR_SURCHARGE_PER_WAR);
            OVERDUE_PENALTY_PER_MONTH = f(j, "overduePenaltyPerMonth", OVERDUE_PENALTY_PER_MONTH);
            MARKET_DISRUPTION_RATE_MODIFIER = f(j, "marketDisruptionModifier", MARKET_DISRUPTION_RATE_MODIFIER);
            CREDIT_SCORE_DEFAULT = i(j, "creditScoreDefault", CREDIT_SCORE_DEFAULT);
            SCORE_ON_TIME_PAYMENT = i(j, "scoreOnTimePayment", SCORE_ON_TIME_PAYMENT);
            SCORE_LOAN_PAYOFF = i(j, "scoreLoanPayoff", SCORE_LOAN_PAYOFF);
            SCORE_LATE_PAYMENT = i(j, "scoreLatePayment", SCORE_LATE_PAYMENT);
            SCORE_MISSED_PAYMENT = i(j, "scoreMissedPayment", SCORE_MISSED_PAYMENT);
            SCORE_DEFAULT = i(j, "scoreDefault", SCORE_DEFAULT);
            OVERDUE_PHASE1_DAYS = i(j, "overduePhase1Days", OVERDUE_PHASE1_DAYS);
            OVERDUE_PHASE2_DAYS = i(j, "overduePhase2Days", OVERDUE_PHASE2_DAYS);
            OVERDUE_PHASE3_DAYS = i(j, "overduePhase3Days", OVERDUE_PHASE3_DAYS);
            DEFAULT_THRESHOLD_DAYS = i(j, "defaultThresholdDays", DEFAULT_THRESHOLD_DAYS);
            COLLECTION_FLEET_FP_PER_DEBT = f(j, "collectionFleetCreditsPerFP", COLLECTION_FLEET_FP_PER_DEBT);
            COLLECTION_FLEET_MIN_FP = i(j, "collectionFleetMinFP", COLLECTION_FLEET_MIN_FP);
            COLLECTION_FLEET_MAX_FP = i(j, "collectionFleetMaxFP", COLLECTION_FLEET_MAX_FP);
            COLLECTION_FLEET_ESCALATION = f(j, "collectionFleetEscalation", COLLECTION_FLEET_ESCALATION);
            COLLECTION_FLEET_RETRY_DAYS = i(j, "collectionFleetRetryDays", COLLECTION_FLEET_RETRY_DAYS);
            COLLECTION_REFUSAL_REP_PENALTY = f(j, "collectionRefusalRepPenalty", COLLECTION_REFUSAL_REP_PENALTY);
            SHIP_SURRENDER_VALUE_FRACTION = f(j, "shipSurrenderValueFraction", SHIP_SURRENDER_VALUE_FRACTION);
            BANKRUPTCY_DEBT_REDUCTION = f(j, "bankruptcyDebtReduction", BANKRUPTCY_DEBT_REDUCTION);
            BANKRUPTCY_NO_LOANS_MONTHS = i(j, "bankruptcyNoLoansMonths", BANKRUPTCY_NO_LOANS_MONTHS);
            BANKRUPTCY_NO_INVEST_MONTHS = i(j, "bankruptcyNoInvestMonths", BANKRUPTCY_NO_INVEST_MONTHS);
            BANKRUPTCY_FULL_RECOVERY_MONTHS = i(j, "bankruptcyFullRecoveryMonths", BANKRUPTCY_FULL_RECOVERY_MONTHS);
            BANKRUPTCY_RELATION_PENALTY = f(j, "bankruptcyRelationPenalty", BANKRUPTCY_RELATION_PENALTY);
            BANKRUPTCY_COLONY_INCOME_PENALTY = f(j, "bankruptcyColonyIncomePenalty", BANKRUPTCY_COLONY_INCOME_PENALTY);
            BANKRUPTCY_SCORE_RECOVERY_PER_MONTH = i(j, "bankruptcyScoreRecoveryPerMonth", BANKRUPTCY_SCORE_RECOVERY_PER_MONTH);
            GARNISH_PERCENTAGE = f(j, "garnishPercentage", GARNISH_PERCENTAGE);
            EARLY_WITHDRAWAL_PENALTY = f(j, "earlyWithdrawalPenalty", EARLY_WITHDRAWAL_PENALTY);
            log.info("Bank of Starsector: Settings loaded from " + SETTINGS_FILE);
        } catch (Exception e) {
            log.warn("Bank of Starsector: Could not read " + SETTINGS_FILE + ", using defaults (" + e.getMessage() + ")");
        }
        // In-game settings (LunaLib), when available, take precedence over the JSON file.
        com.bankofstarsector.compat.LunaLibCompat.applyOverrides();
        Str.load(LANGUAGE);
        Str.applyDescriptions();
    }

    private static float f(JSONObject j, String key, float def) {
        return (float) j.optDouble(key, def);
    }

    private static int i(JSONObject j, String key, int def) {
        return j.optInt(key, def);
    }
}
