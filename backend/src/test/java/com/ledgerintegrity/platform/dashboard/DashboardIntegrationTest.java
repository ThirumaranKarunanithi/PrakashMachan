package com.ledgerintegrity.platform.dashboard;

import com.ledgerintegrity.platform.engagement.Engagement;
import com.ledgerintegrity.platform.engagement.EngagementRepository;
import com.ledgerintegrity.platform.evidence.EvidenceService;
import com.ledgerintegrity.platform.importer.ImportService.SourceFile;
import com.ledgerintegrity.platform.importer.MappingProfileRepository;
import com.ledgerintegrity.platform.importer.persist.EngagementImportService;
import com.ledgerintegrity.platform.rules.RuleEngineService;
import com.ledgerintegrity.platform.rules.RuleParams;
import com.ledgerintegrity.platform.rules.persist.ExceptionCase;
import com.ledgerintegrity.platform.rules.persist.ExceptionCaseRepository;
import com.ledgerintegrity.platform.workpaper.WorkpaperService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dashtestdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@EnabledIf("sampleDataPresent")
class DashboardIntegrationTest {

    private static final Path SAMPLE = Path.of("..", "phase0", "sample-data");

    static boolean sampleDataPresent() {
        return Files.exists(SAMPLE.resolve("general_ledger.csv"));
    }

    @Autowired EngagementRepository engagements;
    @Autowired EngagementImportService glImport;
    @Autowired RuleEngineService engine;
    @Autowired ExceptionCaseRepository exceptions;
    @Autowired EvidenceService evidence;
    @Autowired WorkpaperService workpapers;
    @Autowired MappingProfileRepository profiles;
    @Autowired DashboardService dashboard;

    @Test
    void portfolioRanksOpenRiskAndExplorerBreaksItDown() throws IOException {
        UUID firmId = UUID.randomUUID();
        Engagement e = new Engagement(UUID.randomUUID(), firmId, "CLIENT-A",
                LocalDate.of(2024, 4, 1), LocalDate.of(2025, 3, 31), LocalDate.of(2025, 3, 31), Instant.now());
        engagements.save(e);
        glImport.importInto(e.getId(),
                new SourceFile("general_ledger.csv", Files.readAllBytes(SAMPLE.resolve("general_ledger.csv"))),
                new SourceFile("trial_balance.csv", Files.readAllBytes(SAMPLE.resolve("trial_balance.csv"))),
                profiles.find("client-a-gl").orElseThrow());
        engine.run(e.getId(), RuleParams.defaults().withPrivilegedUsers(Set.of("ADMIN-1", "MGR-1")));

        // one confirmed misstatement, one overdue evidence request, one draft workpaper
        var all = exceptions.findByEngagementIdOrderBySeverityAscExposurePaiseDesc(e.getId());
        ExceptionCase confirmed = all.get(0); // the 49L JE-03
        confirmed.decide(ExceptionCase.Status.CONFIRMED, "Provision unsupported; management agreed to reverse.",
                "P. Partner", Instant.now());
        exceptions.save(confirmed);
        evidence.createRequest(all.get(1).getId(), "Reversal justification", null, "A. Associate",
                LocalDate.now().minusDays(2));
        workpapers.generate(e.getId());

        var portfolio = dashboard.portfolio(firmId);
        assertEquals(1, portfolio.size());
        var row = portfolio.get(0);
        assertEquals("CLIENT-A", row.clientName());
        assertEquals(3008, row.populationCount());
        // 26 raised (pack 0.5.0 adds the STA-01 outlier), 1 CONFIRMED closed it;
        // the evidence request moved another to INFO_REQUIRED (still open)
        assertEquals(25, row.openExceptions());
        assertTrue(row.openHigh() >= 3);
        assertEquals(1, row.confirmedExceptions());
        assertEquals(49_00_000_00L, row.confirmedExposurePaise()); // RSK-005: kept apart
        assertTrue(row.estimatedExposurePaise() > 0);
        assertNotEquals(row.estimatedExposurePaise(), row.confirmedExposurePaise());
        // exposure de-duplicates per case: the roll-up must be BELOW the naive per-signal sum,
        // because several rules flag the same vouchers (e.g. the 49L pair carries 8+ signals)
        long naiveSum = all.stream()
                .filter(x -> x.getStatus() != ExceptionCase.Status.CONFIRMED && x.getId() != confirmed.getId())
                .mapToLong(ExceptionCase::getExposurePaise).sum();
        assertTrue(row.estimatedExposurePaise() < naiveSum,
                "roll-up " + row.estimatedExposurePaise() + " should dedupe below naive sum " + naiveSum);
        assertEquals(1, row.overdueEvidence());
        assertEquals("v1 DRAFT", row.workpaperStatus());
        assertTrue(row.openCases() > 0 && row.openCases() <= row.totalCases());

        // explorer: open risk broken down by rule / user / month / account
        var explorer = dashboard.explorer(e.getId());
        assertFalse(explorer.byRule().isEmpty());
        assertTrue(explorer.byRule().stream().anyMatch(s -> s.key().startsWith("JE-09")));
        assertTrue(explorer.byUser().stream().anyMatch(s -> s.key().equals("ADMIN-1")));
        assertTrue(explorer.byMonth().stream().anyMatch(s -> s.key().equals("2025-03")));
        assertFalse(explorer.byAccount().isEmpty());
        // the confirmed exception no longer drives "open" risk
        assertTrue(explorer.byRule().stream().noneMatch(s ->
                s.key().startsWith("JE-03") && s.count() == 3)); // one of the three JE-03s is now closed
    }
}
