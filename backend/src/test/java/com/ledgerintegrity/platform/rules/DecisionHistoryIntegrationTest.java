package com.ledgerintegrity.platform.rules;

import com.ledgerintegrity.platform.engagement.Engagement;
import com.ledgerintegrity.platform.engagement.EngagementRepository;
import com.ledgerintegrity.platform.evidence.EvidenceService;
import com.ledgerintegrity.platform.rules.persist.ExceptionCase;
import com.ledgerintegrity.platform.rules.persist.ExceptionCaseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * QA P1: an evidence request's auto-transition to INFO_REQUIRED must not erase the
 * auditor's prior decision note — every status change lands in append-only history.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:decisionhistorydb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class DecisionHistoryIntegrationTest {

    @Autowired EngagementRepository engagements;
    @Autowired ExceptionCaseRepository exceptions;
    @Autowired ExceptionDecisionService decisions;
    @Autowired EvidenceService evidence;

    @Test
    void autoTransitionPreservesPriorReasoningInHistory() {
        Engagement e = new Engagement(UUID.randomUUID(), UUID.randomUUID(), "HIST-CLIENT",
                LocalDate.of(2024, 4, 1), LocalDate.of(2025, 3, 31), LocalDate.of(2025, 3, 31), Instant.now());
        engagements.save(e);
        ExceptionCase x = ExceptionCase.from(
                new Finding("JE-09", "Reversal without original", Finding.Severity.MEDIUM, 1_00_000_00L,
                        "Voucher JRN-1 reverses a voucher that is not in the population.",
                        List.of("JRN-1"), "general_ledger.csv:42"),
                e.getId(), UUID.randomUUID(), "hist-hash-1", Instant.now());
        exceptions.save(x);

        // auditor records an explicit review note
        decisions.transition(x, ExceptionCase.Status.UNDER_REVIEW,
                "Reversal reviewed, awaiting supporting approval.", "A. Auditor <a@firm>");

        // workflow auto-transition on evidence request — previously clobbered the note
        evidence.createRequest(x.getId(), "Supporting approval", null, "A. Auditor <a@firm>",
                LocalDate.now().plusDays(7));

        ExceptionCase after = exceptions.findById(x.getId()).orElseThrow();
        assertEquals(ExceptionCase.Status.INFO_REQUIRED, after.getStatus());

        List<com.ledgerintegrity.platform.rules.persist.ExceptionDecision> history =
                decisions.historyOf(x.getId());
        assertEquals(2, history.size());
        assertEquals("Reversal reviewed, awaiting supporting approval.", history.get(0).getNote());
        assertEquals(ExceptionCase.Status.UNDER_REVIEW, history.get(0).getToStatus());
        assertEquals("A. Auditor <a@firm>", history.get(0).getDecidedBy());
        assertEquals(ExceptionCase.Status.UNDER_REVIEW, history.get(1).getFromStatus());
        assertEquals(ExceptionCase.Status.INFO_REQUIRED, history.get(1).getToStatus());
        assertTrue(history.get(1).getNote().startsWith("Evidence requested:"));
    }
}
