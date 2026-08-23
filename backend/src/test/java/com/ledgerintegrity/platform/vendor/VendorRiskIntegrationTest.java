package com.ledgerintegrity.platform.vendor;

import com.ledgerintegrity.platform.engagement.Engagement;
import com.ledgerintegrity.platform.engagement.EngagementRepository;
import com.ledgerintegrity.platform.gst.GstImportService;
import com.ledgerintegrity.platform.gst.GstReconciliationService;
import com.ledgerintegrity.platform.importer.ImportService.SourceFile;
import com.ledgerintegrity.platform.importer.MappingProfileRepository;
import com.ledgerintegrity.platform.importer.persist.EngagementImportService;
import com.ledgerintegrity.platform.rules.RuleEngineService;
import com.ledgerintegrity.platform.rules.RuleParams;
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

/** Per-vendor risk report on the seeded CLIENT-A year: the planted vendor cluster ranks top. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:vendorriskdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@EnabledIf("sampleDataPresent")
class VendorRiskIntegrationTest {

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
    @Autowired MappingProfileRepository profiles;
    @Autowired VendorRiskService risk;

    @Test
    void reportRanksTheSeededVendorClusterTopWithExplainableComponents() throws IOException {
        Engagement e = new Engagement(UUID.randomUUID(), UUID.randomUUID(), "RISK-CLIENT",
                LocalDate.of(2024, 4, 1), LocalDate.of(2025, 3, 31), LocalDate.of(2025, 3, 31), Instant.now());
        engagements.save(e);
        glImport.importInto(e.getId(),
                new SourceFile("general_ledger.csv", Files.readAllBytes(SAMPLE.resolve("general_ledger.csv"))),
                new SourceFile("trial_balance.csv", Files.readAllBytes(SAMPLE.resolve("trial_balance.csv"))),
                profiles.find("client-a-gl").orElseThrow());
        vendorImport.importVendorMaster(e.getId(), "vendor_master.csv",
                Files.readAllBytes(SAMPLE.resolve("vendor_master.csv")));
        gstImport.importPurchaseRegister(e.getId(), "purchase_register.csv",
                Files.readAllBytes(SAMPLE.resolve("purchase_register.csv")));
        gstImport.importGstr2b(e.getId(), "gstr2b.csv", Files.readAllBytes(SAMPLE.resolve("gstr2b.csv")));
        engine.run(e.getId(), RuleParams.defaults().withPrivilegedUsers(Set.of("ADMIN-1", "MGR-1")));
        gstRecon.reconcile(e.getId());

        var report = risk.report(e.getId());
        assertFalse(report.isEmpty());
        // ranked by score, every scored vendor explains itself
        for (int i = 1; i < report.size(); i++) {
            assertTrue(report.get(i - 1).score() >= report.get(i).score());
        }
        var top = report.get(0);
        assertTrue(top.score() >= 30, "top vendor should carry substantial risk, got " + top.score());
        assertTrue(top.components().size() >= 2, "top vendor risk must be multi-component: " + top.components());
        assertFalse(top.notes().isEmpty());

        // the seeded duplicate-vendor pair (shared bank account) is surfaced near the top
        boolean sharedBankSurfaced = report.stream().limit(5)
                .anyMatch(v -> v.notes().stream().anyMatch(n -> n.contains("shared with")));
        assertTrue(sharedBankSurfaced, "shared-bank vendors should rank in the top 5");

        // components never exceed their caps
        for (var v : report) {
            v.components().forEach((k, points) -> assertTrue(points <= 35, k + "=" + points));
            assertEquals(v.score(), v.components().values().stream().mapToInt(Integer::intValue).sum());
        }
    }
}
