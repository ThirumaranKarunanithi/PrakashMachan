package com.ledgerintegrity.platform.importer;

import com.ledgerintegrity.platform.engagement.Engagement;
import com.ledgerintegrity.platform.engagement.EngagementRepository;
import com.ledgerintegrity.platform.importer.ImportService.SourceFile;
import com.ledgerintegrity.platform.importer.model.QualityIssue.IssueType;
import com.ledgerintegrity.platform.importer.persist.EngagementImportService;
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

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:tallytestdb;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
@EnabledIf("sampleDataPresent")
class TallyImportIntegrationTest {

    private static final Path SAMPLE = Path.of("..", "phase0", "sample-data");

    static boolean sampleDataPresent() {
        return Files.exists(SAMPLE.resolve("tally_sample.xml"));
    }

    @Autowired EngagementRepository engagements;
    @Autowired EngagementImportService importService;
    @Autowired RuleEngineService engine;

    @Test
    void importsTallyDaybookXmlDeltaSafeAndRulesRunOnIt() throws IOException {
        Engagement e = new Engagement(UUID.randomUUID(), UUID.randomUUID(), "TALLY-CLIENT",
                LocalDate.of(2024, 4, 1), LocalDate.of(2025, 3, 31), LocalDate.of(2025, 3, 31), Instant.now());
        engagements.save(e);
        byte[] xml = Files.readAllBytes(SAMPLE.resolve("tally_sample.xml"));

        var r = importService.importTallyInto(e.getId(), new SourceFile("tally_sample.xml", xml), null);
        // 3 vouchers, 6 ledger lines, ledger name doubles as account code
        assertEquals(6, r.addedRows());
        assertEquals(6, r.populationCount());
        var v = r.pipeline().validation();
        assertTrue(v.balanced()); // Tally sign convention decoded correctly
        assertEquals(0, v.voucherImbalances().size());
        // no TB supplied: the check is vacuous but the limitation is recorded (DAT-002)
        assertTrue(v.tbAgrees());
        assertTrue(r.pipeline().qualityReport().issues().stream()
                .anyMatch(i -> i.type() == IssueType.TB_NOT_PROVIDED));
        // manifest carries the file checksum (DAT-001)
        assertEquals(1, r.pipeline().manifest().size());
        assertEquals(64, r.pipeline().manifest().get(0).sha256().length());

        // delta-safe re-import (DAT-006)
        var again = importService.importTallyInto(e.getId(), new SourceFile("tally_sample.xml", xml), null);
        assertEquals(0, again.addedRows());
        assertEquals(6, again.skippedRows());

        // the rule pack runs on Tally data: TLY-JRN-001 posts a provision at close (PET-04)
        var run = engine.run(e.getId(), RuleParams.defaults().withPrivilegedUsers(Set.of()));
        assertTrue(run.created().stream().anyMatch(x ->
                x.getRuleId().equals("PET-04") && x.getVoucherIds().contains("TLY-JRN-001")));

        // a malformed file yields issues, not an exception
        var bad = importService.importTallyInto(e.getId(),
                new SourceFile("bad.xml", "<not-tally>".getBytes(StandardCharsets.UTF_8)), null);
        assertEquals(0, bad.addedRows());
        assertFalse(bad.pipeline().qualityReport().issues().isEmpty());
    }
}
