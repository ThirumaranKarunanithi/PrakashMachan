package com.ledgerintegrity.platform.rules;

import com.ledgerintegrity.platform.engagement.Engagement;
import com.ledgerintegrity.platform.engagement.EngagementRepository;
import com.ledgerintegrity.platform.importer.ImportService.SourceFile;
import com.ledgerintegrity.platform.importer.MappingProfile;
import com.ledgerintegrity.platform.importer.MappingProfileRepository;
import com.ledgerintegrity.platform.importer.persist.EngagementImportService;
import com.ledgerintegrity.platform.rules.persist.ExceptionCase;
import com.ledgerintegrity.platform.rules.persist.ExceptionCaseRepository;
import com.ledgerintegrity.platform.rules.persist.InvestigationCase;
import com.ledgerintegrity.platform.rules.persist.InvestigationCaseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Rule engine against the Phase 0 CLIENT-A population. The expected hits are the
 * seeded anomalies documented in phase0/sample-data/SEEDED_ANOMALIES.md (A1-A3).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:ruletestdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@EnabledIf("sampleDataPresent")
class RuleEngineIntegrationTest {

    private static final Path SAMPLE = Path.of("..", "phase0", "sample-data");

    static boolean sampleDataPresent() {
        return Files.exists(SAMPLE.resolve("general_ledger.csv"));
    }

    @Autowired EngagementRepository engagements;
    @Autowired EngagementImportService importService;
    @Autowired RuleEngineService engine;
    @Autowired ExceptionCaseRepository exceptions;
    @Autowired InvestigationCaseRepository cases;
    @Autowired MappingProfileRepository profiles;

    @Test
    void findsSeededAnomaliesAndReRunsAreIdempotent() throws IOException {
        Engagement e = new Engagement(UUID.randomUUID(), UUID.randomUUID(), "CLIENT-A",
                LocalDate.of(2024, 4, 1), LocalDate.of(2025, 3, 31), LocalDate.of(2025, 3, 31), Instant.now());
        engagements.save(e);
        MappingProfile profile = profiles.find("client-a-gl").orElseThrow();
        importService.importInto(e.getId(),
                new SourceFile("general_ledger.csv", Files.readAllBytes(SAMPLE.resolve("general_ledger.csv"))),
                new SourceFile("trial_balance.csv", Files.readAllBytes(SAMPLE.resolve("trial_balance.csv"))),
                profile);

        RuleParams params = RuleParams.defaults().withPrivilegedUsers(Set.of("ADMIN-1", "MGR-1"));
        var result = engine.run(e.getId(), params);

        List<ExceptionCase> all = exceptions.findByEngagementIdOrderBySeverityAscExposurePaiseDesc(e.getId());
        assertEquals(all.size(), result.created().size());

        // A1/A2: three post-close backdated journals, all by privileged users -> HIGH
        var je03 = all.stream().filter(x -> x.getRuleId().equals("JE-03")).toList();
        assertEquals(3, je03.size());
        assertTrue(je03.stream().allMatch(x -> x.getSeverity() == Finding.Severity.HIGH));
        assertTrue(je03.stream().anyMatch(x -> x.getVoucherIds().contains("JRN-90001")
                && x.getExposurePaise() == 49_00_000_00L));

        // A1: the Rs 49 lakh reversal pair
        var je09 = all.stream().filter(x -> x.getRuleId().equals("JE-09")).toList();
        assertEquals(1, je09.size());
        assertEquals("JRN-90001 JRN-90002", je09.get(0).getVoucherIds());

        // A3 + A1/A2 side-effects: exact round manual journals
        var je07 = all.stream().filter(x -> x.getRuleId().equals("JE-07")).toList();
        assertEquals(7, je07.size()); // 90001, 90002, 90003, 90010..90013

        // A3: the four "adjustment" journals
        var je10 = all.stream().filter(x -> x.getRuleId().equals("JE-10")).toList();
        assertEquals(4, je10.size());

        // VP-05 fires on GL payments even without vendor master: the seeded Prakash
        // Machinery split (A4) — three payments just below Rs 50,000 in 5 days
        var vp05 = all.stream().filter(x -> x.getRuleId().equals("VP-05")).toList();
        assertEquals(1, vp05.size());
        assertTrue(vp05.get(0).getReason().contains("Prakash Machinery"));

        // every exception starts NEW with lineage attached (BRD §3.3 / DAT-005)
        assertTrue(all.stream().allMatch(x -> x.getStatus() == ExceptionCase.Status.NEW));
        assertTrue(all.stream().allMatch(x -> x.getSourceRefs().contains("general_ledger.csv:")));

        // run parameters are snapshotted for reproducibility (JET-007)
        assertEquals("mvp-pack-0.5.0", result.run().getPackVersion());
        assertTrue(result.run().getParamsJson().contains("privilegedUsers"));

        // XC-05: related exceptions consolidate into one case per underlying event.
        // 26 exceptions -> 8 cases (the JE-06 pair finding bridges the 90003 and 90004 cases;
        // pack 0.5.0 adds the STA-01 peer-group outlier PUR-01145 as its own case);
        // JRN-90001/90002 (9 signals) is the top-priority case.
        List<InvestigationCase> consolidated = result.cases();
        assertEquals(8, consolidated.size());
        InvestigationCase top = consolidated.get(0);
        assertEquals("JRN-90001 JRN-90002", top.getVoucherIds());
        assertEquals(Finding.Severity.HIGH, top.getSeverity());
        // Score v2 (guide 9): family-capped. DETERMINISTIC raw 45 (JE-03 H, JE-09 H,
        // JE-07 x2, PET-04, JE-06 x2) caps at 25; BEHAVIOUR (MOT-01 x2) adds 10 -> 35.
        assertEquals(35, top.getPriorityScore());
        assertTrue(top.getFamilyScoresJson().contains("\"DETERMINISTIC\":{\"score\":25,\"cap\":25}"));
        assertTrue(top.getFamilyScoresJson().contains("\"BEHAVIOUR_ACCESS\":{\"score\":10,\"cap\":15}"));
        assertEquals(49_00_000_00L, top.getExposurePaise()); // max member, not sum
        long topMembers = all.stream().filter(x -> top.getId().equals(x.getCaseId())).count();
        assertEquals(9, topMembers); // JE-03 + JE-09 + JE-07 x2 + MOT-01 x2 + PET-04 + JE-06 x2
        // every exception belongs to exactly one case
        assertTrue(all.stream().allMatch(x -> x.getCaseId() != null));

        // re-run: nothing new, everything skipped as already raised, case set stable
        var second = engine.run(e.getId(), params);
        assertEquals(0, second.run().getExceptionsCreated());
        assertEquals(all.size(), second.run().getSkippedExisting());
        assertEquals(all.size(), exceptions.countByEngagementId(e.getId()));
        assertEquals(8, cases.countByEngagementId(e.getId()));
        // case identity survives the re-run (review history stays attached)
        assertTrue(second.cases().stream().anyMatch(c -> c.getId().equals(top.getId())));
    }
}
