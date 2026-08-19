package com.ledgerintegrity.platform.rules;

import com.ledgerintegrity.platform.engagement.Engagement;
import com.ledgerintegrity.platform.engagement.EngagementRepository;
import com.ledgerintegrity.platform.importer.ImportService.SourceFile;
import com.ledgerintegrity.platform.importer.MappingProfileRepository;
import com.ledgerintegrity.platform.importer.persist.EngagementImportService;
import com.ledgerintegrity.platform.rules.persist.InvestigationCase;
import com.ledgerintegrity.platform.rules.persist.InvestigationCaseRepository;
import com.ledgerintegrity.platform.rules.persist.RiskWeightConfig;
import com.ledgerintegrity.platform.rules.persist.RiskWeightConfigRepository;
import com.ledgerintegrity.platform.vendor.AuditTrailAnalysisService;
import com.ledgerintegrity.platform.vendor.VendorImportService;
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
        "spring.datasource.url=jdbc:h2:mem:atrrsktestdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@EnabledIf("sampleDataPresent")
class AtrAndRiskConfigIntegrationTest {

    private static final Path SAMPLE = Path.of("..", "phase0", "sample-data");

    static boolean sampleDataPresent() {
        return Files.exists(SAMPLE.resolve("general_ledger.csv"));
    }

    @Autowired EngagementRepository engagements;
    @Autowired EngagementImportService glImport;
    @Autowired VendorImportService vendorImport;
    @Autowired AuditTrailAnalysisService atrAnalysis;
    @Autowired RuleEngineService engine;
    @Autowired InvestigationCaseRepository cases;
    @Autowired RiskWeightConfigRepository weightConfigs;
    @Autowired MappingProfileRepository profiles;

    /** Crafted trail: events only in Apr and Nov (big gap), plus a logging-disablement event. */
    private static final String AUDIT_CSV = String.join("\n",
            "timestamp,user_id,object,record_id,field,old_value,new_value,action",
            "2024-04-10 10:00,ACCT-1,VendorMaster,V-001,phone,x,y,Modify",
            "2024-04-25 11:00,ACCT-2,VendorMaster,V-002,email,a,b,Modify",
            "2024-11-20 09:30,ADMIN-1,SystemConfig,CFG-1,audit_trail_enabled,Yes,No,Disable",
            "2024-11-21 09:30,ACCT-1,VendorMaster,V-003,address,p,q,Modify");

    @Test
    void atrFindsGapsAndDisablementAndRiskConfigChangesScores() throws IOException {
        UUID firmId = UUID.randomUUID();
        Engagement e = new Engagement(UUID.randomUUID(), firmId, "CLIENT-A",
                LocalDate.of(2024, 4, 1), LocalDate.of(2025, 3, 31), LocalDate.of(2025, 3, 31), Instant.now());
        engagements.save(e);
        vendorImport.importAuditTrail(e.getId(), "audit_trail.csv", AUDIT_CSV.getBytes(StandardCharsets.UTF_8));

        // ---- ATR-002/003 ----
        var report = atrAnalysis.analyze(e.getId());
        assertEquals(4, report.events());
        assertEquals(1, report.disablementEvents()); // the SystemConfig Disable event
        // the Apr-25 -> Nov-20 stretch (~209 days) and the Nov-21 -> close stretch (~130 days)
        assertEquals(2, report.gaps().size());
        assertTrue(report.gaps().stream().anyMatch(g -> g.days() > 200));
        assertTrue(report.monthsWithoutEvents().contains("2024-07"));
        assertEquals(3, report.exceptionsCreated()); // 2 gaps + 1 disablement
        // neutral wording: a gap is a limitation, not a conclusion
        var again = atrAnalysis.analyze(e.getId());
        assertEquals(0, again.exceptionsCreated()); // idempotent
        assertEquals(3, again.skippedExisting());

        // ---- RSK-003: firm weights change the computed score on the next consolidation ----
        glImport.importInto(e.getId(),
                new SourceFile("general_ledger.csv", Files.readAllBytes(SAMPLE.resolve("general_ledger.csv"))),
                new SourceFile("trial_balance.csv", Files.readAllBytes(SAMPLE.resolve("trial_balance.csv"))),
                profiles.find("client-a-gl").orElseThrow());
        engine.run(e.getId(), RuleParams.defaults().withPrivilegedUsers(Set.of("ADMIN-1", "MGR-1")));
        InvestigationCase top = cases.findByEngagementIdOrderByPriorityScoreDescExposurePaiseDesc(e.getId()).get(0);
        assertEquals(55, top.getPriorityScore()); // defaults: 2x10 + 7x5

        weightConfigs.save(new RiskWeightConfig(UUID.randomUUID(), firmId, 1, 20, 8, 3, "Methodology Lead", Instant.now()));
        engine.run(e.getId(), RuleParams.defaults().withPrivilegedUsers(Set.of("ADMIN-1", "MGR-1")));
        InvestigationCase rescored = cases.findById(top.getId()).orElseThrow();
        assertEquals(2 * 20 + 7 * 8, rescored.getPriorityScore()); // 96 under the firm's weights

        // ---- RSK-004: reviewer override with recorded reason, surviving re-runs ----
        assertThrows(IllegalArgumentException.class, () ->
                rescored.overridePriority(5, " ", "P. Partner", Instant.now()));
        rescored.overridePriority(5, "Management already remediated; deprioritised for this cycle.",
                "P. Partner", Instant.now());
        cases.save(rescored);
        assertEquals(5, rescored.effectivePriority());
        assertEquals(96, rescored.getPriorityScore()); // rule result untouched

        engine.run(e.getId(), RuleParams.defaults().withPrivilegedUsers(Set.of("ADMIN-1", "MGR-1")));
        InvestigationCase afterRerun = cases.findById(rescored.getId()).orElseThrow();
        assertEquals(5, afterRerun.effectivePriority()); // override survives consolidation
        assertEquals("P. Partner", afterRerun.getOverriddenBy());
    }
}
