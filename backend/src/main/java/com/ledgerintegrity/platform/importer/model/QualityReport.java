package com.ledgerintegrity.platform.importer.model;

import java.util.List;
import java.util.Map;

/** DAT-003: aggregated, downloadable data-quality report for one import. */
public record QualityReport(
        String file,
        int totalRows,
        int cleanRows,
        List<QualityIssue> issues,
        Map<QualityIssue.IssueType, Long> summary
) {}
