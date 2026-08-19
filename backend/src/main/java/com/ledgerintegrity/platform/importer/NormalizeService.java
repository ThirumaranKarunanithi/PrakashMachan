package com.ledgerintegrity.platform.importer;

import com.ledgerintegrity.platform.common.Csv;
import com.ledgerintegrity.platform.importer.MappingProfile.StandardField;
import com.ledgerintegrity.platform.importer.model.LedgerRow;
import com.ledgerintegrity.platform.importer.model.Lineage;
import com.ledgerintegrity.platform.importer.model.QualityIssue;
import com.ledgerintegrity.platform.importer.model.QualityIssue.IssueType;
import com.ledgerintegrity.platform.importer.model.TrialBalanceRow;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalisation: source CSV rows -> standard LedgerRow list with lineage (DAT-005),
 * recording per-row quality issues (feeds DAT-003) instead of silently dropping data.
 * Rows too broken to represent are excluded but always accounted for in the issues.
 */
@Service
public class NormalizeService {

    public record GlResult(List<LedgerRow> rows, List<QualityIssue> issues, int totalRows) {}
    public record TbResult(List<TrialBalanceRow> rows, List<QualityIssue> issues) {}

    public GlResult normalizeGl(Csv.Table table, MappingProfile profile, String fileName) {
        List<QualityIssue> issues = new ArrayList<>();
        List<LedgerRow> rows = new ArrayList<>();
        Map<String, Integer> colIndex = new HashMap<>();
        for (int i = 0; i < table.header().size(); i++) colIndex.put(table.header().get(i), i);

        List<List<String>> data = table.rows();
        for (int i = 0; i < data.size(); i++) {
            List<String> raw = data.get(i);
            Lineage lineage = new Lineage(fileName, i + 2);
            boolean broken = false;

            // required text fields
            for (StandardField field : MappingProfile.REQUIRED_FIELDS) {
                if (field == StandardField.TXN_DATE) continue; // date checked below
                String v = get(raw, colIndex, profile, field);
                if (v.isEmpty()) {
                    issues.add(new QualityIssue(IssueType.MISSING_REQUIRED_FIELD, field.name(), null,
                            field == StandardField.NARRATION
                                    ? "Narration is blank."
                                    : "Row is missing required field \"" + field + "\".",
                            lineage));
                    if (field == StandardField.VOUCHER_ID || field == StandardField.ACCOUNT_CODE) broken = true;
                }
            }

            // date
            String rawDate = get(raw, colIndex, profile, StandardField.TXN_DATE);
            LocalDate txnDate = rawDate.isEmpty() ? null : profile.parseDate(rawDate);
            if (txnDate == null) {
                issues.add(new QualityIssue(IssueType.INVALID_DATE, "TXN_DATE", rawDate,
                        "Transaction date \"" + rawDate + "\" is not a valid " + profile.dateFormat() + " date.",
                        lineage));
                broken = true;
            }

            // optional creation timestamp
            LocalDateTime createdAt = null;
            String rawTs = get(raw, colIndex, profile, StandardField.CREATED_AT);
            if (!rawTs.isEmpty()) {
                createdAt = MappingProfile.parseTimestamp(rawTs);
                if (createdAt == null) {
                    issues.add(new QualityIssue(IssueType.INVALID_TIMESTAMP, "CREATED_AT", rawTs,
                            "Creation timestamp \"" + rawTs + "\" is not a valid timestamp.", lineage));
                }
            }

            // amounts
            Long debit = parseSide(raw, colIndex, profile, StandardField.DEBIT, lineage, issues);
            Long credit = parseSide(raw, colIndex, profile, StandardField.CREDIT, lineage, issues);
            if (debit != null && credit != null) {
                issues.add(new QualityIssue(IssueType.BOTH_DEBIT_AND_CREDIT, null, null,
                        "Line carries both a debit and a credit amount.", lineage));
            }
            if (debit == null && credit == null) {
                issues.add(new QualityIssue(IssueType.NO_AMOUNT, null, null,
                        "Line has neither a debit nor a credit amount.", lineage));
            }

            if (broken) continue; // excluded, but recorded above

            rows.add(new LedgerRow(
                    get(raw, colIndex, profile, StandardField.VOUCHER_ID),
                    get(raw, colIndex, profile, StandardField.VOUCHER_TYPE),
                    txnDate,
                    createdAt,
                    get(raw, colIndex, profile, StandardField.ACCOUNT_CODE),
                    get(raw, colIndex, profile, StandardField.ACCOUNT_NAME),
                    debit, credit,
                    get(raw, colIndex, profile, StandardField.NARRATION),
                    emptyToNull(get(raw, colIndex, profile, StandardField.SOURCE)),
                    emptyToNull(get(raw, colIndex, profile, StandardField.USER_ID)),
                    emptyToNull(get(raw, colIndex, profile, StandardField.REVERSAL_OF)),
                    lineage));
        }
        return new GlResult(rows, issues, data.size());
    }

    /** Trial balance normaliser — fixed standard header (exported by our tooling or mapped upstream). */
    public TbResult normalizeTb(Csv.Table table, String fileName) {
        List<QualityIssue> issues = new ArrayList<>();
        List<TrialBalanceRow> rows = new ArrayList<>();
        List<String> need = List.of("account_code", "account_name", "opening", "debit", "credit", "closing");
        Map<String, Integer> idx = new HashMap<>();
        for (int i = 0; i < table.header().size(); i++) idx.put(table.header().get(i), i);
        for (String col : need) {
            if (!idx.containsKey(col)) {
                issues.add(new QualityIssue(IssueType.UNMAPPED_COLUMN, col, null,
                        "Trial balance is missing column \"" + col + "\".", new Lineage(fileName, 1)));
            }
        }
        if (!issues.isEmpty()) return new TbResult(rows, issues);

        List<List<String>> data = table.rows();
        for (int i = 0; i < data.size(); i++) {
            List<String> r = data.get(i);
            Lineage lineage = new Lineage(fileName, i + 2);
            rows.add(new TrialBalanceRow(
                    cell(r, idx.get("account_code")),
                    cell(r, idx.get("account_name")),
                    tbAmount(r, idx, "opening", lineage, issues),
                    tbAmount(r, idx, "debit", lineage, issues),
                    tbAmount(r, idx, "credit", lineage, issues),
                    tbAmount(r, idx, "closing", lineage, issues),
                    lineage));
        }
        return new TbResult(rows, issues);
    }

    // ---------- helpers ----------

    private static String get(List<String> raw, Map<String, Integer> colIndex, MappingProfile profile, StandardField field) {
        String col = profile.fieldMap().get(field);
        if (col == null) return "";
        Integer idx = colIndex.get(col);
        if (idx == null || idx >= raw.size()) return "";
        return raw.get(idx).trim();
    }

    private static Long parseSide(List<String> raw, Map<String, Integer> colIndex, MappingProfile profile,
                                  StandardField field, Lineage lineage, List<QualityIssue> issues) {
        String rawAmt = get(raw, colIndex, profile, field);
        try {
            Long p = MappingProfile.parseAmountPaise(rawAmt);
            return (p == null || p == 0L) ? null : p; // treat explicit 0 as empty side
        } catch (NumberFormatException e) {
            issues.add(new QualityIssue(IssueType.NON_NUMERIC_AMOUNT, field.name(), rawAmt,
                    field + " value \"" + rawAmt + "\" is not a number.", lineage));
            return null;
        }
    }

    private static long tbAmount(List<String> r, Map<String, Integer> idx, String col,
                                 Lineage lineage, List<QualityIssue> issues) {
        String raw = cell(r, idx.get(col));
        try {
            Long p = MappingProfile.parseAmountPaise(raw);
            return p == null ? 0L : p;
        } catch (NumberFormatException e) {
            issues.add(new QualityIssue(IssueType.NON_NUMERIC_AMOUNT, col, raw,
                    "Trial balance " + col + " is not a number.", lineage));
            return 0L;
        }
    }

    private static String cell(List<String> r, Integer idx) {
        return (idx == null || idx >= r.size()) ? "" : r.get(idx).trim();
    }

    private static String emptyToNull(String s) {
        return s.isEmpty() ? null : s;
    }
}
