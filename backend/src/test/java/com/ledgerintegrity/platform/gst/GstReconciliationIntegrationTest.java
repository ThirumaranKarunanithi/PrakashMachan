package com.ledgerintegrity.platform.gst;

import com.ledgerintegrity.platform.engagement.Engagement;
import com.ledgerintegrity.platform.engagement.EngagementRepository;
import com.ledgerintegrity.platform.gst.persist.GstMatchResult.Category;
import com.ledgerintegrity.platform.gst.persist.GstMatchResultRepository;
import com.ledgerintegrity.platform.rules.persist.ExceptionCaseRepository;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * GS-01 against the Phase 0 CLIENT-A registers. Expected classification comes from the
 * seeded anomalies A12-A14 (SEEDED_ANOMALIES.md): 296 matched, 3 value mismatches,
 * 5 books-only, 3 2B-only.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:gsttestdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@EnabledIf("sampleDataPresent")
class GstReconciliationIntegrationTest {

    private static final Path SAMPLE = Path.of("..", "phase0", "sample-data");

    static boolean sampleDataPresent() {
        return Files.exists(SAMPLE.resolve("purchase_register.csv"));
    }

    @Autowired EngagementRepository engagements;
    @Autowired GstImportService importService;
    @Autowired GstReconciliationService reconciliation;
    @Autowired GstMatchResultRepository matches;
    @Autowired ExceptionCaseRepository exceptions;
    @Autowired InvestigationCaseRepository cases;

    @Test
    void classifiesSeededMismatchesAndReconcileIsIdempotent() throws IOException {
        Engagement e = new Engagement(UUID.randomUUID(), UUID.randomUUID(), "CLIENT-A",
                LocalDate.of(2024, 4, 1), LocalDate.of(2025, 3, 31), LocalDate.of(2025, 3, 31), Instant.now());
        engagements.save(e);

        var p = importService.importPurchaseRegister(e.getId(), "purchase_register.csv",
                Files.readAllBytes(SAMPLE.resolve("purchase_register.csv")));
        var g = importService.importGstr2b(e.getId(), "gstr2b.csv",
                Files.readAllBytes(SAMPLE.resolve("gstr2b.csv")));
        assertEquals(304, p.added());
        assertEquals(302, g.added());
        assertTrue(p.problems().isEmpty());
        assertTrue(g.problems().isEmpty());

        // re-import is delta-safe (DAT-006)
        var pAgain = importService.importPurchaseRegister(e.getId(), "purchase_register.csv",
                Files.readAllBytes(SAMPLE.resolve("purchase_register.csv")));
        assertEquals(0, pAgain.added());
        assertEquals(304, pAgain.skipped());

        var r = reconciliation.reconcile(e.getId());
        assertEquals(296, r.counts().get(Category.MATCHED));
        assertEquals(3, r.counts().get(Category.VALUE_MISMATCH));
        assertEquals(5, r.counts().get(Category.BOOKS_ONLY));
        assertEquals(3, r.counts().get(Category.G2B_ONLY));
        assertEquals(11, r.exceptionsCreated()); // every non-match becomes one exception
        assertTrue(r.itcExposurePaise() > 0);

        // the seeded 2B-only invoices are present by name (A14)
        var g2bOnly = matches.findBySideAndCategory(e.getId(), com.ledgerintegrity.platform.gst.persist.GstMatchResult.Side.PURCHASE, Category.G2B_ONLY, true);
        assertTrue(g2bOnly.stream().anyMatch(m -> m.getInvoiceNo().equals("GT/8801")));
        assertTrue(g2bOnly.stream().anyMatch(m -> m.getInvoiceNo().equals("IP/3302")));
        assertTrue(g2bOnly.stream().anyMatch(m -> m.getInvoiceNo().equals("MC/1190")));

        // the duplicate second booking TT/2287A is books-only (A12: supplier filed only TT/2287)
        var booksOnly = matches.findBySideAndCategory(e.getId(), com.ledgerintegrity.platform.gst.persist.GstMatchResult.Side.PURCHASE, Category.BOOKS_ONLY, true);
        assertTrue(booksOnly.stream().anyMatch(m -> m.getInvoiceNo().equals("TT/2287A")));

        // exceptions consolidated into cases; 11 GST exceptions on 11 distinct invoices -> 11 cases
        assertEquals(11, exceptions.countByEngagementId(e.getId()));
        assertEquals(11, cases.countByEngagementId(e.getId()));

        // idempotent: same reconciliation again -> same classification, no new exceptions
        var r2 = reconciliation.reconcile(e.getId());
        assertEquals(r.counts(), r2.counts());
        assertEquals(0, r2.exceptionsCreated());
        assertEquals(11, r2.skippedExisting());
        assertEquals(11, exceptions.countByEngagementId(e.getId()));
    }
}
