import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { parseCsv } from '../core/csv.js';
import { parseAmountPaise, parseDate, parseTimestamp, checkProfileAgainstHeader, type MappingProfile } from '../import/mapping.js';
import { normalizeGl, normalizeTb } from '../import/normalize.js';
import { findDuplicateIdentities, findUnmappedAccounts, buildQualityReport } from '../import/quality.js';
import { validateLedger } from '../import/validate.js';
import { deltaImport, rowIdentity } from '../import/delta.js';

const here = dirname(fileURLToPath(import.meta.url));
const SAMPLE = join(here, '..', '..', '..', 'phase0', 'sample-data');
const PROFILE: MappingProfile = JSON.parse(
  readFileSync(join(here, '..', '..', 'mappings', 'client-a-gl.json'), 'utf-8'),
);

// ---------- unit: parsers ----------

test('parseAmountPaise handles rupees, separators, negatives, garbage', () => {
  assert.equal(parseAmountPaise('1234.56'), 123456);
  assert.equal(parseAmountPaise('1,23,456.7'), 12345670);
  assert.equal(parseAmountPaise('-500'), -50000);
  assert.equal(parseAmountPaise(''), null);
  assert.ok(Number.isNaN(parseAmountPaise('12.3.4')));
  assert.ok(Number.isNaN(parseAmountPaise('abc')));
});

test('parseDate validates real calendar dates per format', () => {
  assert.equal(parseDate('2025-03-31', 'YYYY-MM-DD'), '2025-03-31');
  assert.equal(parseDate('31-03-2025', 'DD-MM-YYYY'), '2025-03-31');
  assert.equal(parseDate('31/03/2025', 'DD/MM/YYYY'), '2025-03-31');
  assert.equal(parseDate('2025-02-30', 'YYYY-MM-DD'), null); // not a real date
  assert.equal(parseDate('2025-13-01', 'YYYY-MM-DD'), null);
  assert.equal(parseDate('31-03-2025', 'YYYY-MM-DD'), null); // wrong format
});

test('parseTimestamp accepts space/T and rejects bad clock values', () => {
  assert.equal(parseTimestamp('2025-04-04 22:15'), '2025-04-04 22:15');
  assert.equal(parseTimestamp('2025-04-04T22:15:33'), '2025-04-04 22:15');
  assert.equal(parseTimestamp('2025-04-04 25:00'), null);
});

test('checkProfileAgainstHeader reports unmapped/missing columns', () => {
  const problems = checkProfileAgainstHeader(PROFILE, ['voucher_id', 'txn_date']);
  assert.ok(problems.some((p) => p.includes('account_code')));
});

// ---------- unit: quality issues on a crafted bad file ----------

const BAD_CSV = [
  'voucher_id,voucher_type,txn_date,created_at,account_code,account_name,debit,credit,narration,source,user_id,reversal_of',
  'V1,Journal,2024-05-10,,1101,Bank,100.00,,ok line dr,Manual,U1,',
  'V1,Journal,2024-05-10,,2101,Creditors,,100.00,ok line cr,Manual,U1,',
  'V2,Journal,2024-99-99,,1101,Bank,50.00,,bad date,Manual,U1,',        // invalid_date
  'V3,Journal,2024-05-11,,1101,Bank,abc,,bad amount,Manual,U1,',        // non_numeric_amount + no_amount
  'V4,Journal,2024-05-12,,,Missing acct,10.00,,missing account code,Manual,U1,', // missing_required_field (broken)
  'V5,Journal,2024-05-13,,1101,Bank,25.00,25.00,both sides,Manual,U1,', // both_debit_and_credit
  'V6,Journal,2024-05-14,,1101,Bank,75.00,,dup identity,Manual,U1,',
  'V6,Journal,2024-05-14,,1101,Bank,75.00,,dup identity,Manual,U1,',    // duplicate_row_identity
].join('\n');

test('normalizeGl records every category of quality issue without silent drops', () => {
  const result = normalizeGl(parseCsv(BAD_CSV), PROFILE, 'bad.csv');
  const types = result.issues.map((i) => i.type);
  assert.ok(types.includes('invalid_date'));
  assert.ok(types.includes('non_numeric_amount'));
  assert.ok(types.includes('missing_required_field'));
  assert.ok(types.includes('both_debit_and_credit'));
  assert.ok(types.includes('no_amount'));
  // broken rows (bad date, missing account) excluded but accounted for
  assert.equal(result.totalRows, 8);
  assert.equal(result.rows.length, 6);
  // lineage points at real source lines
  const badDate = result.issues.find((i) => i.type === 'invalid_date')!;
  assert.deepEqual(badDate.lineage, { file: 'bad.csv', row: 4 });
  // duplicates found on normalised rows
  const dups = findDuplicateIdentities(result.rows);
  assert.equal(dups.length, 1);
  assert.equal(dups[0]!.lineage.row, 9);
});

test('quality report aggregates counts by type', () => {
  const result = normalizeGl(parseCsv(BAD_CSV), PROFILE, 'bad.csv');
  const report = buildQualityReport('bad.csv', result.totalRows, result.rows.length, result.issues);
  assert.equal(report.summary.invalid_date, 1);
  assert.ok((report.summary.missing_required_field ?? 0) >= 1);
});

// ---------- unit: validation catches imbalance and TB drift ----------

test('validateLedger flags unbalanced vouchers and TB differences', () => {
  const csv = [
    'voucher_id,voucher_type,txn_date,created_at,account_code,account_name,debit,credit,narration,source,user_id,reversal_of',
    'V1,Journal,2024-05-10,,1101,Bank,100.00,,dr,Manual,U1,',
    'V1,Journal,2024-05-10,,2101,Creditors,,90.00,cr short,Manual,U1,', // voucher off by 10
  ].join('\n');
  const gl = normalizeGl(parseCsv(csv), PROFILE, 't.csv');
  const tb = normalizeTb(parseCsv(
    'account_code,account_name,opening,debit,credit,closing\n1101,Bank,0,100.00,0,100.00\n2101,Creditors,0,0,95.00,-95.00\n',
  ), 'tb.csv');
  const v = validateLedger(gl.rows, tb.rows);
  assert.equal(v.balanced, false);
  assert.equal(v.voucherImbalances.length, 1);
  assert.equal(v.voucherImbalances[0]!.difference, 1000); // 10 rupees in paise
  assert.equal(v.tbAgrees, false);
  assert.ok(v.tbDifferences.some((d) => d.accountCode === '2101' && d.difference === 500));
});

// ---------- unit: delta import ----------

test('deltaImport skips previously loaded records, adds new ones', () => {
  const csv = [
    'voucher_id,voucher_type,txn_date,created_at,account_code,account_name,debit,credit,narration,source,user_id,reversal_of',
    'V1,Journal,2024-05-10,,1101,Bank,100.00,,a,Manual,U1,',
    'V1,Journal,2024-05-10,,2101,Creditors,,100.00,a,Manual,U1,',
  ].join('\n');
  const first = normalizeGl(parseCsv(csv), PROFILE, 'p1.csv');
  const d1 = deltaImport(new Set(), first.rows);
  assert.equal(d1.added.length, 2);

  // same content re-uploaded from a different file/rows -> all skipped
  const again = normalizeGl(parseCsv(csv), PROFILE, 'p1-reupload.csv');
  const d2 = deltaImport(d1.identities, again.rows);
  assert.equal(d2.added.length, 0);
  assert.equal(d2.skipped, 2);

  // identity is content-based, not position-based
  assert.equal(rowIdentity(first.rows[0]!), rowIdentity(again.rows[0]!));
});

// ---------- integration: full CLIENT-A sample population ----------

test('CLIENT-A sample: normalises clean, balances, agrees to TB', () => {
  const glTable = parseCsv(readFileSync(join(SAMPLE, 'general_ledger.csv'), 'utf-8'));
  const tbTable = parseCsv(readFileSync(join(SAMPLE, 'trial_balance.csv'), 'utf-8'));
  const gl = normalizeGl(glTable, PROFILE, 'general_ledger.csv');
  const tb = normalizeTb(tbTable, 'trial_balance.csv');

  assert.equal(gl.totalRows, 3008);
  assert.equal(gl.rows.length, 3008); // nothing dropped
  assert.equal(gl.issues.length, 0);  // synthetic data is clean
  assert.equal(tb.issues.length, 0);

  const unmapped = findUnmappedAccounts(gl.rows, tb.rows);
  assert.equal(unmapped.length, 0);

  const v = validateLedger(gl.rows, tb.rows);
  assert.equal(v.balanced, true);
  assert.equal(v.voucherImbalances.length, 0);
  assert.equal(v.tbAgrees, true);
  // paise precision: totals are exact integers
  assert.equal(v.totalDebit, v.totalCredit);
  assert.equal(v.totalDebit % 1, 0);
});
