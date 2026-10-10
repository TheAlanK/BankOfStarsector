package com.bankofstarsector.banking;

import com.bankofstarsector.core.BankSettings;

/** Fleet insurance policies, underwritten by House Varenne for the Confederation (see LORE.md). */
public enum InsurancePlan {

    /** 60% of a lost ship's base value, 10k deductible per ship. */
    STANDARD(0.60f, 10000f),
    /** 80% of a lost ship's base value, 5k deductible per ship, dearer. */
    COMPREHENSIVE(0.80f, 5000f);

    public final float coverage;
    public final float deductible;

    InsurancePlan(float coverage, float deductible) {
        this.coverage = coverage;
        this.deductible = deductible;
    }

    /** Monthly premium rate on the insured value, before risk adjustments. */
    public float getBaseRate() {
        return this == STANDARD ? BankSettings.INSURANCE_RATE_STANDARD : BankSettings.INSURANCE_RATE_COMPREHENSIVE;
    }

    public String getDisplayName() {
        return com.bankofstarsector.core.Str.get("insurance." + name() + ".name");
    }
}
