package com.ledgerintegrity.platform.importer;

import com.ledgerintegrity.platform.common.Checksums;
import com.ledgerintegrity.platform.common.Csv;
import com.ledgerintegrity.platform.importer.model.LedgerRow;
import com.ledgerintegrity.platform.importer.model.QualityIssue;
import com.ledgerintegrity.platform.importer.model.QualityReport;
import com.ledgerintegrity.platform.importer.model.ValidationResult;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Stateless import pipeline: parse -> profile check (DAT-004) -> normalise with
 * lineage (DAT-005) -> quality report (DAT-003) -> validation (DAT-002) -> manifest (DAT-001).
 * Persistence is the orchestrator's job (EngagementImportService).
 */
@Service
public class ImportService {

    public record SourceFile(String name, byte[] content) {}

    public record ManifestEntry(String file, long bytes, String sha256, int rows, Instant importedAt) {}

    public record PipelineResult(
            String profile,
            List<ManifestEntry> manifest,
            QualityReport qualityReport,
            ValidationResult validation,
            List<LedgerRow> population
    ) {}

    private final NormalizeService normalizeService;
    private final QualityService qualityService;
    private final ValidationService validationService;

    public ImportService(NormalizeService normalizeService,
                         QualityService qualityService,
                         ValidationService validationService) {
        this.normalizeService = normalizeService;
        this.qualityService = qualityService;
        this.validationService = validationService;
    }

    /** Thrown when the mapping profile does not fit the uploaded file (DAT-004). */
    public static class ProfileMismatchException extends RuntimeException {
        private final List<String> problems;
        public ProfileMismatchException(List<String> problems) {
            super("Mapping profile does not match the uploaded file: " + String.join(" | ", problems));
            this.problems = problems;
        }
        public List<String> problems() { return problems; }
    }

    public PipelineResult run(SourceFile gl, SourceFile tb, MappingProfile profile) {
        return runParsed(gl, Csv.parse(new String(gl.content(), StandardCharsets.UTF_8)),
                tb, Csv.parse(new String(tb.content(), StandardCharsets.UTF_8)), profile);
    }

    /** Pre-parsed variant so Excel workbooks keep their ORIGINAL bytes in the manifest (DAT-001). */
    public PipelineResult runParsed(SourceFile gl, Csv.Table glTable, SourceFile tb, Csv.Table tbTable,
                                    MappingProfile profile) {

        // 0. mapping profile check (DAT-004)
        List<String> problems = profile.checkAgainstHeader(glTable.header());
        if (!problems.isEmpty()) throw new ProfileMismatchException(problems);

        // 1. normalise with lineage (DAT-005)
        NormalizeService.GlResult glResult = normalizeService.normalizeGl(glTable, profile, gl.name());
        NormalizeService.TbResult tbResult = normalizeService.normalizeTb(tbTable, tb.name());

        // 2. data-quality report (DAT-003)
        List<QualityIssue> issues = new ArrayList<>(glResult.issues());
        issues.addAll(tbResult.issues());
        issues.addAll(qualityService.findDuplicateIdentities(glResult.rows()));
        issues.addAll(qualityService.findUnmappedAccounts(glResult.rows(), tbResult.rows()));
        QualityReport report = qualityService.buildReport(
                gl.name(), glResult.totalRows(), glResult.rows().size(), issues);

        // 3. validation (DAT-002)
        ValidationResult validation = validationService.validate(glResult.rows(), tbResult.rows());

        // 4. source manifest (DAT-001)
        Instant now = Instant.now();
        List<ManifestEntry> manifest = List.of(
                new ManifestEntry(gl.name(), gl.content().length, Checksums.sha256Hex(gl.content()), glResult.totalRows(), now),
                new ManifestEntry(tb.name(), tb.content().length, Checksums.sha256Hex(tb.content()), tbTable.rows().size(), now));

        return new PipelineResult(profile.name(), manifest, report, validation, glResult.rows());
    }
}
