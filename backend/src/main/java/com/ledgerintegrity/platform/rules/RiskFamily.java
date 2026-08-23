package com.ledgerintegrity.platform.rules;

/**
 * The six method families of the Review Priority Score (integrity-core guide §9.1,
 * BRD §17). Related signals are capped INSIDE their family so four digit tests can
 * never masquerade as four independent facts; priority rises only when independent
 * families corroborate the same records.
 */
public enum RiskFamily {

    /** Books vs an independent record: GST returns, bank statement, control totals. */
    RECONCILIATION,
    /** Deterministic rule facts: duplicates, splits, backdating, post-close, period-end. */
    DETERMINISTIC,
    /** Who and when: privileged users, SoD conflicts, unusual access, sequence risk. */
    BEHAVIOUR_ACCESS,
    /** Statistical shape: Benford, peer outliers, bunching, trend — capped lowest by design. */
    STATISTICAL,
    /** Shared identifiers and linked entities: vendors, bank accounts, users. */
    RELATIONSHIP,
    /** Missing, overdue or rejected supporting evidence. */
    EVIDENCE;

    /** Deterministic ruleId -> family mapping (versioned with the rule pack). */
    public static RiskFamily of(String ruleId) {
        if (ruleId == null) return DETERMINISTIC;
        if (ruleId.startsWith("GS-") || ruleId.startsWith("BK-")) return RECONCILIATION;
        // every STA rule is statistical except STA-02, whose signal is WHO acted
        if (ruleId.startsWith("BEN")
                || (ruleId.startsWith("STA-") && !ruleId.equals("STA-02"))) return STATISTICAL;
        if (ruleId.startsWith("MOT") || ruleId.startsWith("ATR")
                || ruleId.equals("STA-02")   // rare user-account = access pattern
                || ruleId.equals("VP-03")    // new vendor immediately active
                || ruleId.equals("VP-06")) { // bank change then payment: sequence risk
            return BEHAVIOUR_ACCESS;
        }
        if (ruleId.equals("VP-01") || ruleId.equals("VP-02")) return RELATIONSHIP; // shared identifiers
        return DETERMINISTIC; // JE-*, PET-*, VP-04 duplicates, VP-05 splits, unknown prefixes
    }
}
