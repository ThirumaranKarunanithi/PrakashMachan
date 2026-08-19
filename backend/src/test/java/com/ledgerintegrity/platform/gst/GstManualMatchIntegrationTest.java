package com.ledgerintegrity.platform.gst;

import com.ledgerintegrity.platform.engagement.Engagement;
import com.ledgerintegrity.platform.engagement.EngagementRepository;
import com.ledgerintegrity.platform.gst.persist.GstMatchResult;
import com.ledgerintegrity.platform.gst.persist.GstMatchResult.Category;
import com.ledgerintegrity.platform.gst.persist.GstMatchResult.Side;
import com.ledgerintegrity.platform.gst.persist.GstMatchResultRepository;
import com.ledgerintegrity.platform.gst.persist.GstManualMatchRepository;
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

/** GST-007 manual links and GST-008 correction schedule on the CLIENT-A files. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:gstmanualtestdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@EnabledIf("sampleDataPresent")
class GstManualMatchIntegrationTest {

    private static final Path SAMPLE = Path.of("..", "phase0", "sample-data");

    static boolean sampleDataPresent() {
        return Files.exists(SAMPLE.resolve("gstr1.csv"));
    }

    @Autowired EngagementRepository engagements;
    @Autowired GstImportService importService;
    @Autowired GstReconciliationService reconciliation;
    @Autowired GstMatchResultRepository matches;
    @Autowired GstManualMatchRepository manualMatches;

    @Test
    void manualLinksReclassifyAndCorrectionScheduleCoversAllDifferences() throws IOException {
        Engagement e = new Engagement(UUID.randomUUID(), UUID.randomUUID(), "CLIENT-A",
                LocalDate.of(2024, 4, 1), LocalDate.of(2025, 3, 31), LocalDate.of(2025, 3, 31), Instant.now());
        engagements.save(e);
        importService.importPurchaseRegister(e.getId(), "purchase_register.csv", read("purchase_register.csv"));
        importService.importGstr2b(e.getId(), "gstr2b.csv", read("gstr2b.csv"));
        importService.importSalesRegister(e.getId(), "sales_register.csv", read("sales_register.csv"));
        importService.importGstr1(e.getId(), "gstr1.csv", read("gstr1.csv"));
        importService.importGstr3b(e.getId(), "gstr3b.csv", read("gstr3b.csv"));

        var before = reconciliation.reconcile(e.getId());
        assertEquals(5, before.counts().get(Category.BOOKS_ONLY));
        assertEquals(3, before.counts().get(Category.G2B_ONLY));
        reconciliation.reconcileSales(e.getId());

        // pick one unmatched pair from each list and link them manually
        GstMatchResult booksOnly = matches.findBySideAndCategory(e.getId(), Side.PURCHASE, Category.BOOKS_ONLY, true).get(0);
        GstMatchResult portalOnly = matches.findBySideAndCategory(e.getId(), Side.PURCHASE, Category.G2B_ONLY, true).get(0);

        // GST-007: reason is mandatory
        assertThrows(IllegalArgumentException.class, () -> reconciliation.manualLink(e.getId(), Side.PURCHASE,
                booksOnly.getGstin(), booksOnly.getInvoiceNo(),
                portalOnly.getGstin(), portalOnly.getInvoiceNo(), " ", "GST Reviewer"));
        // unknown invoice rejected
        assertThrows(IllegalArgumentException.class, () -> reconciliation.manualLink(e.getId(), Side.PURCHASE,
                "XX", "NOPE", portalOnly.getGstin(), portalOnly.getInvoiceNo(), "typo", "GST Reviewer"));

        reconciliation.manualLink(e.getId(), Side.PURCHASE,
                booksOnly.getGstin(), booksOnly.getInvoiceNo(),
                portalOnly.getGstin(), portalOnly.getInvoiceNo(),
                "Supplier filed under a different invoice number; confirmed same supply.", "GST Reviewer");
        assertEquals(1, manualMatches.findByEngagementIdOrderByDecidedAtDesc(e.getId()).size());

        // re-reconcile: the linked pair leaves both unmatched lists
        var after = reconciliation.reconcile(e.getId());
        assertEquals(4, after.counts().get(Category.BOOKS_ONLY));
        assertEquals(2, after.counts().get(Category.G2B_ONLY));
        int reclassified = after.counts().get(Category.MATCHED) + after.counts().get(Category.VALUE_MISMATCH);
        int beforeSum = before.counts().get(Category.MATCHED) + before.counts().get(Category.VALUE_MISMATCH);
        assertEquals(beforeSum + 1, reclassified);
        // the manually linked row is marked as such (logged and reviewable)
        assertTrue(matches.findBySide(e.getId(), Side.PURCHASE, true).stream()
                .anyMatch(GstMatchResult::isManuallyLinked));

        // GST-008: the schedule covers every unresolved difference incl. the 3B period
        var schedule = reconciliation.correctionSchedule(e.getId());
        assertFalse(schedule.isEmpty());
        assertTrue(schedule.stream().anyMatch(r -> r.category().equals("GSTR1_VS_3B") && r.reference().equals("2025-01")));
        assertTrue(schedule.stream().anyMatch(r -> r.invoiceOrPeriod().equals("TT/2287A")));
        assertTrue(schedule.stream().allMatch(r -> r.category().equals("MATCHED") == false));
        assertTrue(schedule.stream().allMatch(r -> !r.suggestedAction().isBlank()));
        // ranked by financial effect
        for (int i = 1; i < schedule.size(); i++) {
            assertTrue(schedule.get(i - 1).taxEffectPaise() >= schedule.get(i).taxEffectPaise());
        }
    }

    private byte[] read(String f) throws IOException {
        return Files.readAllBytes(SAMPLE.resolve(f));
    }
}
