/** DAT-003: aggregate per-row issues into a downloadable data-quality report. */
import type { LedgerRow, QualityIssue, QualityReport, QualityIssueType, TrialBalanceRow } from './types.js';
import { sha256Hex } from '../core/checksum.js';
import { serializeCsv } from '../core/csv.js';

/** Duplicate row identity: same voucher + account + amounts + date appearing on multiple source rows. */
export function findDuplicateIdentities(rows: LedgerRow[]): QualityIssue[] {
  const seen = new Map<string, LedgerRow>();
  const issues: QualityIssue[] = [];
  for (const r of rows) {
    const key = sha256Hex([r.voucherId, r.txnDate, r.accountCode, r.debit ?? '', r.credit ?? '', r.narration].join('|'));
    const first = seen.get(key);
    if (first) {
      issues.push({
        type: 'duplicate_row_identity',
        message: `Row duplicates ${first.lineage.file}:${first.lineage.row} (voucher ${r.voucherId}, identical account/amount/date/narration).`,
        lineage: r.lineage,
      });
    } else seen.set(key, r);
  }
  return issues;
}

/** Accounts used in the ledger but absent from the trial balance (unmapped accounts). */
export function findUnmappedAccounts(rows: LedgerRow[], tb: TrialBalanceRow[]): QualityIssue[] {
  const known = new Set(tb.map((t) => t.accountCode));
  const reported = new Set<string>();
  const issues: QualityIssue[] = [];
  for (const r of rows) {
    if (!known.has(r.accountCode) && !reported.has(r.accountCode)) {
      reported.add(r.accountCode);
      issues.push({
        type: 'unmapped_account', field: 'accountCode', value: r.accountCode,
        message: `Account ${r.accountCode} "${r.accountName}" appears in the ledger but not in the trial balance (first at ${r.lineage.file}:${r.lineage.row}).`,
        lineage: r.lineage,
      });
    }
  }
  return issues;
}

export function buildQualityReport(file: string, totalRows: number, cleanRows: number, issues: QualityIssue[]): QualityReport {
  const summary: Partial<Record<QualityIssueType, number>> = {};
  for (const i of issues) summary[i.type] = (summary[i.type] ?? 0) + 1;
  return { file, totalRows, cleanRows, issues, summary };
}

/** CSV export of the report — "a downloadable data-quality report is produced for every import" (DAT-003). */
export function qualityReportCsv(report: QualityReport): string {
  return serializeCsv(
    ['file', 'row', 'issue_type', 'field', 'value', 'message'],
    report.issues.map((i) => [i.lineage.file, i.lineage.row, i.type, i.field ?? '', i.value ?? '', i.message]),
  );
}
