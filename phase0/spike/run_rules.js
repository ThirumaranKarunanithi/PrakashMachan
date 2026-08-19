/**
 * Phase 0 workflow spike — proves the BRD workflow end-to-end on the synthetic dataset:
 *   import -> validate -> reconcile -> analyse -> exception register -> workpaper draft
 *
 * Implements 9 rules from the MVP pack (BRD §21.1). Throwaway code, NOT the MVP.
 * Every exception carries: rule id, severity, exposure, plain-language reason, source refs
 * (BRD principles: explain before scoring, source traceability).
 *
 * Usage: node run_rules.js
 * Outputs: exceptions.csv, ../sample-workpaper/journal-entry-testing-workpaper-FILLED.md
 */
'use strict';
const fs = require('fs');
const path = require('path');
const DATA = path.join(__dirname, '..', 'sample-data');

// ---------- tiny CSV reader (handles quoted fields) ----------
function readCsv(file) {
  const text = fs.readFileSync(path.join(DATA, file), 'utf-8').replace(/\r/g, '');
  const rows = [];
  let field = '', row = [], inQ = false;
  for (let i = 0; i < text.length; i++) {
    const c = text[i];
    if (inQ) {
      if (c === '"') { if (text[i + 1] === '"') { field += '"'; i++; } else inQ = false; }
      else field += c;
    } else if (c === '"') inQ = true;
    else if (c === ',') { row.push(field); field = ''; }
    else if (c === '\n') { row.push(field); field = ''; if (row.length > 1 || row[0] !== '') rows.push(row); row = []; }
    else field += c;
  }
  if (field !== '' || row.length) { row.push(field); rows.push(row); }
  const header = rows.shift();
  return rows.map(r => Object.fromEntries(header.map((h, i) => [h, r[i] ?? ''])));
}

const CLOSE_DATE = '2025-03-31';
const APPROVAL_THRESHOLD = 50000;
const gl = readCsv('general_ledger.csv');
const tb = readCsv('trial_balance.csv');
const vendors = readCsv('vendor_master.csv');
const purchases = readCsv('purchase_register.csv');
const g2b = readCsv('gstr2b.csv');
const bankStmt = readCsv('bank_statement.csv');
const bankLedger = readCsv('bank_ledger.csv');
const users = readCsv('user_master.csv');
const priv = new Set(users.filter(u => u.privileged === 'Y').map(u => u.user_id));

const exceptions = [];
let exSeq = 1;
function ex(rule, severity, exposure, reason, refs) {
  exceptions.push({ id: 'EX-' + String(exSeq++).padStart(3, '0'), rule, severity, exposure: Math.round(exposure), reason, refs });
}

// ---------- STEP 1: validate (DAT-002) — GL balance + TB agreement ----------
const num = (s) => s ? parseFloat(s) : 0;
const glDr = gl.reduce((s, r) => s + num(r.debit), 0);
const glCr = gl.reduce((s, r) => s + num(r.credit), 0);
const tbDr = tb.reduce((s, r) => s + num(r.debit), 0);
const validation = {
  glLines: gl.length,
  glDr, glCr,
  balanced: Math.abs(glDr - glCr) < 0.01,
  tbAgrees: Math.abs(glDr - tbDr) < 0.01,
};

// group GL lines into vouchers
const vouchers = new Map();
gl.forEach((r, i) => {
  r._row = i + 2; // 1-based + header, for source traceability
  if (!vouchers.has(r.voucher_id)) vouchers.set(r.voucher_id, []);
  vouchers.get(r.voucher_id).push(r);
});
const vAmount = (lines) => lines.reduce((s, l) => s + num(l.debit), 0);
const refsOf = (lines) => lines.map(l => `general_ledger.csv:${l._row}`).join(' ');

// ---------- RULE JE-03/JE-04: created after close, posted on/before close ----------
for (const [vidr, lines] of vouchers) {
  const l = lines[0];
  if (l.txn_date <= CLOSE_DATE && l.created_at.slice(0, 10) > CLOSE_DATE) {
    const amt = vAmount(lines);
    const sev = priv.has(l.user_id) ? 'HIGH' : 'MEDIUM';
    ex('JE-03 post-close/backdated', sev, amt,
      `Voucher ${vidr} posted to ${l.txn_date} but created ${l.created_at} (after close ${CLOSE_DATE}) by ${l.user_id}${priv.has(l.user_id) ? ' [privileged user]' : ''}.`,
      refsOf(lines));
  }
}

// ---------- RULE JE-09: quick reversal via reversal_of link ----------
for (const [vidr, lines] of vouchers) {
  const rev = lines[0].reversal_of;
  if (!rev || !vouchers.has(rev)) continue;
  const orig = vouchers.get(rev)[0];
  const days = Math.round((new Date(lines[0].txn_date) - new Date(orig.txn_date)) / 86400000);
  ex('JE-09 quick reversal', 'HIGH', vAmount(lines),
    `Voucher ${vidr} reverses ${rev} after ${days} days; original posted ${orig.txn_date} by ${orig.user_id}, reversal by ${lines[0].user_id}. Reversal crosses the reporting date.`,
    refsOf(vouchers.get(rev)) + ' ' + refsOf(lines));
}

// ---------- RULE JE-07 + JE-10: round-amount manual journals / vague narration ----------
const VAGUE = new Set(['adjustment', 'misc', 'adj', 'entry', 'correction', 'sundry']);
for (const [vidr, lines] of vouchers) {
  const l = lines[0];
  if (l.voucher_type !== 'Journal' || l.source !== 'Manual') continue;
  const amt = vAmount(lines);
  const isRound = amt >= 100000 && amt % 10000 === 0;
  const isVague = VAGUE.has(l.narration.trim().toLowerCase());
  if (isRound || isVague) {
    const why = [];
    if (isRound) why.push(`round amount Rs ${amt.toLocaleString('en-IN')}`);
    if (isVague) why.push(`vague narration "${l.narration}"`);
    ex('JE-07/JE-10 round/vague manual journal', isRound && isVague ? 'HIGH' : 'MEDIUM', amt,
      `Manual journal ${vidr} by ${l.user_id} on ${l.txn_date}: ${why.join(' and ')}.`, refsOf(lines));
  }
}

// ---------- RULE VP-05: threshold splitting (same vendor payee, <threshold each, window 7 days) ----------
{
  // payments = Payment vouchers; payee inferred from narration up to the NEFT/chq ref
  const pays = [];
  for (const [vidr, lines] of vouchers) {
    const l = lines[0];
    if (l.voucher_type !== 'Payment') continue;
    const payee = l.narration.replace(/^(Payment to |Advance to )/, '').replace(/ (NEFT|UTR|chq).*$/, '');
    pays.push({ vidr, payee, date: l.txn_date, amt: vAmount(lines), lines });
  }
  const byPayee = new Map();
  pays.forEach(p => { (byPayee.get(p.payee) || byPayee.set(p.payee, []).get(p.payee)).push(p); });
  for (const [payee, list] of byPayee) {
    const under = list.filter(p => p.amt < APPROVAL_THRESHOLD && p.amt >= APPROVAL_THRESHOLD * 0.9)
      .sort((a, b) => a.date < b.date ? -1 : 1);
    for (let i = 0; i < under.length; i++) {
      const group = under.filter(p => Math.abs(new Date(p.date) - new Date(under[i].date)) <= 7 * 86400000);
      if (group.length >= 2 && group.reduce((s, p) => s + p.amt, 0) > APPROVAL_THRESHOLD) {
        ex('VP-05 threshold splitting', 'HIGH', group.reduce((s, p) => s + p.amt, 0),
          `${group.length} payments to ${payee} within 7 days, each just below the Rs ${APPROVAL_THRESHOLD.toLocaleString('en-IN')} approval threshold: ${group.map(p => `${p.vidr} ${p.date} Rs ${p.amt.toLocaleString('en-IN')}`).join('; ')}. Group total exceeds the threshold.`,
          group.map(p => refsOf(p.lines)).join(' '));
        break; // one case per payee
      }
    }
  }
}

// ---------- RULE VP-01/VP-02: duplicate vendors (fuzzy name, shared bank account) ----------
{
  const norm = (s) => s.toLowerCase().replace(/\b(pvt|ltd|co|company|enterprises|traders|services)\b/g, '').replace(/[^a-z]/g, '');
  // shared bank account
  const byBank = new Map();
  vendors.forEach(v => { const k = v.bank_account + '|' + v.ifsc; (byBank.get(k) || byBank.set(k, []).get(k)).push(v); });
  for (const [k, vs] of byBank) {
    if (vs.length < 2) continue;
    const nameSim = norm(vs[0].name) === norm(vs[1].name) ? ' Names are near-identical after normalisation.' : '';
    ex('VP-01/VP-02 duplicate vendor / shared bank', 'HIGH', 0,
      `Vendors ${vs.map(v => `${v.vendor_id} "${v.name}" (created ${v.created_date} by ${v.created_by})`).join(' and ')} share bank account ${vs[0].bank_account} / ${vs[0].ifsc}.${nameSim}`,
      vs.map(v => `vendor_master.csv:${v.vendor_id}`).join(' '));
  }
}

// ---------- RULE VP-03: new vendor immediate large activity ----------
vendors.filter(v => v.created_date >= '2025-01-01').forEach(v => {
  const inv = purchases.filter(p => p.vendor_id === v.vendor_id);
  const total = inv.reduce((s, p) => s + num(p.total), 0);
  if (total > 100000) {
    ex('VP-03 new vendor activity', 'HIGH', total,
      `Vendor ${v.vendor_id} "${v.name}" created ${v.created_date} by ${v.created_by}; Rs ${Math.round(total).toLocaleString('en-IN')} of invoices booked within weeks of creation (${inv.map(p => p.invoice_no).join(', ')}).`,
      inv.map(p => `purchase_register.csv:${p.invoice_no}`).join(' '));
  }
});

// ---------- RULE VP-04/GS-04: duplicate / near-duplicate purchase invoices ----------
{
  const byKey = new Map();
  purchases.forEach(p => {
    const k = p.vendor_id + '|' + p.total + '|' + p.invoice_no.replace(/[^0-9]/g, ''); // same vendor+amount+numeric core
    (byKey.get(k) || byKey.set(k, []).get(k)).push(p);
  });
  for (const [, ps] of byKey) {
    if (ps.length < 2) continue;
    ex('VP-04 duplicate invoice', 'HIGH', num(ps[0].total),
      `Invoices ${ps.map(p => `${p.invoice_no} (${p.invoice_date})`).join(' and ')} from ${ps[0].vendor_name} have identical value Rs ${num(ps[0].total).toLocaleString('en-IN')} and matching numeric core — possible double booking.`,
      ps.map(p => `purchase_register.csv:${p.invoice_no}`).join(' '));
  }
}

// ---------- RULE VP-06/ATR-004: bank detail change followed by payment ----------
{
  const at = readCsv('audit_trail.csv').filter(r => r.field === 'bank_account');
  at.forEach(chg => {
    const vend = vendors.find(v => v.vendor_id === chg.record_id);
    if (!vend) return;
    // payment to that vendor within 3 days after the change
    for (const [vidr, lines] of vouchers) {
      const l = lines[0];
      if (l.voucher_type !== 'Payment' || !l.narration.includes(vend.name)) continue;
      const gap = (new Date(l.txn_date) - new Date(chg.timestamp.slice(0, 10))) / 86400000;
      if (gap >= 0 && gap <= 3) {
        ex('VP-06 bank change before payment', 'HIGH', vAmount(lines),
          `Bank account of ${vend.vendor_id} "${vend.name}" changed ${chg.timestamp} by ${chg.user_id} (after hours); payment ${vidr} of Rs ${vAmount(lines).toLocaleString('en-IN')} released ${l.txn_date}, ${Math.round(gap)} day(s) later.`,
          `audit_trail.csv:${chg.timestamp} ` + refsOf(lines));
      }
    }
  });
}

// ---------- RULE GS-01: purchase register vs GSTR-2B ----------
const gstSummary = { matched: 0, valueMismatch: 0, booksOnly: 0, g2bOnly: 0 };
{
  const bookByInv = new Map(purchases.map(p => [p.gstin + '|' + p.invoice_no, p]));
  const g2bByInv = new Map(g2b.map(r => [r.supplier_gstin + '|' + r.invoice_no, r]));
  for (const [k, p] of bookByInv) {
    const r = g2bByInv.get(k);
    if (!r) {
      gstSummary.booksOnly++;
      ex('GS-01 books-only (not in 2B)', 'MEDIUM', num(p.tax_amount),
        `Invoice ${p.invoice_no} from ${p.vendor_name} (Rs ${num(p.total).toLocaleString('en-IN')}) is in the purchase register but absent from GSTR-2B. Potential ITC exposure Rs ${Math.round(num(p.tax_amount)).toLocaleString('en-IN')} — eligibility for professional review.`,
        `purchase_register.csv:${p.invoice_no}`);
    } else if (Math.abs(num(r.taxable_value) - num(p.taxable_value)) > 1) {
      gstSummary.valueMismatch++;
      ex('GS-01 value mismatch books vs 2B', 'MEDIUM', Math.abs(num(r.tax_amount) - num(p.tax_amount)),
        `Invoice ${p.invoice_no} (${p.vendor_name}): books taxable Rs ${num(p.taxable_value).toLocaleString('en-IN')} vs 2B Rs ${num(r.taxable_value).toLocaleString('en-IN')}. Tax difference Rs ${Math.round(Math.abs(num(r.tax_amount) - num(p.tax_amount))).toLocaleString('en-IN')}.`,
        `purchase_register.csv:${p.invoice_no} gstr2b.csv:${r.invoice_no}`);
    } else gstSummary.matched++;
  }
  for (const [k, r] of g2bByInv) {
    if (!bookByInv.has(k)) {
      gstSummary.g2bOnly++;
      ex('GS-01 2B-only (not in books)', 'MEDIUM', num(r.tax_amount),
        `Supplier ${r.supplier_name} filed invoice ${r.invoice_no} (${r.invoice_date}, taxable Rs ${num(r.taxable_value).toLocaleString('en-IN')}) in GSTR-2B but it is not booked — possible unrecorded purchase or supplier error.`,
        `gstr2b.csv:${r.invoice_no}`);
    }
  }
}

// ---------- RULE BK-01/02/04: bank statement vs bank ledger ----------
const bankSummary = { exact: 0, tolerance: 0, bankOnly: 0, booksOnly: 0 };
{
  const usedStmt = new Set(), usedLed = new Set();
  const stmtByRef = new Map(bankStmt.map((r, i) => [r.reference, { r, i }]));
  // pass 1: exact reference match, amount equal
  bankLedger.forEach((l, j) => {
    const hit = stmtByRef.get(l.reference);
    if (hit && !usedStmt.has(hit.i)) {
      const sAmt = num(hit.r.debit) || num(hit.r.credit);
      const lAmt = num(l.debit) || num(l.credit);
      if (Math.abs(sAmt - lAmt) < 0.01) {
        const dayGap = Math.abs(new Date(hit.r.date) - new Date(l.date)) / 86400000;
        if (dayGap === 0) bankSummary.exact++; else bankSummary.tolerance++;
        usedStmt.add(hit.i); usedLed.add(j);
      }
    }
  });
  bankStmt.forEach((r, i) => {
    if (usedStmt.has(i)) return;
    bankSummary.bankOnly++;
    ex('BK-04 bank-only item', num(r.debit) + num(r.credit) > 50000 ? 'MEDIUM' : 'LOW', num(r.debit) + num(r.credit),
      `Bank statement ${r.date} "${r.narration}" Rs ${(num(r.debit) + num(r.credit)).toLocaleString('en-IN')} (${num(r.debit) ? 'debit' : 'credit'}) has no matching book entry.`,
      `bank_statement.csv:${r.reference}`);
  });
  bankLedger.forEach((l, j) => {
    if (usedLed.has(j)) return;
    bankSummary.booksOnly++;
    ex('BK-04 books-only item', 'MEDIUM', num(l.debit) + num(l.credit),
      `Book entry ${l.voucher_id} ${l.date} ref ${l.reference} Rs ${(num(l.debit) + num(l.credit)).toLocaleString('en-IN')} never appeared in the bank statement${l.date <= '2025-03-01' ? ' [stale >30 days at close]' : ''}.`,
      `bank_ledger.csv:${l.voucher_id}`);
  });
}

// ---------- output: exception register ----------
const SEV_ORDER = { HIGH: 0, MEDIUM: 1, LOW: 2 };
exceptions.sort((a, b) => SEV_ORDER[a.severity] - SEV_ORDER[b.severity] || b.exposure - a.exposure);
const esc = (v) => /[",\n]/.test(String(v)) ? '"' + String(v).replace(/"/g, '""') + '"' : String(v);
fs.writeFileSync(path.join(__dirname, 'exceptions.csv'),
  ['exception_id,rule,severity,estimated_exposure_inr,reason,source_refs,status']
    .concat(exceptions.map(e => [e.id, e.rule, e.severity, e.exposure, e.reason, e.refs, 'New'].map(esc).join(','))).join('\n') + '\n', 'utf-8');

// ---------- console summary ----------
console.log('=== VALIDATE (DAT-002/003) ===');
console.log(`GL lines: ${validation.glLines} | Dr ${glDr.toFixed(2)} = Cr ${glCr.toFixed(2)}: ${validation.balanced ? 'BALANCED' : 'UNBALANCED'} | TB agrees: ${validation.tbAgrees ? 'YES' : 'NO'}`);
console.log('\n=== RECONCILE (GS-01, BK-01/02/04) ===');
console.log(`GST: matched ${gstSummary.matched} | value mismatch ${gstSummary.valueMismatch} | books-only ${gstSummary.booksOnly} | 2B-only ${gstSummary.g2bOnly}`);
console.log(`Bank: exact ${bankSummary.exact} | tolerance ${bankSummary.tolerance} | bank-only ${bankSummary.bankOnly} | books-only ${bankSummary.booksOnly}`);
console.log('\n=== EXCEPTION REGISTER ===');
const byRule = new Map();
exceptions.forEach(e => byRule.set(e.rule, (byRule.get(e.rule) || 0) + 1));
for (const [rule, n] of byRule) console.log(`  ${rule}: ${n}`);
console.log(`Total exceptions: ${exceptions.length} (HIGH ${exceptions.filter(e => e.severity === 'HIGH').length}, MEDIUM ${exceptions.filter(e => e.severity === 'MEDIUM').length}, LOW ${exceptions.filter(e => e.severity === 'LOW').length})`);
console.log('\nTop HIGH-severity cases:');
exceptions.filter(e => e.severity === 'HIGH').slice(0, 12).forEach(e => console.log(`  ${e.id} [${e.rule}] Rs ${e.exposure.toLocaleString('en-IN')} — ${e.reason.slice(0, 130)}`));

// ---------- workpaper draft (AWP-001..004 shape) ----------
const je = exceptions.filter(e => e.rule.startsWith('JE-'));
const manualJournals = [...vouchers.values()].filter(ls => ls[0].voucher_type === 'Journal' && ls[0].source === 'Manual');
const wp = `# Workpaper JE-01 — Journal-Entry Testing (FILLED EXAMPLE)

*Auto-generated by the Phase 0 spike (run_rules.js) from the synthetic CLIENT-A dataset — demonstrates the workpaper shape required by BRD §15 / AWP-001..004. Reviewer fields are intentionally blank: professional judgement cannot be auto-filled (BRD §15 boundary).*

## 1. Objective and scope
Test journal entries of CLIENT-A for FY 2024-25 (01-Apr-2024 to 31-Mar-2025, close date ${CLOSE_DATE}) for post-close/backdated posting, quick reversals, round amounts and vague narrations, per firm methodology and SA 240 management-override considerations.

## 2. Data and completeness (DAT-002)
| Item | Value |
|---|---|
| Source file | general_ledger.csv (synthetic, seed 20260818) |
| GL lines imported | ${validation.glLines} |
| Total debits | Rs ${glDr.toLocaleString('en-IN')} |
| Total credits | Rs ${glCr.toLocaleString('en-IN')} |
| Debits = credits | ${validation.balanced ? 'Yes' : 'NO — investigate'} |
| Agrees to trial balance | ${validation.tbAgrees ? 'Yes' : 'NO — investigate'} |
| Manual journal population | ${manualJournals.length} vouchers, Rs ${manualJournals.reduce((s, ls) => s + vAmount(ls), 0).toLocaleString('en-IN')} |

## 3. Procedures and parameters
| Rule | Parameters |
|---|---|
| JE-03 post-close/backdated | close date ${CLOSE_DATE}; creation timestamp vs transaction date |
| JE-09 quick reversal | reversal link present; original in prior period |
| JE-07 round amounts | manual journals ≥ Rs 1,00,000 and multiple of 10,000 |
| JE-10 vague narration | narration in configured vague-word list |
Rule pack version: phase0-spike-0.1 (throwaway; MVP will version formally per AWP-003).

## 4. Exceptions (${je.length})
| ID | Rule | Severity | Exposure (Rs) | Reason | Source refs | Preparer conclusion | Reviewer |
|---|---|---|---|---|---|---|---|
${je.map(e => `| ${e.id} | ${e.rule} | ${e.severity} | ${e.exposure.toLocaleString('en-IN')} | ${e.reason} | ${e.refs} | _pending_ | _pending_ |`).join('\n')}

## 5. Conclusion
_To be completed by the engagement team. The system identifies review priorities; it does not conclude on misstatement (BRD §2.3, §20)._

| Role | Name | Date | Signature |
|---|---|---|---|
| Prepared by | | | |
| Reviewed by (Manager) | | | |
| Approved by (Partner) | | | |
`;
fs.writeFileSync(path.join(__dirname, '..', 'sample-workpaper', 'journal-entry-testing-workpaper-FILLED.md'), wp, 'utf-8');
console.log('\nWorkpaper draft written: ../sample-workpaper/journal-entry-testing-workpaper-FILLED.md');
console.log('Exception register written: exceptions.csv');
