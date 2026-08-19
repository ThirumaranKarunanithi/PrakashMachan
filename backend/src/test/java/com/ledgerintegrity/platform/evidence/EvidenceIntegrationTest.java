package com.ledgerintegrity.platform.evidence;

import com.ledgerintegrity.platform.engagement.Engagement;
import com.ledgerintegrity.platform.engagement.EngagementRepository;
import com.ledgerintegrity.platform.evidence.persist.EvidenceRequest;
import com.ledgerintegrity.platform.importer.ImportService.SourceFile;
import com.ledgerintegrity.platform.importer.MappingProfileRepository;
import com.ledgerintegrity.platform.importer.persist.EngagementImportService;
import com.ledgerintegrity.platform.rules.RuleEngineService;
import com.ledgerintegrity.platform.rules.RuleParams;
import com.ledgerintegrity.platform.rules.persist.ExceptionCase;
import com.ledgerintegrity.platform.rules.persist.ExceptionCaseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:evtestdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@EnabledIf("sampleDataPresent")
class EvidenceIntegrationTest {

    private static final Path SAMPLE = Path.of("..", "phase0", "sample-data");

    static boolean sampleDataPresent() {
        return Files.exists(SAMPLE.resolve("general_ledger.csv"));
    }

    @Autowired EngagementRepository engagements;
    @Autowired EngagementImportService glImport;
    @Autowired RuleEngineService engine;
    @Autowired ExceptionCaseRepository exceptions;
    @Autowired EvidenceService service;
    @Autowired MappingProfileRepository profiles;

    @Test
    void evidenceLifecycleWithVersioningAndDocumentedDecisions() throws IOException {
        Engagement e = new Engagement(UUID.randomUUID(), UUID.randomUUID(), "CLIENT-A",
                LocalDate.of(2024, 4, 1), LocalDate.of(2025, 3, 31), LocalDate.of(2025, 3, 31), Instant.now());
        engagements.save(e);
        glImport.importInto(e.getId(),
                new SourceFile("general_ledger.csv", Files.readAllBytes(SAMPLE.resolve("general_ledger.csv"))),
                new SourceFile("trial_balance.csv", Files.readAllBytes(SAMPLE.resolve("trial_balance.csv"))),
                profiles.find("client-a-gl").orElseThrow());
        engine.run(e.getId(), RuleParams.defaults().withPrivilegedUsers(Set.of("ADMIN-1", "MGR-1")));

        ExceptionCase target = exceptions.findByEngagementIdOrderBySeverityAscExposurePaiseDesc(e.getId()).get(0);
        assertEquals(ExceptionCase.Status.NEW, target.getStatus());

        // CDC-001: request created from the exception; exception moves to INFO_REQUIRED
        var request = service.createRequest(target.getId(),
                "Provision computation for JRN-90001", "Upload the supporting calculation and approval.",
                "A. Associate", LocalDate.now().minusDays(1)); // already overdue
        assertEquals(EvidenceRequest.Status.OPEN, request.getStatus());
        assertTrue(request.isOverdue(LocalDate.now()));
        assertEquals(ExceptionCase.Status.INFO_REQUIRED,
                exceptions.findById(target.getId()).orElseThrow().getStatus());

        // decision before any response is rejected
        assertThrows(IllegalStateException.class, () ->
                service.decide(request.getId(), EvidenceRequest.Status.ACCEPTED, "ok", "M. Manager"));

        // CDC-004: uploads are versioned with checksums and uploader identity
        var v1 = service.upload(request.getId(), "calc.xlsx", "application/octet-stream",
                "first draft".getBytes(StandardCharsets.UTF_8), "client-user");
        var v2 = service.upload(request.getId(), "calc-final.xlsx", "application/octet-stream",
                "final version".getBytes(StandardCharsets.UTF_8), "client-user");
        assertEquals(1, v1.getVersion());
        assertEquals(2, v2.getVersion());
        assertNotEquals(v1.getSha256(), v2.getSha256());

        // CDC-006: rejection recorded with reason; client may respond again
        var rejected = service.decide(request.getId(), EvidenceRequest.Status.REJECTED,
                "Calculation lacks approval sign-off.", "M. Manager");
        assertEquals(EvidenceRequest.Status.REJECTED, rejected.getStatus());
        var afterReject = service.upload(request.getId(), "calc-approved.xlsx", "application/octet-stream",
                "final with approval".getBytes(StandardCharsets.UTF_8), "client-user");
        assertEquals(3, afterReject.getVersion());

        // decision without a note is rejected
        assertThrows(IllegalArgumentException.class, () ->
                service.decide(request.getId(), EvidenceRequest.Status.ACCEPTED, " ", "M. Manager"));

        var accepted = service.decide(request.getId(), EvidenceRequest.Status.ACCEPTED,
                "Approved calculation received; matter resolved.", "M. Manager");
        assertEquals(EvidenceRequest.Status.ACCEPTED, accepted.getStatus());

        // acceptance never auto-closes the exception — human judgement is final (BRD §2.3)
        assertEquals(ExceptionCase.Status.INFO_REQUIRED,
                exceptions.findById(target.getId()).orElseThrow().getStatus());

        // an accepted request cannot take more uploads
        assertThrows(IllegalStateException.class, () ->
                service.upload(request.getId(), "late.pdf", null, "x".getBytes(StandardCharsets.UTF_8), "client-user"));

        assertEquals(3, service.documentsOf(request.getId()).size());
    }
}
