/** DAT-006: delta imports — a later-period upload adds only new or changed records. */
import { sha256Hex } from '../core/checksum.js';
import type { LedgerRow } from './types.js';

/** Content identity of a ledger row — independent of source file/row position. */
export function rowIdentity(r: LedgerRow): string {
  return sha256Hex([
    r.voucherId, r.voucherType, r.txnDate, r.createdAt ?? '',
    r.accountCode, r.debit ?? '', r.credit ?? '', r.narration,
    r.source ?? '', r.userId ?? '', r.reversalOf ?? '',
  ].join('|'));
}

export interface DeltaResult {
  added: LedgerRow[];
  skipped: number;
  /** updated identity set after the import (feed back into the next delta) */
  identities: Set<string>;
}

/** Merge an upload into an engagement population without duplicating previously loaded records. */
export function deltaImport(existing: Set<string>, upload: LedgerRow[]): DeltaResult {
  const identities = new Set(existing);
  const added: LedgerRow[] = [];
  let skipped = 0;
  for (const r of upload) {
    const id = rowIdentity(r);
    if (identities.has(id)) { skipped++; continue; }
    identities.add(id);
    added.push(r);
  }
  return { added, skipped, identities };
}
