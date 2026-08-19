package com.ledgerintegrity.platform.gst;

import com.ledgerintegrity.platform.bank.BankImportService;
import com.ledgerintegrity.platform.bank.BankReconciliationService;
import com.ledgerintegrity.platform.bank.persist.BankMatchResult.MatchType;
import com.ledgerintegrity.platform.engagement.Engagement;
import com.ledgerintegrity.platform.engagement.EngagementRepository;
import com.ledgerintegrity.platform.gst.persist.GstMatchResult;
import com.ledgerintegrity.platform.gst.persist.GstMatchResult.Category;
import com.ledgerintegrity.platform.gst.persist.GstMatchResult.Side;
import com.ledgerintegrity.platform.gst.persist.GstMatchResultRepository;
import com.ledgerintegrity.platform.importer.ImportService.SourceFile;
import com.ledgerintegrity.platform.importer.MappingProfileRepository;
import com.ledgerintegrity.platform.importer.persist.EngagementImportService;
import com.ledgerintegrity.platform.notify.NotificationService;
import com.ledgerintegrity.platform.rules.RuleEngineService;
import com.ledgerintegrity.platform.rules.RuleParams;
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

/** GST-002 fuzzy suggestions, BKR-003 manual bank matches, §18.3 notifications, JET-001 filters. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:batchtestdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@EnabledIf("sampleDataPresent")
class FuzzyManualAndNotifyIntegrationTest {

    private static final Path SAMPLE = Path.of("..", "phase0", "sample-data");

    static boolean sampleDataPresent() {
        return Files.exists(SAMPLE.resolve("general_ledger.csv"));
    }

    @Autowired EngagementRepository engagements;
    @Autowired GstImportService gstImport;
    @Autowired GstReconciliationService gstRecon;
    @Autowired GstMatchResultRepository gstMatches;
    @Autowired BankImportService bankImport;
    @Autowired BankReconciliationService bankRecon;
    @Autowired EngagementImportService glImport;
    @Autowired RuleEngineService engine;
    @Autowired NotificationService notifications;
    @Autowired MappingProfileRepository profiles;

    @Test
    void fuzzySuggestionsManualBankMatchesNotificationsAndFilters() throws IOException {
        UUID firmId = UUID.randomUUID();
        Engagement e = new Engagement(UUID.randomUUID(), firmId, "CLIENT-F",
                LocalDate.of(2024, 4, 1), LocalDate.of(2025, 3, 31), LocalDate.of(2025, 3, 31), Instant.now());
        engagements.save(e);

        // ---- GST-002: same supplier, typo'd invoice number -> a scored suggestion ----
        String purchases = "invoice_no,invoice_date,vendor_id,vendor_name,gstin,taxable_value,tax_amount,total,voucher_id\n"
                + "INV-1001,2024-06-10,V-1,Acme Traders,27AA1111A1Z5,100000.00,18000.00,118000.00,PUR-1\n";
        String g2b = "supplier_gstin,supplier_name,invoice_no,invoice_date,taxable_value,tax_amount,filing_status\n"
                + "27AA1111A1Z5,Acme Traders,INV-1O01,2024-06-10,100000.00,18000.00,Filed\n"; // O for 0
        gstImport.importPurchaseRegister(e.getId(), "p.csv", purchases.getBytes(StandardCharsets.UTF_8));
        gstImport.importGstr2b(e.getId(), "g.csv", g2b.getBytes(StandardCharsets.UTF_8));
        var r = gstRecon.reconcile(e.getId());
        assertEquals(1, r.counts().get(Category.BOOKS_ONLY));
        assertEquals(1, r.counts().get(Category.G2B_ONLY));
        assertEquals(1, r.counts().get(Category.SUGGESTED));
        GstMatchResult suggestion = gstMatches.findBySideAndCategory(e.getId(), Side.PURCHASE, Category.SUGGESTED, true).get(0);
        assertNotNull(suggestion.getConfidence());
        assertTrue(suggestion.getConfidence() >= 0.85); // gstin + 87% invoice similarity + exact amount
        assertTrue(suggestion.getMatchedFields().contains("portal=INV-1O01"));

        // approving = the existing GST-007 manual link; suggestion resolves on re-reconcile
        gstRecon.manualLink(e.getId(), Side.PURCHASE, "27AA1111A1Z5", "INV-1001",
                "27AA1111A1Z5", "INV-1O01", "Supplier typo confirmed with copy invoice.", "GST Reviewer");
        var r2 = gstRecon.reconcile(e.getId());
        assertEquals(1, r2.counts().get(Category.MATCHED));
        assertEquals(0, r2.counts().get(Category.BOOKS_ONLY));
        assertEquals(0, r2.counts().get(Category.SUGGESTED));

        // ---- BKR-003: manual bank match ----
        String stmt = "date,narration,reference,debit,credit,balance\n"
                + "2024-07-01,UNKNOWN NARRATION,REF-X,5000.00,,995000.00\n";
        String ledg = "date,voucher_id,reference,debit,credit,narration\n"
                + "2024-07-01,PMT-77,OTHER-REF,,5000.00,Payment somewhere\n";
        bankImport.importStatement(e.getId(), "s.csv", stmt.getBytes(StandardCharsets.UTF_8));
        bankImport.importLedger(e.getId(), "l.csv", ledg.getBytes(StandardCharsets.UTF_8));
        var b1 = bankRecon.reconcile(e.getId());
        assertEquals(1, b1.counts().get(MatchType.BANK_ONLY));
        assertEquals(1, b1.counts().get(MatchType.BOOKS_ONLY));

        assertThrows(IllegalArgumentException.class, () ->
                bankRecon.manualLink(e.getId(), "REF-X", "PMT-77", " ", "Reviewer")); // reason required
        bankRecon.manualLink(e.getId(), "REF-X", "PMT-77",
                "Bank truncated the reference; confirmed against the advice.", "B. Reviewer");
        var b2 = bankRecon.reconcile(e.getId());
        assertEquals(1, b2.counts().get(MatchType.MANUAL));
        assertEquals(0, b2.counts().get(MatchType.BANK_ONLY));
        assertEquals(0, b2.counts().get(MatchType.BOOKS_ONLY));
        assertEquals(0, b2.unexplainedPaise());

        // ---- JET-001 + NFR-002/003: filtered run, HIGH notification once ----
        glImport.importInto(e.getId(),
                new SourceFile("general_ledger.csv", Files.readAllBytes(SAMPLE.resolve("general_ledger.csv"))),
                new SourceFile("trial_balance.csv", Files.readAllBytes(SAMPLE.resolve("trial_balance.csv"))),
                profiles.find("client-a-gl").orElseThrow());

        var filtered = engine.run(e.getId(), RuleParams.defaults().withPrivilegedUsers(Set.of("ADMIN-1", "MGR-1")),
                new RuleEngineService.PopulationFilter(null, null, Set.of("Journal"), null, null));
        assertTrue(filtered.run().getPopulationVouchers() < 20); // only the 8 journals
        assertTrue(filtered.run().getPopulationValuePaise() > 0);
        assertTrue(filtered.run().getParamsJson().contains("voucherTypes")); // snapshot includes the filter

        long unreadAfterRun = notifications.unreadCount(firmId);
        assertTrue(unreadAfterRun >= 1); // HIGH exceptions notification
        assertTrue(notifications.list(firmId).stream()
                .anyMatch(n -> n.getType().equals("HIGH_EXCEPTIONS") && n.getMessage().contains("CLIENT-F")));

        // NFR-003: identical re-notification is suppressed; different run id notifies anew only if new HIGHs
        engine.run(e.getId(), RuleParams.defaults().withPrivilegedUsers(Set.of("ADMIN-1", "MGR-1")),
                new RuleEngineService.PopulationFilter(null, null, Set.of("Journal"), null, null));
        assertEquals(unreadAfterRun, notifications.unreadCount(firmId)); // nothing new raised -> no new alert

        notifications.markAllRead(firmId);
        assertEquals(0, notifications.unreadCount(firmId));
    }
}
