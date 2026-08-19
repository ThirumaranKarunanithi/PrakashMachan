/** Mirrors backend DTOs (EngagementController / ImportSummaryDto). */

export interface MappingProfile {
  name: string;
  sourceType: string;
  description: string | null;
  dateFormat: string;
  fieldMap: Record<string, string>;
}

export interface Engagement {
  id: string;
  clientName: string;
  fyStart: string;
  fyEnd: string;
  closeDate: string;
  status: string;
  createdAt: string;
  populationCount: number;
  importCount: number;
}

export interface ImportBatch {
  id: string;
  profile: string;
  importedAt: string;
  totalRows: number;
  addedRows: number;
  skippedRows: number;
  issueCount: number;
  balanced: boolean;
  tbAgrees: boolean;
}

export interface ManifestEntry {
  file: string;
  bytes: number;
  sha256: string;
  rows: number;
  importedAt: string;
}

export interface IssueDto {
  file: string;
  row: number;
  type: string;
  field: string | null;
  value: string | null;
  message: string;
}

export interface VoucherImbalance {
  voucherId: string;
  debit: number;
  credit: number;
  difference: number;
  rows: number;
}

export interface TbDifference {
  accountCode: string;
  accountName: string;
  ledgerDebit: number;
  ledgerCredit: number;
  tbDebit: number;
  tbCredit: number;
  difference: number;
}

export interface ImportSummary {
  importId: string;
  engagementId: string;
  profile: string;
  files: ManifestEntry[];
  totalRows: number;
  cleanRows: number;
  addedRows: number;
  skippedRows: number;
  populationCount: number;
  issueCount: number;
  issueSummary: Record<string, number>;
  issues: IssueDto[];
  issuesTruncated: boolean;
  totalDebitPaise: number;
  totalCreditPaise: number;
  balanced: boolean;
  voucherImbalanceCount: number;
  voucherImbalances: VoucherImbalance[];
  tbAgrees: boolean;
  tbDifferences: TbDifference[];
  readyForAnalysis: boolean;
}

/** integer paise -> "1,23,456.78" (Indian grouping) */
export function inr(paise: number): string {
  return (paise / 100).toLocaleString('en-IN', {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  });
}
