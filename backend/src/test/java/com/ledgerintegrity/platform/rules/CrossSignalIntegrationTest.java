package com.ledgerintegrity.platform.rules;

import com.ledgerintegrity.platform.engagement.Engagement;
import com.ledgerintegrity.platform.engagement.EngagementRepository;
import com.ledgerintegrity.platform.gst.GstImportService;
import com.ledgerintegrity.platform.gst.GstReconciliationService;
import com.ledgerintegrity.platform.importer.ImportService.SourceFile;
import com.ledgerintegrity.platform.importer.MappingProfileRepository;
import com.ledgerintegrity.platform.importer.persist.EngagementImportService;
import com.ledgerintegrity.platform.rules.persist.ExceptionCase;
import com.ledgerintegrity.platform.rules.persist.ExceptionCaseRepository;
import com.ledgerintegrity.platform.rules.persist.InvestigationCase;
import com.ledgerintegrity.platform.rules.persist.InvestigationCaseRepository;
import com.ledgerintegrity.platform.vendor.VendorImportService;
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
 * The full CLIENT-A engagement: GL + vendor master + audit trail + purchase register
 * + GSTR-2B, then GST reconciliation and the complete rule pack. Verifies that
 * independent modules corroborate each other inside consolidated cases (BRD §17.2) —
 * the seeded "Shri Ram Traders" event (A5/A6) and the duplicate Trident invoice (A7/A12).
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:crosstestdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@EnabledIf("sampleDataPresent")
class CrossSignalIntegrationTest {

    private static final Path SAMPLE = Path.of("..", "phase0", "sample-data");

    static boolean sampleDataPresent() {
        return Files.exists(SAMPLE.resolve("vendor_master.csv"));
    }

    @Autowired EngagementRepository engagements;
    @Autowired EngagementImportService glImport;
    @Autowired VendorImportService vendorImport;
    @Autowired GstImportService gstImport;
    @Autowired GstReconciliationService gstRecon;
    @Autowired RuleEngineService engine;
    @Autowired ExceptionCaseRepository exceptions;
    @Autowired InvestigationCaseRepository cases;
    @Autowired MappingProfileRepository profiles;

    @Test
    void independentModulesCorroborateInsideConsolidatedCases() throws IOException {
        Engagement e = new Engagement(UUID.randomUUID(), UUID.randomUUID(), "CLIENT-A",
                LocalDate.of(2024, 4, 1), LocalDate.of(2025, 3, 31), LocalDate.of(2025, 3, 31), Instant.now());
        engagements.save(e);

        glImport.importInto(e.getId(),
                new SourceFile("general_ledger.csv", read("general_ledger.csv")),
                new SourceFile("trial_balance.csv", read("trial_balance.csv")),
                profiles.find("client-a-gl").orElseThrow());
        assertEquals(45, vendorImport.importVendorMaster(e.getId(), "vendor_master.csv", read("vendor_master.csv")).added());
        assertEquals(27, vendorImport.importAuditTrail(e.getId(), "audit_trail.csv", read("audit_trail.csv")).added());
        gstImport.importPurchaseRegister(e.getId(), "purchase_register.csv", read("purchase_register.csv"));
        gstImport.importGstr2b(e.getId(), "gstr2b.csv", read("gstr2b.csv"));

        gstRecon.reconcile(e.getId());
        var result = engine.run(e.getId(), RuleParams.defaults().withPrivilegedUsers(Set.of("ADMIN-1", "MGR-1")));

        List<ExceptionCase> all = exceptions.findByEngagementIdOrderBySeverityAscExposurePaiseDesc(e.getId());
        // previous 42 + STA-01 (the PUR-01145 peer-group outlier by ADMIN-1) = 43
        assertEquals(43, all.size());
        assertEquals(3, all.stream().filter(x -> x.getRuleId().equals("JE-06")).count());
        for (String rule : List.of("VP-01", "VP-03", "VP-04", "VP-05", "VP-06", "PET-02", "PET-04")) {
            assertEquals(1, all.stream().filter(x -> x.getRuleId().equals(rule)).count(), rule);
        }
        assertEquals(4, all.stream().filter(x -> x.getRuleId().equals("MOT-01")).count());
        assertEquals(2, all.stream().filter(x -> x.getRuleId().equals("MOT-02")).count());

        List<InvestigationCase> allCases = cases.findByEngagementIdOrderByPriorityScoreDescExposurePaiseDesc(e.getId());

        // A5/A6: shared-bank vendors (VP-01) + new-vendor activity (VP-03) + the
        // supplier never filing SRT/0091 (GS-01B) merge into ONE case via shared tokens
        InvestigationCase sriRam = allCases.stream()
                .filter(c -> c.getVoucherIds().contains("VENDOR:V-044"))
                .findFirst().orElseThrow();
        Set<String> sriRamRules = ruleIdsOf(all, sriRam);
        assertTrue(sriRamRules.containsAll(Set.of("VP-01", "VP-03", "GS-01B")),
                "expected cross-module signals, got " + sriRamRules);

        // A7/A12: duplicate booking TT/2287A — VP-04 (duplicate invoice) and GS-01B
        // (books-only, supplier filed only the original) corroborate in one case
        InvestigationCase trident = allCases.stream()
                .filter(c -> ruleIdsOf(all, c).contains("VP-04"))
                .findFirst().orElseThrow();
        assertTrue(ruleIdsOf(all, trident).contains("GS-01B"),
                "duplicate-invoice case should include the GST books-only signal");

        // A8: bank change before payment found via audit trail (Fortune Chemicals)
        ExceptionCase vp06 = all.stream().filter(x -> x.getRuleId().equals("VP-06")).findFirst().orElseThrow();
        assertTrue(vp06.getReason().contains("Fortune Chemicals"));
        assertTrue(vp06.getReason().contains("after hours"));
        assertTrue(vp06.getSourceRefs().contains("audit_trail.csv:"));

        // everything idempotent: run both engines again -> nothing new
        gstRecon.reconcile(e.getId());
        var second = engine.run(e.getId(), RuleParams.defaults().withPrivilegedUsers(Set.of("ADMIN-1", "MGR-1")));
        assertEquals(0, second.run().getExceptionsCreated());
        assertEquals(43, exceptions.countByEngagementId(e.getId()));
    }

    private static Set<String> ruleIdsOf(List<ExceptionCase> all, InvestigationCase c) {
        return all.stream()
                .filter(x -> c.getId().equals(x.getCaseId()))
                .map(ExceptionCase::getRuleId)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static byte[] read(String file) throws IOException {
        return Files.readAllBytes(SAMPLE.resolve(file));
    }
}
