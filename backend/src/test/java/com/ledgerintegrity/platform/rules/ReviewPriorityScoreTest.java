package com.ledgerintegrity.platform.rules;

import com.ledgerintegrity.platform.rules.persist.ExceptionCase;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Guide §9.2/9.4: family caps, no double counting, cross-family corroboration. */
class ReviewPriorityScoreTest {

    private static final Map<Finding.Severity, Integer> W = Map.of(
            Finding.Severity.HIGH, 10, Finding.Severity.MEDIUM, 5, Finding.Severity.LOW, 2);
    private static final Map<RiskFamily, Integer> CAPS = Map.of(
            RiskFamily.RECONCILIATION, 25, RiskFamily.DETERMINISTIC, 25,
            RiskFamily.BEHAVIOUR_ACCESS, 15, RiskFamily.STATISTICAL, 10,
            RiskFamily.RELATIONSHIP, 15, RiskFamily.EVIDENCE, 10);

    private static ExceptionCase x(String ruleId, Finding.Severity sev) {
        return ExceptionCase.from(new Finding(ruleId, ruleId, sev, 0, "r", List.of("V1"), "s"),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID().toString(), Instant.now());
    }

    private static int total(Map<RiskFamily, Integer> scores) {
        return scores.values().stream().mapToInt(Integer::intValue).sum();
    }

    @Test
    void ruleFamilyMappingCoversEveryProducer() {
        assertEquals(RiskFamily.RECONCILIATION, RiskFamily.of("GS-01B"));
        assertEquals(RiskFamily.RECONCILIATION, RiskFamily.of("BK-04L"));
        assertEquals(RiskFamily.DETERMINISTIC, RiskFamily.of("JE-03"));
        assertEquals(RiskFamily.DETERMINISTIC, RiskFamily.of("PET-04"));
        assertEquals(RiskFamily.DETERMINISTIC, RiskFamily.of("VP-05"));
        assertEquals(RiskFamily.BEHAVIOUR_ACCESS, RiskFamily.of("MOT-01"));
        assertEquals(RiskFamily.BEHAVIOUR_ACCESS, RiskFamily.of("ATR-02"));
        assertEquals(RiskFamily.BEHAVIOUR_ACCESS, RiskFamily.of("STA-02"));
        assertEquals(RiskFamily.STATISTICAL, RiskFamily.of("BEN-01"));
        assertEquals(RiskFamily.STATISTICAL, RiskFamily.of("STA-01"));
        assertEquals(RiskFamily.RELATIONSHIP, RiskFamily.of("VP-01"));
        assertEquals(RiskFamily.DETERMINISTIC, RiskFamily.of("XX-99")); // unknown -> deterministic
    }

    @Test
    void correlatedStatisticalSignalsCannotExceedTheirFamilyCap() {
        // guide §9.2: four digit-shaped signals must not count as four independent facts
        var scores = ConsolidationService.familyScores(List.of(
                x("BEN-01", Finding.Severity.MEDIUM), x("STA-01", Finding.Severity.HIGH),
                x("STA-03", Finding.Severity.MEDIUM), x("STA-04", Finding.Severity.MEDIUM)),
                W, CAPS, 0);
        assertEquals(Map.of(RiskFamily.STATISTICAL, 10), scores); // 25 raw -> capped at 10
        assertEquals(10, total(scores));
    }

    @Test
    void independentFamiliesCorroborateToAHigherTotal() {
        // guide §9.4 case D shape: statistical + deterministic + reconciliation + evidence
        var scores = ConsolidationService.familyScores(List.of(
                x("BEN-01", Finding.Severity.MEDIUM),   // statistical 5
                x("JE-09", Finding.Severity.HIGH),      // deterministic 10
                x("GS-01V", Finding.Severity.MEDIUM),   // reconciliation 5
                x("GS-01B", Finding.Severity.MEDIUM)),  // reconciliation 5
                W, CAPS, 1);                            // one overdue evidence request = 5
        assertEquals(5, scores.get(RiskFamily.STATISTICAL));
        assertEquals(10, scores.get(RiskFamily.DETERMINISTIC));
        assertEquals(10, scores.get(RiskFamily.RECONCILIATION));
        assertEquals(5, scores.get(RiskFamily.EVIDENCE));
        assertEquals(30, total(scores));
    }

    @Test
    void evidenceFamilyCapsAtItsLimitAndTotalNeverExceeds100() {
        var scores = ConsolidationService.familyScores(List.of(), W, CAPS, 7); // 35 raw evidence
        assertEquals(Map.of(RiskFamily.EVIDENCE, 10), scores);

        // saturate every family: total must land exactly on the cap structure sum (100)
        var saturated = ConsolidationService.familyScores(List.of(
                x("GS-01B", Finding.Severity.HIGH), x("GS-01V", Finding.Severity.HIGH), x("GS-02B", Finding.Severity.HIGH),
                x("JE-03", Finding.Severity.HIGH), x("JE-05", Finding.Severity.HIGH), x("PET-01", Finding.Severity.HIGH),
                x("MOT-01", Finding.Severity.HIGH), x("MOT-02", Finding.Severity.HIGH),
                x("BEN-01", Finding.Severity.HIGH), x("STA-01", Finding.Severity.HIGH),
                x("VP-01", Finding.Severity.HIGH), x("VP-02", Finding.Severity.HIGH)),
                W, CAPS, 3);
        assertEquals(100, total(saturated));
    }
}
