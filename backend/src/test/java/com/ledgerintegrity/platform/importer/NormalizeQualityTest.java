package com.ledgerintegrity.platform.importer;

import com.ledgerintegrity.platform.common.Csv;
import com.ledgerintegrity.platform.importer.model.QualityIssue;
import com.ledgerintegrity.platform.importer.model.QualityIssue.IssueType;
import com.ledgerintegrity.platform.importer.model.QualityReport;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

class NormalizeQualityTest {

    private final NormalizeService normalize = new NormalizeService();
    private final QualityService quality = new QualityService();

    private static final String BAD_CSV = String.join("\n",
            TestData.GL_HEADER,
            "V1,Journal,2024-05-10,,1101,Bank,100.00,,ok line dr,Manual,U1,",
            "V1,Journal,2024-05-10,,2101,Creditors,,100.00,ok line cr,Manual,U1,",
            "V2,Journal,2024-99-99,,1101,Bank,50.00,,bad date,Manual,U1,",        // INVALID_DATE
            "V3,Journal,2024-05-11,,1101,Bank,abc,,bad amount,Manual,U1,",        // NON_NUMERIC_AMOUNT + NO_AMOUNT
            "V4,Journal,2024-05-12,,,Missing acct,10.00,,missing account code,Manual,U1,", // MISSING_REQUIRED_FIELD (broken)
            "V5,Journal,2024-05-13,,1101,Bank,25.00,25.00,both sides,Manual,U1,", // BOTH_DEBIT_AND_CREDIT
            "V6,Journal,2024-05-14,,1101,Bank,75.00,,dup identity,Manual,U1,",
            "V6,Journal,2024-05-14,,1101,Bank,75.00,,dup identity,Manual,U1,");   // DUPLICATE_ROW_IDENTITY

    @Test
    void recordsEveryCategoryOfQualityIssueWithoutSilentDrops() {
        NormalizeService.GlResult result = normalize.normalizeGl(Csv.parse(BAD_CSV), TestData.clientAProfile(), "bad.csv");
        Set<IssueType> types = result.issues().stream().map(QualityIssue::type).collect(Collectors.toSet());
        assertTrue(types.contains(IssueType.INVALID_DATE));
        assertTrue(types.contains(IssueType.NON_NUMERIC_AMOUNT));
        assertTrue(types.contains(IssueType.MISSING_REQUIRED_FIELD));
        assertTrue(types.contains(IssueType.BOTH_DEBIT_AND_CREDIT));
        assertTrue(types.contains(IssueType.NO_AMOUNT));

        // broken rows (bad date, missing account) excluded but accounted for
        assertEquals(8, result.totalRows());
        assertEquals(6, result.rows().size());

        // lineage points at real source lines
        QualityIssue badDate = result.issues().stream()
                .filter(i -> i.type() == IssueType.INVALID_DATE).findFirst().orElseThrow();
        assertEquals("bad.csv", badDate.lineage().file());
        assertEquals(4, badDate.lineage().row());

        // duplicates found on normalised rows
        List<QualityIssue> dups = quality.findDuplicateIdentities(result.rows());
        assertEquals(1, dups.size());
        assertEquals(9, dups.get(0).lineage().row());
    }

    @Test
    void qualityReportAggregatesCountsByType() {
        NormalizeService.GlResult result = normalize.normalizeGl(Csv.parse(BAD_CSV), TestData.clientAProfile(), "bad.csv");
        QualityReport report = quality.buildReport("bad.csv", result.totalRows(), result.rows().size(), result.issues());
        assertEquals(1L, report.summary().get(IssueType.INVALID_DATE));
        assertTrue(report.summary().get(IssueType.MISSING_REQUIRED_FIELD) >= 1L);
    }
}
