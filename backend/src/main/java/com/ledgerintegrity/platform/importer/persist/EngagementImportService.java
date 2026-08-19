package com.ledgerintegrity.platform.importer.persist;

import com.ledgerintegrity.platform.engagement.Engagement;
import com.ledgerintegrity.platform.engagement.EngagementRepository;
import com.ledgerintegrity.platform.importer.DeltaService;
import com.ledgerintegrity.platform.importer.ImportService;
import com.ledgerintegrity.platform.importer.ImportService.PipelineResult;
import com.ledgerintegrity.platform.importer.ImportService.SourceFile;
import com.ledgerintegrity.platform.importer.MappingProfile;
import com.ledgerintegrity.platform.importer.NormalizeService;
import com.ledgerintegrity.platform.importer.QualityService;
import com.ledgerintegrity.platform.importer.TallyXmlParser;
import com.ledgerintegrity.platform.importer.ValidationService;
import com.ledgerintegrity.platform.importer.model.LedgerRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates an engagement import: run the stateless pipeline, apply delta dedup
 * against the persisted population (DAT-006), then persist the batch, its quality
 * issues and the new ledger entries in one transaction.
 */
@Service
public class EngagementImportService {

    public record EngagementImportResult(
            ImportBatch batch,
            PipelineResult pipeline,
            int addedRows,
            int skippedRows,
            long populationCount
    ) {}

    private final ImportService importService;
    private final DeltaService deltaService;
    private final NormalizeService normalizeService;
    private final QualityService qualityService;
    private final ValidationService validationService;
    private final EngagementRepository engagements;
    private final ImportBatchRepository batches;
    private final LedgerEntryRepository entries;

    public EngagementImportService(ImportService importService,
                                   DeltaService deltaService,
                                   NormalizeService normalizeService,
                                   QualityService qualityService,
                                   ValidationService validationService,
                                   EngagementRepository engagements,
                                   ImportBatchRepository batches,
                                   LedgerEntryRepository entries) {
        this.importService = importService;
        this.deltaService = deltaService;
        this.normalizeService = normalizeService;
        this.qualityService = qualityService;
        this.validationService = validationService;
        this.engagements = engagements;
        this.batches = batches;
        this.entries = entries;
    }

    @Transactional
    public EngagementImportResult importInto(UUID engagementId, SourceFile gl, SourceFile tb, MappingProfile profile) {
        Engagement engagement = engagements.findById(engagementId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown engagement: " + engagementId));
        PipelineResult pipeline = importService.run(gl, tb, profile);
        return persist(engagement, profile.name(), pipeline);
    }

    /** BRD §4.1: Excel import — .xlsx files for GL and/or TB, parsed to the same table shape. */
    @Transactional
    public EngagementImportResult importXlsxInto(UUID engagementId, SourceFile gl, SourceFile tb,
                                                 MappingProfile profile) {
        Engagement engagement = engagements.findById(engagementId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown engagement: " + engagementId));
        try {
            com.ledgerintegrity.platform.common.Csv.Table glTable = gl.name().toLowerCase().endsWith(".xlsx")
                    ? com.ledgerintegrity.platform.importer.XlsxConverter.toTable(gl.content())
                    : com.ledgerintegrity.platform.common.Csv.parse(
                            new String(gl.content(), java.nio.charset.StandardCharsets.UTF_8));
            com.ledgerintegrity.platform.common.Csv.Table tbTable = tb.name().toLowerCase().endsWith(".xlsx")
                    ? com.ledgerintegrity.platform.importer.XlsxConverter.toTable(tb.content())
                    : com.ledgerintegrity.platform.common.Csv.parse(
                            new String(tb.content(), java.nio.charset.StandardCharsets.UTF_8));
            PipelineResult pipeline = importService.runParsed(gl, glTable, tb, tbTable, profile);
            return persist(engagement, profile.name(), pipeline);
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("The file could not be read as an .xlsx workbook: " + e.getMessage());
        }
    }

    /**
     * BRD §4.1: Tally XML import. The daybook export carries no trial balance; passing
     * one (CSV) alongside restores the full DAT-002 comparison, otherwise the limitation
     * is recorded as a quality issue.
     */
    @Transactional
    public EngagementImportResult importTallyInto(UUID engagementId, SourceFile xml, SourceFile tbOrNull) {
        Engagement engagement = engagements.findById(engagementId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown engagement: " + engagementId));

        TallyXmlParser.ParseResult parsed = TallyXmlParser.parse(xml.content(), xml.name());
        com.ledgerintegrity.platform.importer.NormalizeService.TbResult tb = tbOrNull == null
                ? new com.ledgerintegrity.platform.importer.NormalizeService.TbResult(java.util.List.of(), java.util.List.of())
                : normalizeService.normalizeTb(
                        com.ledgerintegrity.platform.common.Csv.parse(
                                new String(tbOrNull.content(), java.nio.charset.StandardCharsets.UTF_8)),
                        tbOrNull.name());

        java.util.List<com.ledgerintegrity.platform.importer.model.QualityIssue> issues =
                new java.util.ArrayList<>(parsed.issues());
        issues.addAll(tb.issues());
        issues.addAll(qualityService.findDuplicateIdentities(parsed.rows()));
        if (tbOrNull != null) {
            issues.addAll(qualityService.findUnmappedAccounts(parsed.rows(), tb.rows()));
        } else {
            issues.add(new com.ledgerintegrity.platform.importer.model.QualityIssue(
                    com.ledgerintegrity.platform.importer.model.QualityIssue.IssueType.TB_NOT_PROVIDED,
                    null, null,
                    "No trial balance was provided with the Tally XML — the ledger-vs-TB completeness check (DAT-002) could not run.",
                    new com.ledgerintegrity.platform.importer.model.Lineage(xml.name(), 1)));
        }
        var report = qualityService.buildReport(xml.name(), parsed.rows().size(), parsed.rows().size(), issues);
        var validation = validationService.validate(parsed.rows(), tb.rows(), tbOrNull != null);

        java.time.Instant now = java.time.Instant.now();
        java.util.List<ImportService.ManifestEntry> manifest = new java.util.ArrayList<>();
        manifest.add(new ImportService.ManifestEntry(xml.name(), xml.content().length,
                com.ledgerintegrity.platform.common.Checksums.sha256Hex(xml.content()), parsed.rows().size(), now));
        if (tbOrNull != null) {
            manifest.add(new ImportService.ManifestEntry(tbOrNull.name(), tbOrNull.content().length,
                    com.ledgerintegrity.platform.common.Checksums.sha256Hex(tbOrNull.content()), tb.rows().size(), now));
        }
        PipelineResult pipeline = new PipelineResult("tally-xml", manifest, report, validation, parsed.rows());
        return persist(engagement, "tally-xml", pipeline);
    }

    private EngagementImportResult persist(Engagement engagement, String profileName, PipelineResult pipeline) {
        // DAT-006: skip rows already in the engagement population
        Set<String> existing = new HashSet<>(entries.findIdentityHashes(engagement.getId()));
        DeltaService.DeltaResult delta = deltaService.deltaImport(existing, pipeline.population());

        ImportBatch batch = new ImportBatch(UUID.randomUUID(), engagement.getId(), profileName, Instant.now());
        batch.setCounts(
                pipeline.qualityReport().totalRows(),
                pipeline.qualityReport().cleanRows(),
                delta.added().size(),
                delta.skipped());
        var v = pipeline.validation();
        batch.setValidation(v.totalDebit(), v.totalCredit(), v.balanced(), v.tbAgrees(),
                v.voucherImbalances().size(), v.tbDifferences().size());
        pipeline.manifest().forEach(m -> batch.getFiles()
                .add(new ImportBatch.SourceFileRef(m.file(), m.bytes(), m.sha256(), m.rows())));
        pipeline.qualityReport().issues().forEach(i -> batch.getIssues()
                .add(new ImportBatch.QualityIssueRef(
                        i.type().name(), i.field(), i.value(), i.message(),
                        i.lineage().file(), i.lineage().row())));
        batches.save(batch);

        List<LedgerEntry> newEntries = delta.added().stream()
                .map((LedgerRow r) -> LedgerEntry.from(r, engagement.getId(), batch.getId(), deltaService.rowIdentity(r)))
                .toList();
        entries.saveAll(newEntries);

        return new EngagementImportResult(batch, pipeline, delta.added().size(), delta.skipped(),
                entries.countByEngagementId(engagement.getId()));
    }
}
