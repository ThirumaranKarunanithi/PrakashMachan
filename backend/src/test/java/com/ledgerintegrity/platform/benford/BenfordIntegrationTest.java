package com.ledgerintegrity.platform.benford;

import com.ledgerintegrity.platform.benford.persist.BenfordRun;
import com.ledgerintegrity.platform.benford.persist.BenfordRun.Conformity;
import com.ledgerintegrity.platform.benford.persist.BenfordRun.DigitTest;
import com.ledgerintegrity.platform.benford.persist.BenfordRun.Population;
import com.ledgerintegrity.platform.benford.persist.BenfordRun.Suitability;
import com.ledgerintegrity.platform.engagement.Engagement;
import com.ledgerintegrity.platform.engagement.EngagementRepository;
import com.ledgerintegrity.platform.importer.ImportService.SourceFile;
import com.ledgerintegrity.platform.importer.MappingProfileRepository;
import com.ledgerintegrity.platform.importer.persist.EngagementImportService;
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

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:benfordtestdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@EnabledIf("sampleDataPresent")
class BenfordIntegrationTest {

    private static final Path SAMPLE = Path.of("..", "phase0", "sample-data");

    static boolean sampleDataPresent() {
        return Files.exists(SAMPLE.resolve("general_ledger.csv"));
    }

    @Autowired EngagementRepository engagements;
    @Autowired EngagementImportService glImport;
    @Autowired BenfordService service;
    @Autowired ExceptionCaseRepository exceptions;

    @Test
    void suitabilityGateBlocksSmallPopulationsAndLargeOnesAreAssessedReproducibly() throws IOException {
        Engagement e = new Engagement(UUID.randomUUID(), UUID.randomUUID(), "CLIENT-A",
                LocalDate.of(2024, 4, 1), LocalDate.of(2025, 3, 31), LocalDate.of(2025, 3, 31), Instant.now());
        engagements.save(e);
        glImport.importInto(e.getId(),
                new SourceFile("general_ledger.csv", Files.readAllBytes(SAMPLE.resolve("general_ledger.csv"))),
                new SourceFile("trial_balance.csv", Files.readAllBytes(SAMPLE.resolve("trial_balance.csv"))),
                new MappingProfileForTest().profile());

        // BEN-002: the 8 manual journals are far below the methodology minimum -> gate blocks
        var small = service.run(e.getId(), Population.MANUAL_JOURNALS, DigitTest.FIRST, false, null);
        assertEquals(Suitability.NOT_SUITABLE, small.run().getSuitability());
        assertEquals(Conformity.NOT_ASSESSED, small.run().getConformity());
        assertNull(small.run().getMad());
        assertNull(small.run().getCreatedExceptionId()); // zero Benford risk points (16.7)
        assertTrue(small.run().getSuitabilityReasons().contains("below the methodology minimum"));
        // descriptive buckets still shown, exclusions reported (BEN-003)
        assertEquals(1, small.run().getExcludedReversals()); // JRN-90002 reverses JRN-90001

        // BEN-011: override without a reason is rejected
        assertThrows(IllegalArgumentException.class, () ->
                service.run(e.getId(), Population.MANUAL_JOURNALS, DigitTest.FIRST, true, " "));

        // large population: assessed, buckets reconcile to the eligible count
        var big = service.run(e.getId(), Population.ALL_VOUCHERS, DigitTest.FIRST, false, null);
        BenfordRun run = big.run();
        assertNotEquals(Suitability.NOT_SUITABLE, run.getSuitability());
        assertNotEquals(Conformity.NOT_ASSESSED, run.getConformity());
        assertNotNull(run.getMad());
        assertEquals(run.getEligibleCount(),
                big.buckets().stream().mapToInt(BenfordService.Bucket::observed).sum());
        assertEquals(100.0,
                big.buckets().stream().mapToDouble(BenfordService.Bucket::expectedPct).sum(), 0.1);
        assertTrue(run.getParamsJson().contains("log10(1 + 1/d)")); // BEN-012 formula stored

        // BEN-006: drilldown returns only vouchers whose amounts start with that digit
        var digit4 = service.drilldown(run, "4");
        assertFalse(digit4.isEmpty());
        assertTrue(digit4.stream().allMatch(v ->
                BenfordService.digitLabel(v.amountPaise(), DigitTest.FIRST).equals("4")));

        // reproducibility (BEN-012) + no duplicate exceptions on re-run (BEN-009)
        long exceptionsAfterFirst = exceptions.countByEngagementId(e.getId());
        var again = service.run(e.getId(), Population.ALL_VOUCHERS, DigitTest.FIRST, false, null);
        assertEquals(run.getMad(), again.run().getMad());
        assertEquals(run.getConformity(), again.run().getConformity());
        assertEquals(run.getResultJson(), again.run().getResultJson());
        assertEquals(exceptionsAfterFirst, exceptions.countByEngagementId(e.getId()));

        // BEN-010: any raised exception wording stays neutral
        exceptions.findByEngagementIdOrderBySeverityAscExposurePaiseDesc(e.getId()).forEach(x -> {
            assertFalse(x.getReason().toLowerCase().contains("fraud"));
            if (x.getRuleId().equals("BEN-01")) {
                assertTrue(x.getReason().contains("statistical review signal, not a conclusion"));
            }
        });
    }

    /** Local copy of the CLIENT-A mapping profile for this test. */
    static class MappingProfileForTest {
        com.ledgerintegrity.platform.importer.MappingProfile profile() {
            return new com.ledgerintegrity.platform.importer.MappingProfile("client-a-gl",
                    com.ledgerintegrity.platform.importer.MappingProfile.SourceType.CSV, "t",
                    com.ledgerintegrity.platform.importer.MappingProfile.DateFormat.ISO,
                    java.util.Map.ofEntries(
                            java.util.Map.entry(com.ledgerintegrity.platform.importer.MappingProfile.StandardField.VOUCHER_ID, "voucher_id"),
                            java.util.Map.entry(com.ledgerintegrity.platform.importer.MappingProfile.StandardField.VOUCHER_TYPE, "voucher_type"),
                            java.util.Map.entry(com.ledgerintegrity.platform.importer.MappingProfile.StandardField.TXN_DATE, "txn_date"),
                            java.util.Map.entry(com.ledgerintegrity.platform.importer.MappingProfile.StandardField.CREATED_AT, "created_at"),
                            java.util.Map.entry(com.ledgerintegrity.platform.importer.MappingProfile.StandardField.ACCOUNT_CODE, "account_code"),
                            java.util.Map.entry(com.ledgerintegrity.platform.importer.MappingProfile.StandardField.ACCOUNT_NAME, "account_name"),
                            java.util.Map.entry(com.ledgerintegrity.platform.importer.MappingProfile.StandardField.DEBIT, "debit"),
                            java.util.Map.entry(com.ledgerintegrity.platform.importer.MappingProfile.StandardField.CREDIT, "credit"),
                            java.util.Map.entry(com.ledgerintegrity.platform.importer.MappingProfile.StandardField.NARRATION, "narration"),
                            java.util.Map.entry(com.ledgerintegrity.platform.importer.MappingProfile.StandardField.SOURCE, "source"),
                            java.util.Map.entry(com.ledgerintegrity.platform.importer.MappingProfile.StandardField.USER_ID, "user_id"),
                            java.util.Map.entry(com.ledgerintegrity.platform.importer.MappingProfile.StandardField.REVERSAL_OF, "reversal_of")));
        }
    }
}
