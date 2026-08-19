package com.ledgerintegrity.platform.importer.persist;

import com.ledgerintegrity.platform.engagement.Engagement;
import com.ledgerintegrity.platform.engagement.EngagementRepository;
import com.ledgerintegrity.platform.importer.ImportService.SourceFile;
import com.ledgerintegrity.platform.importer.MappingProfile;
import com.ledgerintegrity.platform.importer.MappingProfile.DateFormat;
import com.ledgerintegrity.platform.importer.MappingProfile.SourceType;
import com.ledgerintegrity.platform.importer.MappingProfile.StandardField;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Engagement + import persistence round-trip on in-memory H2 (PostgreSQL mode). */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class EngagementImportPersistenceTest {

    @Autowired EngagementRepository engagements;
    @Autowired EngagementImportService importService;
    @Autowired ImportBatchRepository batches;
    @Autowired LedgerEntryRepository entries;

    private static final String GL = String.join("\n",
            "voucher_id,voucher_type,txn_date,created_at,account_code,account_name,debit,credit,narration,source,user_id,reversal_of",
            "V1,Journal,2024-05-10,,1101,Bank,100.00,,receipt,Manual,U1,",
            "V1,Journal,2024-05-10,,2101,Creditors,,100.00,receipt,Manual,U1,");
    private static final String GL_DELTA = GL + "\n"
            + "V2,Journal,2024-06-01,,1101,Bank,50.00,,later entry,Manual,U1,\n"
            + "V2,Journal,2024-06-01,,2101,Creditors,,50.00,later entry,Manual,U1,";
    private static final String TB =
            "account_code,account_name,opening,debit,credit,closing\n"
                    + "1101,Bank,0,100.00,0,100.00\n"
                    + "2101,Creditors,0,0,100.00,-100.00\n";
    private static final String TB2 =
            "account_code,account_name,opening,debit,credit,closing\n"
                    + "1101,Bank,0,150.00,0,150.00\n"
                    + "2101,Creditors,0,0,150.00,-150.00\n";

    @Test
    void importPersistsPopulationAndDeltaSkipsExistingRows() {
        Engagement e = new Engagement(UUID.randomUUID(), UUID.randomUUID(), "CLIENT-T",
                LocalDate.of(2024, 4, 1), LocalDate.of(2025, 3, 31), LocalDate.of(2025, 3, 31), Instant.now());
        engagements.save(e);

        // first import: 2 rows added
        var r1 = importService.importInto(e.getId(),
                src("gl.csv", GL), src("tb.csv", TB), profile());
        assertEquals(2, r1.addedRows());
        assertEquals(0, r1.skippedRows());
        assertEquals(2, r1.populationCount());
        assertTrue(r1.batch().isBalanced());
        assertTrue(r1.batch().isTbAgrees());

        // delta import: same 2 rows + 2 new -> 2 added, 2 skipped (DAT-006)
        var r2 = importService.importInto(e.getId(),
                src("gl-delta.csv", GL_DELTA), src("tb2.csv", TB2), profile());
        assertEquals(2, r2.addedRows());
        assertEquals(2, r2.skippedRows());
        assertEquals(4, r2.populationCount());

        // persisted state
        assertEquals(4, entries.countByEngagementId(e.getId()));
        assertEquals(2, batches.findByEngagementIdOrderByImportedAtDesc(e.getId()).size());

        // manifest + lineage survive the round-trip
        var batch = batches.findById(r1.batch().getId()).orElseThrow();
        assertEquals(2, batch.getFiles().size());
        assertEquals(64, batch.getFiles().get(0).sha256().length());
        var row = entries.findByEngagementId(e.getId()).get(0).toRow();
        assertNotNull(row.lineage().file());
        assertTrue(row.lineage().row() >= 2);
    }

    @Test
    void importIntoUnknownEngagementFails() {
        assertThrows(IllegalArgumentException.class, () ->
                importService.importInto(UUID.randomUUID(), src("gl.csv", GL), src("tb.csv", TB), profile()));
    }

    private static SourceFile src(String name, String content) {
        return new SourceFile(name, content.getBytes(StandardCharsets.UTF_8));
    }

    private static MappingProfile profile() {
        return new MappingProfile("client-a-gl", SourceType.CSV, "test", DateFormat.ISO, Map.ofEntries(
                Map.entry(StandardField.VOUCHER_ID, "voucher_id"),
                Map.entry(StandardField.VOUCHER_TYPE, "voucher_type"),
                Map.entry(StandardField.TXN_DATE, "txn_date"),
                Map.entry(StandardField.CREATED_AT, "created_at"),
                Map.entry(StandardField.ACCOUNT_CODE, "account_code"),
                Map.entry(StandardField.ACCOUNT_NAME, "account_name"),
                Map.entry(StandardField.DEBIT, "debit"),
                Map.entry(StandardField.CREDIT, "credit"),
                Map.entry(StandardField.NARRATION, "narration"),
                Map.entry(StandardField.SOURCE, "source"),
                Map.entry(StandardField.USER_ID, "user_id"),
                Map.entry(StandardField.REVERSAL_OF, "reversal_of")));
    }
}
