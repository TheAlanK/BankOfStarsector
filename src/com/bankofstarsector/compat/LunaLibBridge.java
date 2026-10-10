package com.bankofstarsector.compat;

import com.bankofstarsector.core.BankModPlugin;
import com.bankofstarsector.core.BankSettings;
import lunalib.lunaSettings.LunaSettings;
import lunalib.lunaSettings.LunaSettingsListener;

/** Direct LunaLib calls. Only touched when {@link LunaLibCompat#isAvailable()} is true. */
final class LunaLibBridge {

    private static final String M = BankModPlugin.MOD_ID;

    private LunaLibBridge() {}

    static void applyOverrides() {
        String lang = LunaSettings.getString(M, "bos_language");
        if (lang != null && !lang.isEmpty()) BankSettings.LANGUAGE = lang;
        BankSettings.WAR_SURCHARGE_PER_WAR = f("bos_warSurchargePerWar", BankSettings.WAR_SURCHARGE_PER_WAR);
        BankSettings.WAR_SURCHARGE = f("bos_warSurchargeMax", BankSettings.WAR_SURCHARGE);
        BankSettings.OVERDUE_PENALTY_PER_MONTH = f("bos_overduePenaltyPerMonth", BankSettings.OVERDUE_PENALTY_PER_MONTH);
        BankSettings.MARKET_DISRUPTION_RATE_MODIFIER = f("bos_marketDisruptionModifier", BankSettings.MARKET_DISRUPTION_RATE_MODIFIER);
        BankSettings.OVERDUE_PHASE1_DAYS = i("bos_overduePhase1Days", BankSettings.OVERDUE_PHASE1_DAYS);
        BankSettings.OVERDUE_PHASE2_DAYS = i("bos_overduePhase2Days", BankSettings.OVERDUE_PHASE2_DAYS);
        BankSettings.DEFAULT_THRESHOLD_DAYS = i("bos_defaultThresholdDays", BankSettings.DEFAULT_THRESHOLD_DAYS);
        BankSettings.COLLECTION_FLEET_MIN_FP = i("bos_collectionFleetMinFP", BankSettings.COLLECTION_FLEET_MIN_FP);
        BankSettings.COLLECTION_FLEET_MAX_FP = i("bos_collectionFleetMaxFP", BankSettings.COLLECTION_FLEET_MAX_FP);
        BankSettings.COLLECTION_FLEET_ESCALATION = f("bos_collectionFleetEscalation", BankSettings.COLLECTION_FLEET_ESCALATION);
        BankSettings.GARNISH_PERCENTAGE = f("bos_garnishPercentage", BankSettings.GARNISH_PERCENTAGE);
        BankSettings.SHIP_SURRENDER_VALUE_FRACTION = f("bos_shipSurrenderValueFraction", BankSettings.SHIP_SURRENDER_VALUE_FRACTION);
        BankSettings.BANKRUPTCY_DEBT_REDUCTION = f("bos_bankruptcyDebtReduction", BankSettings.BANKRUPTCY_DEBT_REDUCTION);
        BankSettings.BANKRUPTCY_NO_LOANS_MONTHS = i("bos_bankruptcyNoLoansMonths", BankSettings.BANKRUPTCY_NO_LOANS_MONTHS);
        BankSettings.BANKRUPTCY_NO_INVEST_MONTHS = i("bos_bankruptcyNoInvestMonths", BankSettings.BANKRUPTCY_NO_INVEST_MONTHS);
        BankSettings.EARLY_WITHDRAWAL_PENALTY = f("bos_earlyWithdrawalPenalty", BankSettings.EARLY_WITHDRAWAL_PENALTY);
        BankSettings.BUILDER_MAX_AMOUNT = f("bos_builderMaxAmount", BankSettings.BUILDER_MAX_AMOUNT);
        BankSettings.BUILDER_TERM_MONTHS = i("bos_builderTermMonths", BankSettings.BUILDER_TERM_MONTHS);
        BankSettings.BUILDER_RATE = f("bos_builderRate", BankSettings.BUILDER_RATE);
        // Keep the escalation ladder ordered even if the player sets odd values.
        if (BankSettings.OVERDUE_PHASE2_DAYS <= BankSettings.OVERDUE_PHASE1_DAYS) {
            BankSettings.OVERDUE_PHASE2_DAYS = BankSettings.OVERDUE_PHASE1_DAYS + 1;
        }
        if (BankSettings.DEFAULT_THRESHOLD_DAYS <= BankSettings.OVERDUE_PHASE2_DAYS) {
            BankSettings.DEFAULT_THRESHOLD_DAYS = BankSettings.OVERDUE_PHASE2_DAYS + 1;
        }
        if (BankSettings.BUILDER_MAX_AMOUNT < BankSettings.BUILDER_MIN_AMOUNT) {
            BankSettings.BUILDER_MAX_AMOUNT = BankSettings.BUILDER_MIN_AMOUNT;
        }
        if (BankSettings.COLLECTION_FLEET_MAX_FP < BankSettings.COLLECTION_FLEET_MIN_FP) {
            BankSettings.COLLECTION_FLEET_MAX_FP = BankSettings.COLLECTION_FLEET_MIN_FP;
        }
    }

    static void addListener() {
        LunaSettings.addSettingsListener(new LunaSettingsListener() {
            public void settingsChanged(String modId) {
                if (M.equals(modId)) BankSettings.load();
            }
        });
    }

    private static float f(String id, float def) {
        Float v = LunaSettings.getFloat(M, id);
        return v != null ? v : def;
    }

    private static int i(String id, int def) {
        Integer v = LunaSettings.getInt(M, id);
        return v != null ? v : def;
    }
}
