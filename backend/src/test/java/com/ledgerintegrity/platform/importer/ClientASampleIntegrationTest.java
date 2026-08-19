package com.ledgerintegrity.platform.importer;

import com.ledgerintegrity.platform.importer.ImportService.PipelineResult;
import com.ledgerintegrity.platform.importer.ImportService.SourceFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** Full pipeline against the Phase 0 synthetic CLIENT-A population. */
@EnabledIf("sampleDataPresent")
class ClientASampleIntegrationTest {

    private static final Path SAMPLE = Path.of("..", "phase0", "sample-data");

    static boolean sampleDataPresent() {
        return Files.exists(SAMPLE.resolve("general_ledger.csv"));
    }

    @Test
    void clientASampleNormalisesCleanBalancesAndAgreesToTb() throws IOException {
        ImportService service = new ImportService(new NormalizeService(), new QualityService(), new ValidationService());
        PipelineResult result = service.run(
                new SourceFile("general_ledger.csv", Files.readAllBytes(SAMPLE.resolve("general_ledger.csv"))),
                new SourceFile("trial_balance.csv", Files.readAllBytes(SAMPLE.resolve("trial_balance.csv"))),
                TestData.clientAProfile());

        assertEquals(3008, result.qualityReport().totalRows());
        assertEquals(3008, result.population().size()); // nothing dropped
        assertEquals(0, result.qualityReport().issues().size()); // synthetic data is clean

        var v = result.validation();
        assertTrue(v.balanced());
        assertEquals(0, v.voucherImbalances().size());
        assertTrue(v.tbAgrees());
        assertEquals(v.totalDebit(), v.totalCredit());

        // manifest carries checksums for reproducibility (DAT-001)
        assertEquals(2, result.manifest().size());
        assertEquals(64, result.manifest().get(0).sha256().length());
    }
}
