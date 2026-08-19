/** Standard field model (BRD §5) and shared import types. */

/** Source reference for lineage — DAT-005: every normalised record traces to its original row. */
export interface Lineage {
  file: string;
  /** 1-based line number in the source file (header = line 1). */
  row: number;
}

/** One normalised general-ledger line. Amounts are integer paise. */
export interface LedgerRow {
  voucherId: string;
  voucherType: string;
  /** ISO YYYY-MM-DD */
  txnDate: string;
  /** ISO "YYYY-MM-DD HH:mm" when the source supplies it */
  createdAt?: string;
  accountCode: string;
  accountName: string;
  /** integer paise; exactly one of debit/credit is non-null on a valid line */
  debit: number | null;
  credit: number | null;
  narration: string;
  source?: string;
  userId?: string;
  reversalOf?: string;
  lineage: Lineage;
}

/** One trial-balance line. Amounts are integer paise. */
export interface TrialBalanceRow {
  accountCode: string;
  accountName: string;
  opening: number;
  debit: number;
  credit: number;
  closing: number;
  lineage: Lineage;
}

export type QualityIssueType =
  | 'missing_required_field'
  | 'invalid_date'
  | 'invalid_timestamp'
  | 'non_numeric_amount'
  | 'both_debit_and_credit'
  | 'no_amount'
  | 'duplicate_row_identity'
  | 'unmapped_account'
  | 'unmapped_column';

export interface QualityIssue {
  type: QualityIssueType;
  field?: string;
  value?: string;
  message: string;
  lineage: Lineage;
}

/** DAT-003 output: aggregated, downloadable data-quality report. */
export interface QualityReport {
  file: string;
  totalRows: number;
  cleanRows: number;
  issues: QualityIssue[];
  /** counts by issue type, for the summary view */
  summary: Partial<Record<QualityIssueType, number>>;
}

/** integer-paise helpers */
export const paise = (rupees: number): number => Math.round(rupees * 100);
export const inr = (p: number): string =>
  (p / 100).toLocaleString('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
