package com.ledgerintegrity.platform.rules;

import com.ledgerintegrity.platform.engagement.Engagement;
import com.ledgerintegrity.platform.engagement.EngagementDeletionService;
import com.ledgerintegrity.platform.engagement.EngagementRepository;
import com.ledgerintegrity.platform.evidence.EvidenceService;
import com.ledgerintegrity.platform.gst.GstImportService;
import com.ledgerintegrity.platform.gst.GstReconciliationService;
import com.ledgerintegrity.platform.importer.ImportService.SourceFile;
import com.ledgerintegrity.platform.importer.MappingProfileRepository;
import com.ledgerintegrity.platform.importer.persist.EngagementImportService;
import com.ledgerintegrity.platform.importer.persist.LedgerEntryRepository;
import com.ledgerintegrity.platform.rules.persist.ExceptionCase;
import com.ledgerintegrity.platform.rules.persist.ExceptionCaseRepository;
import com.ledgerintegrity.platform.rules.persist.SampleSelection;
import com.ledgerintegrity.platform.vendor.VendorImportService;
import com.ledgerintegrity.platform.workpaper.WorkpaperService;
import com.ledgerintegrity.platform.workpaper.persist.WorkpaperTemplate;
import com.ledgerintegrity.platform.workpaper.persist.WorkpaperTemplateRepository;
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

/** Sampling (JET-008), timelines (AC-08), templates (AWP-001), deletion (SEC-006), GST-006. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:batch2testdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@EnabledIf("sampleDataPresent")
class BatchTwoIntegrationTest {

    private static final Path SAMPLE = Path.of("..", "phase0", "sample-data");

    static boolean sampleDataPresent() {
        return Files.exists(SAMPLE.resolve("general_ledger.csv"));
    }

    @Autowired EngagementRepository engagements;
    @Autowired EngagementImportService glImport;
    @Autowired VendorImportService vendorImport;
    @Autowired GstImportService gstImport;
    @Autowired GstReconciliationService gstRecon;
    @Autowired RuleEngineService engine;
    @Autowired SamplingService sampling;
    @Autowired CaseTimelineService timeline;
    @Autowired WorkpaperService workpapers;
    @Autowired WorkpaperTemplateRepository templates;
    @Autowired EngagementDeletionService deletion;
    @Autowired ExceptionCaseRepository exceptions;
    @Autowired LedgerEntryRepository entries;
    @Autowired EvidenceService evidence;
    @Autowired MappingProfileRepository profiles;

    @Test
    void samplingTimelineTemplatesGst006AndSecureDeletion() throws IOException {
        UUID firmId = UUID.randomUUID();
        Engagement e = new Engagement(UUID.randomUUID(), firmId, "CLIENT-A",
                LocalDate.of(2024, 4, 1), LocalDate.of(2025, 3, 31), LocalDate.of(2025, 3, 31), Instant.now());
        engagements.save(e);
        glImport.importInto(e.getId(),
                new SourceFile("general_ledger.csv", read("general_ledger.csv")),
                new SourceFile("trial_balance.csv", read("trial_balance.csv")),
                profiles.find("client-a-gl").orElseThrow());
        vendorImport.importVendorMaster(e.getId(), "vendor_master.csv", read("vendor_master.csv"));
        vendorImport.importAuditTrail(e.getId(), "audit_trail.csv", read("audit_trail.csv"));
        gstImport.importPurchaseRegister(e.getId(), "purchase_register.csv", read("purchase_register.csv"));
        gstImport.importGstr2b(e.getId(), "gstr2b.csv", read("gstr2b.csv"));
        gstRecon.reconcile(e.getId());
        var run = engine.run(e.getId(), RuleParams.defaults().withPrivilegedUsers(Set.of("ADMIN-1", "MGR-1")));

        // ---- JET-008 / BEN-013: samples ----
        SampleSelection risk = sampling.select(e.getId(), SampleSelection.Method.RISK_RANKED, 5, null, "A. Associate");
        assertEquals(5, risk.getSampleSize());
        assertTrue(risk.getVoucherIds().contains("JRN-90001")); // top-priority case leads the ranking
        SampleSelection rnd1 = sampling.select(e.getId(), SampleSelection.Method.RANDOM, 10, 42L, "A. Associate");
        SampleSelection rnd2 = sampling.select(e.getId(), SampleSelection.Method.RANDOM, 10, 42L, "A. Associate");
        assertEquals(rnd1.getVoucherIds(), rnd2.getVoucherIds()); // seeded = reproducible
        SampleSelection rnd3 = sampling.select(e.getId(), SampleSelection.Method.RANDOM, 10, 43L, "A. Associate");
        assertNotEquals(rnd1.getVoucherIds(), rnd3.getVoucherIds());

        // ---- AC-08: case timeline tells the cross-source story ----
        var sriRam = run.cases().stream()
                .filter(c -> c.getVoucherIds().contains("VENDOR:V-044")).findFirst().orElseThrow();
        var events = timeline.timeline(sriRam);
        assertTrue(events.size() >= 3);
        assertTrue(events.stream().anyMatch(ev -> ev.source().equals("vendor master")
                && ev.description().contains("created by ADMIN-1")));
        assertTrue(events.stream().anyMatch(ev -> ev.source().equals("ledger")));
        // chronological
        for (int i = 1; i < events.size(); i++) {
            assertTrue(events.get(i - 1).when().compareTo(events.get(i).when()) <= 0);
        }

        // ---- AWP-001 + ATR-007 + JET-008 in the workpaper ----
        templates.save(new WorkpaperTemplate(UUID.randomUUID(), firmId, 1,
                "Sharma & Associates — Audit Workpaper", "Prepared under the firm's audit manual v3.",
                true, false, true, "Methodology Lead", Instant.now()));
        var wp = workpapers.generate(e.getId());
        assertTrue(wp.getContentHtml().contains("Sharma &amp; Associates — Audit Workpaper"));
        assertTrue(wp.getContentHtml().contains("audit manual v3"));
        assertTrue(wp.getContentHtml().contains("Samples selected (JET-008"));
        assertTrue(wp.getContentHtml().contains("RISK_RANKED"));
        assertTrue(wp.getContentHtml().contains("Audit-trail coverage (ATR-002/007)"));
        assertFalse(wp.getContentHtml().contains("4. Bank reconciliation")); // toggled off by template

        // ---- GST-006: a resolved mismatch does not reappear as new ----
        ExceptionCase gs01 = exceptions.findByEngagementIdOrderBySeverityAscExposurePaiseDesc(e.getId()).stream()
                .filter(x -> x.getRuleId().equals("GS-01B")).findFirst().orElseThrow();
        gs01.decide(ExceptionCase.Status.EXPLAINED, "Supplier filed late; appeared in next period's 2B.",
                "GST Reviewer", Instant.now());
        exceptions.save(gs01);
        long before = exceptions.countByEngagementId(e.getId());
        gstRecon.reconcile(e.getId());
        assertEquals(before, exceptions.countByEngagementId(e.getId()));
        assertEquals(ExceptionCase.Status.EXPLAINED,
                exceptions.findById(gs01.getId()).orElseThrow().getStatus());

        // ---- SEC-006 / CDC-008: secure deletion removes everything, auditable ----
        long rows = entries.countByEngagementId(e.getId());
        assertTrue(rows > 0);
        int removed = deletion.deleteEngagement(e.getId(), firmId, "partner@firm");
        assertTrue(removed > (int) rows); // ledger + everything else
        assertTrue(engagements.findById(e.getId()).isEmpty());
        assertEquals(0, entries.countByEngagementId(e.getId()));
        assertEquals(0, exceptions.countByEngagementId(e.getId()));
    }

    private byte[] read(String f) throws IOException {
        return Files.readAllBytes(SAMPLE.resolve(f));
    }
}
