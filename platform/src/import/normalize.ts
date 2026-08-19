/**
 * Normalisation: source CSV rows -> standard LedgerRow[] with lineage (DAT-005),
 * recording per-row quality issues (feeds DAT-003) instead of silently dropping data.
 * Rows with structural problems are still emitted where possible so counts reconcile;
 * rows too broken to represent are excluded but always accounted for in issues.
 */
import type { CsvTable } from '../core/csv.js';
import type { LedgerRow, QualityIssue, TrialBalanceRow } from './types.js';
import {
  MappingProfile, REQUIRED_GL_FIELDS,
  parseAmountPaise, parseDate, parseTimestamp,
} from './mapping.js';

export interface NormalizeResult {
  rows: LedgerRow[];
  issues: QualityIssue[];
  /** source data rows seen (excluding header) */
  totalRows: number;
}

export function normalizeGl(table: CsvTable, profile: MappingProfile, fileName: string): NormalizeResult {
  const issues: QualityIssue[] = [];
  const rows: LedgerRow[] = [];
  const colIndex = new Map(table.header.map((h, i) => [h, i]));
  const get = (row: string[], field: keyof MappingProfile['fieldMap']): string => {
    const col = profile.fieldMap[field];
    if (!col) return '';
    const idx = colIndex.get(col);
    return idx === undefined ? '' : (row[idx] ?? '').trim();
  };

  table.rows.forEach((raw, i) => {
    const lineage = { file: fileName, row: i + 2 };
    let broken = false;

    // required text fields
    for (const field of REQUIRED_GL_FIELDS) {
      if (field === 'txnDate') continue; // date checked below
      if (get(raw, field) === '' && field !== 'narration') {
        issues.push({
          type: 'missing_required_field', field,
          message: `Row is missing required field "${field}".`, lineage,
        });
        if (field === 'voucherId' || field === 'accountCode') broken = true;
      }
    }
    if (get(raw, 'narration') === '') {
      issues.push({ type: 'missing_required_field', field: 'narration', message: 'Narration is blank.', lineage });
    }

    // date
    const rawDate = get(raw, 'txnDate');
    const txnDate = rawDate === '' ? null : parseDate(rawDate, profile.dateFormat);
    if (txnDate === null) {
      issues.push({
        type: 'invalid_date', field: 'txnDate', value: rawDate,
        message: `Transaction date "${rawDate}" is not a valid ${profile.dateFormat} date.`, lineage,
      });
      broken = true;
    }

    // optional creation timestamp
    let createdAt: string | undefined;
    const rawTs = get(raw, 'createdAt');
    if (rawTs !== '') {
      const ts = parseTimestamp(rawTs);
      if (ts === null) {
        issues.push({
          type: 'invalid_timestamp', field: 'createdAt', value: rawTs,
          message: `Creation timestamp "${rawTs}" is not a valid timestamp.`, lineage,
        });
      } else createdAt = ts;
    }

    // amounts
    const parseSide = (field: 'debit' | 'credit'): number | null => {
      const rawAmt = get(raw, field);
      const p = parseAmountPaise(rawAmt);
      if (Number.isNaN(p)) {
        issues.push({
          type: 'non_numeric_amount', field, value: rawAmt,
          message: `${field} value "${rawAmt}" is not a number.`, lineage,
        });
        return null;
      }
      return p === 0 ? null : p; // treat explicit 0 as empty side
    };
    const debit = parseSide('debit');
    const credit = parseSide('credit');
    if (debit !== null && credit !== null) {
      issues.push({ type: 'both_debit_and_credit', message: 'Line carries both a debit and a credit amount.', lineage });
    }
    if (debit === null && credit === null) {
      issues.push({ type: 'no_amount', message: 'Line has neither a debit nor a credit amount.', lineage });
    }

    if (broken || txnDate === null) return; // excluded, but recorded above

    rows.push({
      voucherId: get(raw, 'voucherId'),
      voucherType: get(raw, 'voucherType'),
      txnDate,
      ...(createdAt !== undefined ? { createdAt } : {}),
      accountCode: get(raw, 'accountCode'),
      accountName: get(raw, 'accountName'),
      debit, credit,
      narration: get(raw, 'narration'),
      ...(get(raw, 'source') !== '' ? { source: get(raw, 'source') } : {}),
      ...(get(raw, 'userId') !== '' ? { userId: get(raw, 'userId') } : {}),
      ...(get(raw, 'reversalOf') !== '' ? { reversalOf: get(raw, 'reversalOf') } : {}),
      lineage,
    });
  });

  return { rows, issues, totalRows: table.rows.length };
}

/** Trial balance normaliser — fixed standard header (exported by our own tooling or mapped upstream). */
export function normalizeTb(table: CsvTable, fileName: string): { rows: TrialBalanceRow[]; issues: QualityIssue[] } {
  const issues: QualityIssue[] = [];
  const rows: TrialBalanceRow[] = [];
  const idx = new Map(table.header.map((h, i) => [h, i]));
  const need = ['account_code', 'account_name', 'opening', 'debit', 'credit', 'closing'];
  for (const col of need) {
    if (!idx.has(col)) {
      issues.push({
        type: 'unmapped_column', field: col,
        message: `Trial balance is missing column "${col}".`, lineage: { file: fileName, row: 1 },
      });
    }
  }
  if (issues.length > 0) return { rows, issues };
  table.rows.forEach((r, i) => {
    const lineage = { file: fileName, row: i + 2 };
    const amt = (col: string): number => {
      const p = parseAmountPaise(r[idx.get(col)!] ?? '');
      if (p === null || Number.isNaN(p)) {
        issues.push({
          type: 'non_numeric_amount', field: col, value: r[idx.get(col)!] ?? '',
          message: `Trial balance ${col} is not a number.`, lineage,
        });
        return 0;
      }
      return p;
    };
    rows.push({
      accountCode: r[idx.get('account_code')!] ?? '',
      accountName: r[idx.get('account_name')!] ?? '',
      opening: amt('opening'), debit: amt('debit'), credit: amt('credit'), closing: amt('closing'),
      lineage,
    });
  });
  return { rows, issues };
}
