package com.ledgerintegrity.platform.ai;

import com.ledgerintegrity.platform.api.AiController;
import com.ledgerintegrity.platform.engagement.Engagement;
import com.ledgerintegrity.platform.engagement.EngagementRepository;
import com.ledgerintegrity.platform.rules.Finding;
import com.ledgerintegrity.platform.rules.persist.ExceptionCase;
import com.ledgerintegrity.platform.rules.persist.ExceptionCaseRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guide §12 guardrails: without a configured key the AI layer is OFF and says so
 * clearly; prompts carry the neutral-wording rules and only engine-computed facts.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ailayerdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class AiLayerIntegrationTest {

    @Autowired EngagementRepository engagements;
    @Autowired ExceptionCaseRepository exceptions;
    @Autowired AiExplanationService ai;
    @Autowired AiController controller;
    @Autowired com.ledgerintegrity.platform.auth.persist.AppUserRepository users;

    @Test
    void withoutAKeyTheLayerIsOffAndTheErrorTellsTheAdminWhatToDo() {
        assertFalse(ai.enabled());

        UUID firmId = UUID.randomUUID();
        users.save(new com.ledgerintegrity.platform.auth.persist.AppUser(UUID.randomUUID(), firmId,
                "p@ai.test", "x", "AI Partner",
                com.ledgerintegrity.platform.auth.persist.AppUser.Role.PARTNER, Instant.now()));
        var auth = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                "p@ai.test", null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("ROLE_PARTNER")));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);

        Engagement e = new Engagement(UUID.randomUUID(), firmId, "AI-CLIENT",
                LocalDate.of(2024, 4, 1), LocalDate.of(2025, 3, 31), LocalDate.of(2025, 3, 31), Instant.now());
        engagements.save(e);
        ExceptionCase x = ExceptionCase.from(
                new Finding("JE-03", "Post-close / backdated entry", Finding.Severity.HIGH, 49_00_000_00L,
                        "Voucher JRN-90001 was created after close.", List.of("JRN-90001"), "gl.csv:2961"),
                e.getId(), UUID.randomUUID(), "ai-hash-1", Instant.now());
        exceptions.save(x);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> controller.explain(x.getId(), false));
        assertEquals(503, ex.getStatusCode().value());
        assertTrue(ex.getReason().contains("ANTHROPIC_API_KEY"));

        // the same clean state, machine-readable
        var status = controller.status();
        assertEquals(false, status.get("enabled"));
    }

    @Test
    void promptsCarryTheGuardrailsAndOnlyEngineFacts() {
        assertTrue(AiExplanationService.SYSTEM_PROMPT.contains("Never state or imply"));
        assertTrue(AiExplanationService.SYSTEM_PROMPT.contains("not a conclusion"));
        assertTrue(AiExplanationService.SYSTEM_PROMPT.contains("Do not invent"));

        ExceptionCase x = ExceptionCase.from(
                new Finding("STA-01", "Peer-group amount outlier", Finding.Severity.MEDIUM, 5_42_800_00L,
                        "Modified Z-score 2.13 against peer group.", List.of("PUR-01145"), "gl.csv:12"),
                UUID.randomUUID(), UUID.randomUUID(), "ai-hash-2", Instant.now());
        String facts = AiExplanationService.exceptionFacts(x);
        assertTrue(facts.contains("STA-01"));
        assertTrue(facts.contains("STATISTICAL"));      // the family travels with the fact
        assertTrue(facts.contains("PUR-01145"));
        assertTrue(facts.contains("gl.csv:12"));        // source lineage included
        assertTrue(facts.contains("542,800.00"));
    }
}
