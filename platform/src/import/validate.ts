/** DAT-002: reconcile journal debits/credits and compare ledger totals with the trial balance. */
import type { LedgerRow, TrialBalanceRow } from './types.js';

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
  /** (ledger movement) - (tb movement), paise */
  difference: number;
}

export interface ValidationResult {
  totalDebit: number;
  totalCredit: number;
  balanced: boolean;
  voucherImbalances: VoucherImbalance[];
  tbDifferences: TbDifference[];
  tbAgrees: boolean;
}

export function validateLedger(rows: LedgerRow[], tb: TrialBalanceRow[]): ValidationResult {
  let totalDebit = 0;
  let totalCredit = 0;
  const byVoucher = new Map<string, { debit: number; credit: number; rows: number }>();
  const byAccount = new Map<string, { name: string; debit: number; credit: number }>();

  for (const r of rows) {
    const d = r.debit ?? 0;
    const c = r.credit ?? 0;
    totalDebit += d;
    totalCredit += c;
    const v = byVoucher.get(r.voucherId) ?? { debit: 0, credit: 0, rows: 0 };
    v.debit += d; v.credit += c; v.rows++;
    byVoucher.set(r.voucherId, v);
    const a = byAccount.get(r.accountCode) ?? { name: r.accountName, debit: 0, credit: 0 };
    a.debit += d; a.credit += c;
    byAccount.set(r.accountCode, a);
  }

  const voucherImbalances: VoucherImbalance[] = [];
  for (const [voucherId, v] of byVoucher) {
    if (v.debit !== v.credit) {
      voucherImbalances.push({ voucherId, debit: v.debit, credit: v.credit, difference: v.debit - v.credit, rows: v.rows });
    }
  }
  voucherImbalances.sort((a, b) => Math.abs(b.difference) - Math.abs(a.difference));

  const tbDifferences: TbDifference[] = [];
  const tbByAccount = new Map(tb.map((t) => [t.accountCode, t]));
  for (const [accountCode, a] of byAccount) {
    const t = tbByAccount.get(accountCode);
    const tbDebit = t?.debit ?? 0;
    const tbCredit = t?.credit ?? 0;
    const difference = (a.debit - a.credit) - (tbDebit - tbCredit);
    if (difference !== 0) {
      tbDifferences.push({
        accountCode, accountName: a.name,
        ledgerDebit: a.debit, ledgerCredit: a.credit, tbDebit, tbCredit, difference,
      });
    }
  }
  // TB accounts with movement that never appear in the ledger
  for (const t of tb) {
    if (!byAccount.has(t.accountCode) && (t.debit !== 0 || t.credit !== 0)) {
      tbDifferences.push({
        accountCode: t.accountCode, accountName: t.accountName,
        ledgerDebit: 0, ledgerCredit: 0, tbDebit: t.debit, tbCredit: t.credit,
        difference: -(t.debit - t.credit),
      });
    }
  }
  tbDifferences.sort((a, b) => Math.abs(b.difference) - Math.abs(a.difference));

  return {
    totalDebit, totalCredit,
    balanced: totalDebit === totalCredit,
    voucherImbalances, tbDifferences,
    tbAgrees: tbDifferences.length === 0,
  };
}
