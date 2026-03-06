package com.bankofstarsector.core;

import org.apache.log4j.Logger;

public class BankSettings {

    private static final Logger log = Logger.getLogger(BankSettings.class);

    // Interest rate modifiers
    public static float WAR_SURCHARGE = 0.25f;
    public static float MAX_CREDIT_DISCOUNT = 0.30f;
    public static float OVERDUE_PENALTY_PER_MONTH = 0.50f;
    public static float MARKET_DISRUPTION_RATE_MODIFIER = 0.10f;

    // Credit score
    public static int CREDIT_SCORE_MIN = 300;
    public static int CREDIT_SCORE_MAX = 850;
    public static int CREDIT_SCORE_DEFAULT = 550;
    public static int SCORE_ON_TIME_PAYMENT = 5;
    public static int SCORE_LOAN_PAYOFF = 15;
    public static int SCORE_LATE_PAYMENT = -15;
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
        log.info("Bank of Starsector: Settings loaded (using defaults).");
    }
}
