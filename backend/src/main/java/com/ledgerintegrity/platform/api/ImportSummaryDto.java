package com.ledgerintegrity.platform.api;

import com.ledgerintegrity.platform.importer.ImportService.ManifestEntry;
import com.ledgerintegrity.platform.importer.model.QualityIssue;
import com.ledgerintegrity.platform.importer.model.ValidationResult;
import com.ledgerintegrity.platform.importer.persist.EngagementImportService.EngagementImportResult;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * API view of an import: everything the review screen needs, without shipping the whole
 * normalised population. Issues are capped; the full list is the CSV download (DAT-003).
 */
public record ImportSummaryDto(
        String importId,
        String engagementId,
        String profile,
        List<ManifestEntry> files,
        int totalRows,
        int cleanRows,
        int addedRows,
        int skippedRows,
        long populationCount,
        int issueCount,
        Map<String, Long> issueSummary,
        List<IssueDto> issues,
        boolean issuesTruncated,
        long totalDebitPaise,
        long totalCreditPaise,
        boolean balanced,
        int voucherImbalanceCount,
        List<ValidationResult.VoucherImbalance> voucherImbalances,
        boolean tbAgrees,
        List<ValidationResult.TbDifference> tbDifferences,
        /** DAT-002: analysis must not start until the population is usable */
        boolean readyForAnalysis
) {
    private static final int ISSUE_CAP = 500;
    private static final int LIST_CAP = 50;

    public record IssueDto(String file, int row, String type, String field, String value, String message) {
        static IssueDto from(QualityIssue i) {
            return new IssueDto(i.lineage().file(), i.lineage().row(), i.type().name(),
                    i.field(), i.value(), i.message());
        }
    }

    public static ImportSummaryDto from(EngagementImportResult r) {
        List<QualityIssue> all = r.pipeline().qualityReport().issues();
        ValidationResult v = r.pipeline().validation();
        return new ImportSummaryDto(
                r.batch().getId().toString(),
                r.batch().getEngagementId().toString(),
                r.pipeline().profile(),
                r.pipeline().manifest(),
                r.pipeline().qualityReport().totalRows(),
                r.pipeline().qualityReport().cleanRows(),
                r.addedRows(),
                r.skippedRows(),
                r.populationCount(),
                all.size(),
                r.pipeline().qualityReport().summary().entrySet().stream()
                        .collect(Collectors.toMap(e -> e.getKey().name(), Map.Entry::getValue)),
                all.stream().limit(ISSUE_CAP).map(IssueDto::from).toList(),
                all.size() > ISSUE_CAP,
                v.totalDebit(),
                v.totalCredit(),
                v.balanced(),
                v.voucherImbalances().size(),
                v.voucherImbalances().stream().limit(LIST_CAP).toList(),
                v.tbAgrees(),
                v.tbDifferences().stream().limit(LIST_CAP).toList(),
                v.balanced() && v.tbAgrees()
        );
    }
}
