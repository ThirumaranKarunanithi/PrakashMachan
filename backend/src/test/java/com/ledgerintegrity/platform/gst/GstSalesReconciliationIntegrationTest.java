package com.ledgerintegrity.platform.gst;

import com.ledgerintegrity.platform.engagement.Engagement;
import com.ledgerintegrity.platform.engagement.EngagementRepository;
import com.ledgerintegrity.platform.gst.persist.GstMatchResult.Category;
import com.ledgerintegrity.platform.rules.persist.ExceptionCaseRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GS-02/GS-03 against the Phase 0 CLIENT-A outward-supply files. Expected classification
 * from seeded anomalies A15-A18: 375 matched, 2 value mismatches, 3 books-only,
 * 2 GSTR-1-only, and one GSTR-3B period short by Rs 50,000.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:gstsalestestdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@EnabledIf("sampleDataPresent")
class GstSalesReconciliationIntegrationTest {

    private static final Path SAMPLE = Path.of("..", "phase0", "sample-data");

    static boolean sampleDataPresent() {
        return Files.exists(SAMPLE.resolve("gstr1.csv"));
    }

    @Autowired EngagementRepository engagements;
    @Autowired GstImportService importService;
    @Autowired GstReconciliationService reconciliation;
    @Autowired ExceptionCaseRepository exceptions;

    @Test
    void classifiesSeededOutwardMismatchesAndFinds3bShortfall() throws IOException {
        Engagement e = new Engagement(UUID.randomUUID(), UUID.randomUUID(), "CLIENT-A",
                LocalDate.of(2024, 4, 1), LocalDate.of(2025, 3, 31), LocalDate.of(2025, 3, 31), Instant.now());
        engagements.save(e);

        var s = importService.importSalesRegister(e.getId(), "sales_register.csv",
                Files.readAllBytes(SAMPLE.resolve("sales_register.csv")));
        var g1 = importService.importGstr1(e.getId(), "gstr1.csv",
                Files.readAllBytes(SAMPLE.resolve("gstr1.csv")));
        var g3b = importService.importGstr3b(e.getId(), "gstr3b.csv",
                Files.readAllBytes(SAMPLE.resolve("gstr3b.csv")));
        assertEquals(380, s.added());
        assertEquals(379, g1.added());
        assertEquals(12, g3b.added());

        // GS-02: seeded A15 (3 not reported), A16 (2 value mismatches), A17 (2 GSTR-1-only)
        var r = reconciliation.reconcileSales(e.getId());
        assertEquals(375, r.counts().get(Category.MATCHED));
        assertEquals(2, r.counts().get(Category.VALUE_MISMATCH));
        assertEquals(3, r.counts().get(Category.BOOKS_ONLY));
        assertEquals(2, r.counts().get(Category.G2B_ONLY)); // = GSTR-1-only on the sales side
        assertEquals(7, r.exceptionsCreated());

        // GS-03: exactly one period differs, by the seeded Rs 50,000 (A18)
        var b = reconciliation.reconcile3b(e.getId());
        assertEquals(12, b.periods().size());
        assertEquals(1, b.differences());
        assertEquals(50_000_00L, b.totalTaxDiffPaise());
        assertEquals(1, b.exceptionsCreated());
        assertTrue(b.periods().stream().anyMatch(p -> p.period().equals("2025-01") && p.taxDiffPaise() == 50_000_00L));

        // exception wording names the period and stays neutral
        var gs03 = exceptions.findByEngagementIdOrderBySeverityAscExposurePaiseDesc(e.getId()).stream()
                .filter(x -> x.getRuleId().equals("GS-03")).toList();
        assertEquals(1, gs03.size());
        assertTrue(gs03.get(0).getReason().contains("2025-01"));
        assertTrue(gs03.get(0).getReason().contains("professional judgement"));

        // idempotent re-runs
        var r2 = reconciliation.reconcileSales(e.getId());
        assertEquals(0, r2.exceptionsCreated());
        assertEquals(7, r2.skippedExisting());
        var b2 = reconciliation.reconcile3b(e.getId());
        assertEquals(0, b2.exceptionsCreated());
        assertEquals(1, b2.skippedExisting());
        assertEquals(8, exceptions.countByEngagementId(e.getId()));
    }
}
