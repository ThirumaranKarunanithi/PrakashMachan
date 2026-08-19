package com.ledgerintegrity.platform.rules;

import java.util.List;

/**
 * One rule hit, before it becomes a persisted exception. Carries the plain-language
 * explanation and source traceability the BRD requires (explain before scoring).
 */
public record Finding(
        String ruleId,
        String ruleName,
        Severity severity,
        long exposurePaise,
        String reason,
        /** vouchers involved, in a stable order — also the identity of the finding */
        List<String> voucherIds,
        String sourceRefs
) {
    public enum Severity { HIGH, MEDIUM, LOW }
}
