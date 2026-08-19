package com.ledgerintegrity.platform.importer;

import com.ledgerintegrity.platform.common.Checksums;
import com.ledgerintegrity.platform.common.Csv;
import com.ledgerintegrity.platform.importer.model.LedgerRow;
import com.ledgerintegrity.platform.importer.model.QualityIssue;
import com.ledgerintegrity.platform.importer.model.QualityIssue.IssueType;
import com.ledgerintegrity.platform.importer.model.QualityReport;
import com.ledgerintegrity.platform.importer.model.TrialBalanceRow;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** DAT-003: aggregate per-row issues into a downloadable data-quality report. */
@Service
public class QualityService {

    /** Duplicate row identity: same voucher + account + amounts + date appearing on multiple source rows. */
    public List<QualityIssue> findDuplicateIdentities(List<LedgerRow> rows) {
        Map<String, LedgerRow> seen = new HashMap<>();
        List<QualityIssue> issues = new ArrayList<>();
        for (LedgerRow r : rows) {
            String key = Checksums.sha256Hex(String.join("|",
                    r.voucherId(), String.valueOf(r.txnDate()), r.accountCode(),
                    String.valueOf(r.debit()), String.valueOf(r.credit()), r.narration()));
            LedgerRow first = seen.putIfAbsent(key, r);
            if (first != null) {
                issues.add(new QualityIssue(IssueType.DUPLICATE_ROW_IDENTITY, null, null,
                        "Row duplicates " + first.lineage() + " (voucher " + r.voucherId()
                                + ", identical account/amount/date/narration).",
                        r.lineage()));
            }
        }
        return issues;
    }

    /** Accounts used in the ledger but absent from the trial balance. */
    public List<QualityIssue> findUnmappedAccounts(List<LedgerRow> rows, List<TrialBalanceRow> tb) {
        Set<String> known = new HashSet<>();
        for (TrialBalanceRow t : tb) known.add(t.accountCode());
        Set<String> reported = new HashSet<>();
        List<QualityIssue> issues = new ArrayList<>();
        for (LedgerRow r : rows) {
            if (!known.contains(r.accountCode()) && reported.add(r.accountCode())) {
                issues.add(new QualityIssue(IssueType.UNMAPPED_ACCOUNT, "ACCOUNT_CODE", r.accountCode(),
                        "Account " + r.accountCode() + " \"" + r.accountName()
                                + "\" appears in the ledger but not in the trial balance (first at " + r.lineage() + ").",
                        r.lineage()));
            }
        }
        return issues;
    }

    public QualityReport buildReport(String file, int totalRows, int cleanRows, List<QualityIssue> issues) {
        Map<IssueType, Long> summary = new EnumMap<>(IssueType.class);
        for (QualityIssue i : issues) summary.merge(i.type(), 1L, Long::sum);
        return new QualityReport(file, totalRows, cleanRows, issues, summary);
    }

    /** CSV export — "a downloadable data-quality report is produced for every import" (DAT-003). */
    public String reportCsv(QualityReport report) {
        List<List<String>> rows = new ArrayList<>();
        for (QualityIssue i : report.issues()) {
            rows.add(List.of(
                    i.lineage().file(), String.valueOf(i.lineage().row()), i.type().name(),
                    i.field() == null ? "" : i.field(),
                    i.value() == null ? "" : i.value(),
                    i.message()));
        }
        return Csv.serialize(List.of("file", "row", "issue_type", "field", "value", "message"), rows);
    }
}
