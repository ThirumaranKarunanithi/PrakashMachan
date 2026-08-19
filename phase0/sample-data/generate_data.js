/**
 * Phase 0 synthetic dataset generator — Ledger Integrity & Audit Intelligence Platform
 *
 * Generates a fully synthetic engagement for "CLIENT-A", FY 2024-25 (01-Apr-2024 .. 31-Mar-2025).
 * Deterministic: fixed seed, no Date.now()/unseeded Math.random(), so the same script
 * always produces byte-identical CSVs (BRD reproducibility principle).
 *
 * Seeded anomalies are listed in SEEDED_ANOMALIES.md — that file is the ground truth
 * the spike (and later the MVP) must be able to find.
 *
 * Usage: node generate_data.js   (writes CSVs into this directory)
 */
'use strict';
const fs = require('fs');
const path = require('path');

// ---------- deterministic PRNG (mulberry32) ----------
function mulberry32(seed) {
  return function () {
    seed |= 0; seed = (seed + 0x6D2B79F5) | 0;
    let t = Math.imul(seed ^ (seed >>> 15), 1 | seed);
    t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t;
    return ((t ^ (t >>> 14)) >>> 0) / 4294967296;
  };
}
const rand = mulberry32(20260818);
const ri = (min, max) => min + Math.floor(rand() * (max - min + 1)); // inclusive int
const pick = (arr) => arr[Math.floor(rand() * arr.length)];

// ---------- date helpers ----------
const FY_START = new Date(Date.UTC(2024, 3, 1));
const FY_END = new Date(Date.UTC(2025, 2, 31)); // close date 31-Mar-2025
function addDays(d, n) { const x = new Date(d.getTime()); x.setUTCDate(x.getUTCDate() + n); return x; }
function fmtD(d) { return d.toISOString().slice(0, 10); }
function fmtTS(d, hh, mm) {
  const t = new Date(d.getTime()); t.setUTCHours(hh, mm, 0, 0);
  return t.toISOString().slice(0, 16).replace('T', ' ');
}
function randDateInFY() { return addDays(FY_START, ri(0, 364)); }

// ---------- CSV writer ----------
function writeCsv(file, header, rows) {
  const esc = (v) => {
    const s = String(v == null ? '' : v);
    return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
  };
  const out = [header.join(',')].concat(rows.map(r => r.map(esc).join(','))).join('\n') + '\n';
  fs.writeFileSync(path.join(__dirname, file), out, 'utf-8');
  console.log(`${file}: ${rows.length} rows`);
}

// ---------- masters ----------
const USERS = [
  ['ACCT-1', 'Accounts Executive', 'N'],
  ['ACCT-2', 'Accounts Executive', 'N'],
  ['ACCT-3', 'Senior Accountant', 'N'],
  ['MGR-1', 'Finance Manager', 'Y'],
  ['ADMIN-1', 'System Administrator', 'Y'],
];
const NORMAL_USERS = ['ACCT-1', 'ACCT-2', 'ACCT-3'];

const ACCOUNTS = {
  PURCHASES: ['5001', 'Purchases - Trading Goods'],
  FREIGHT: ['5101', 'Freight & Cartage'],
  RENT: ['5201', 'Rent Expense'],
  REPAIRS: ['5301', 'Repairs & Maintenance'],
  PROF_FEES: ['5401', 'Professional Fees'],
  MISC_EXP: ['5901', 'Miscellaneous Expenses'],
  SALES: ['4001', 'Sales - Trading Goods'],
  GST_INPUT: ['2301', 'GST Input Credit'],
  GST_OUTPUT: ['2401', 'GST Output Liability'],
  BANK: ['1101', 'HDFC Bank - CA 8842'],
  SUNDRY_CR: ['2101', 'Sundry Creditors'],
  SUNDRY_DR: ['1201', 'Sundry Debtors'],
  BANK_CHARGES: ['5801', 'Bank Charges'],
  PROVISIONS: ['2501', 'Provisions & Accruals'],
};
const EXP_ACCTS = [ACCOUNTS.PURCHASES, ACCOUNTS.FREIGHT, ACCOUNTS.RENT, ACCOUNTS.REPAIRS, ACCOUNTS.PROF_FEES, ACCOUNTS.MISC_EXP];

// vendor master: 44 normal + 2 fuzzy-duplicates sharing a bank account (V-014 / V-041)
const VENDOR_NAMES = [
  'Agarwal Steel Suppliers', 'Balaji Packaging Co', 'Chennai Polymers Pvt Ltd', 'Deccan Logistics',
  'Eastern Electricals', 'Fortune Chemicals', 'Ganga Traders', 'Himalaya Papers', 'Indus Metal Works',
  'Jaipur Textiles House', 'Kaveri Transport Co', 'Lakshmi Industrial Stores', 'Madras Hardware Mart',
  'Shree Ram Traders', 'Noble Printing Press', 'Omkar Engineering', 'Pioneer Plastics', 'Quality Fasteners',
  'Royal Stationers', 'Sunrise Solvents', 'Trident Tools Ltd', 'Universal Bearings', 'Venus Adhesives',
  'Western Freight Lines', 'Xpress Couriers', 'Yamuna Enterprises', 'Zenith Abrasives', 'Anand Auto Parts',
  'Bharat Lubricants', 'Crescent Ceramics', 'Divya Safety Equipments', 'Everest Scaffolding',
  'Falcon Security Services', 'Green Valley Agro', 'Hitech Instruments', 'Imperial Paints',
  'Jyoti Electrodes', 'Krishna Cement Agency', 'Liberty Rubber Works', 'Mount Cables',
  'National Weighbridge', 'Orient Glass House', 'Prakash Machinery', 'Shri Ram Traders', 'Silverline Pipes'
];
const IFSC = ['HDFC0001234', 'ICIC0004567', 'SBIN0007890', 'UTIB0002345', 'KKBK0006789'];
function gstinFor(i) {
  const states = ['27', '29', '33', '07', '24', '36'];
  return `${states[i % states.length]}ZZ${String(1000 + i)}A1Z${(i * 7) % 10}`;
}

const vendors = VENDOR_NAMES.map((name, idx) => {
  const i = idx + 1;
  const id = 'V-' + String(i).padStart(3, '0');
  return {
    id, name,
    gstin: gstinFor(i),
    bank_account: 'XXXXXX' + String(400000 + i * 137).slice(-6),
    ifsc: IFSC[i % IFSC.length],
    created_date: fmtD(addDays(FY_START, -ri(30, 900))),
    created_by: pick(NORMAL_USERS),
    status: 'Active',
  };
});
// ANOMALY A5: fuzzy duplicate vendors — same bank a/c + IFSC, both created by ADMIN-1
const v14 = vendors.find(v => v.name === 'Shree Ram Traders');
const v44 = vendors.find(v => v.name === 'Shri Ram Traders');
v44.bank_account = v14.bank_account; v44.ifsc = v14.ifsc;
v14.created_by = 'ADMIN-1';
v44.created_by = 'ADMIN-1';
v44.created_date = '2025-02-10'; // ANOMALY A6: new vendor, immediate activity

// ---------- transaction stores ----------
const gl = [];        // general ledger lines
const purchases = []; // purchase register
const sales = [];     // sales register
const gstr2b = [];    // portal 2B lines
const bankStmt = [];  // bank statement lines (unsorted; sorted at end)
const bankLedger = []; // books bank ledger lines
const auditTrail = [];
let vSeq = 1;
const vid = (type) => `${type}-${String(vSeq++).padStart(5, '0')}`;

function glLine(voucher, vtype, txnD, createdTS, acct, dr, cr, narr, src, user, revOf) {
  gl.push([voucher, vtype, fmtD(txnD), createdTS, acct[0], acct[1],
    dr ? dr.toFixed(2) : '', cr ? cr.toFixed(2) : '', narr, src, user, revOf || '']);
}

// ---------- routine purchases (300) ----------
const GST_RATE = 0.18;
for (let i = 0; i < 300; i++) {
  const vend = vendors[ri(0, vendors.length - 1)];
  // skip the duplicate-pair vendors here; they get scripted activity below
  if (vend === v44) { i--; continue; }
  const d = randDateInFY();
  const taxable = ri(8, 2400) * 100 + ri(0, 99); // 800 .. 240099, non-round paise
  const tax = Math.round(taxable * GST_RATE * 100) / 100;
  const invNo = `${vend.id.replace('V-', 'SI')}/${String(2000 + i)}`;
  const voucher = vid('PUR');
  const user = pick(NORMAL_USERS);
  const created = fmtTS(addDays(d, ri(0, 1)), ri(9, 18), ri(0, 59));
  const acct = pick(EXP_ACCTS);
  glLine(voucher, 'Purchase', d, created, acct, taxable, 0, `Being goods/services from ${vend.name} inv ${invNo}`, 'PurchaseModule', user);
  glLine(voucher, 'Purchase', d, created, ACCOUNTS.GST_INPUT, tax, 0, `GST input on ${invNo}`, 'PurchaseModule', user);
  glLine(voucher, 'Purchase', d, created, ACCOUNTS.SUNDRY_CR, 0, taxable + tax, `${vend.name} inv ${invNo}`, 'PurchaseModule', user);
  purchases.push({ invNo, date: fmtD(d), vendor: vend, taxable, tax, voucher });
}

// ---------- routine sales (380) ----------
const CUSTOMERS = Array.from({ length: 30 }, (_, i) => ({
  id: 'C-' + String(i + 1).padStart(3, '0'),
  name: `Customer ${String(i + 1).padStart(3, '0')} Retail`,
  gstin: gstinFor(100 + i),
}));
for (let i = 0; i < 380; i++) {
  const cust = CUSTOMERS[ri(0, CUSTOMERS.length - 1)];
  const d = randDateInFY();
  const taxable = ri(15, 3800) * 100 + ri(0, 99);
  const tax = Math.round(taxable * GST_RATE * 100) / 100;
  const invNo = `CA/24-25/${String(1 + i).padStart(4, '0')}`;
  const voucher = vid('SAL');
  const user = pick(NORMAL_USERS);
  const created = fmtTS(d, ri(9, 18), ri(0, 59));
  glLine(voucher, 'Sales', d, created, ACCOUNTS.SUNDRY_DR, taxable + tax, 0, `${cust.name} inv ${invNo}`, 'SalesModule', user);
  glLine(voucher, 'Sales', d, created, ACCOUNTS.SALES, 0, taxable, `Sales inv ${invNo}`, 'SalesModule', user);
  glLine(voucher, 'Sales', d, created, ACCOUNTS.GST_OUTPUT, 0, tax, `GST output on ${invNo}`, 'SalesModule', user);
  sales.push({ invNo, date: fmtD(d), cust, taxable, tax, voucher });
}

// ---------- payments & receipts -> bank ----------
let bankRef = 5000;
function payVendor(p, dOffset, opts = {}) {
  const d = addDays(new Date(p.date + 'T00:00:00Z'), dOffset);
  if (d > FY_END) return;
  const amt = Math.round((p.taxable + p.tax) * 100) / 100;
  const voucher = vid('PMT');
  const ref = 'NEFT' + (bankRef++);
  const user = opts.user || pick(NORMAL_USERS);
  const created = fmtTS(d, ri(9, 18), ri(0, 59));
  glLine(voucher, 'Payment', d, created, ACCOUNTS.SUNDRY_CR, amt, 0, `Payment to ${p.vendor.name} ${ref}`, 'PaymentModule', user);
  glLine(voucher, 'Payment', d, created, ACCOUNTS.BANK, 0, amt, `Payment to ${p.vendor.name} ${ref}`, 'PaymentModule', user);
  bankLedger.push([fmtD(d), voucher, ref, '', amt.toFixed(2), `Payment ${p.vendor.name}`]);
  if (!opts.booksOnly) {
    const bd = addDays(d, opts.bankLag != null ? opts.bankLag : ri(0, 2));
    bankStmt.push({ d: bd, narr: `NEFT DR ${ref} ${p.vendor.name.toUpperCase().slice(0, 18)}`, ref, dr: amt, cr: 0 });
  }
  return { voucher, ref, amt, date: d };
}
function receiveCustomer(s, dOffset, opts = {}) {
  const d = addDays(new Date(s.date + 'T00:00:00Z'), dOffset);
  if (d > FY_END) return;
  const amt = Math.round((s.taxable + s.tax) * 100) / 100;
  const voucher = vid('RCT');
  const ref = 'UTR' + (bankRef++);
  const user = pick(NORMAL_USERS);
  const created = fmtTS(d, ri(9, 18), ri(0, 59));
  glLine(voucher, 'Receipt', d, created, ACCOUNTS.BANK, amt, 0, `Receipt from ${s.cust.name} ${ref}`, 'ReceiptModule', user);
  glLine(voucher, 'Receipt', d, created, ACCOUNTS.SUNDRY_DR, 0, amt, `Receipt from ${s.cust.name} ${ref}`, 'ReceiptModule', user);
  bankLedger.push([fmtD(d), voucher, ref, amt.toFixed(2), '', `Receipt ${s.cust.name}`]);
  if (!opts.skipBank) bankStmt.push({ d: addDays(d, ri(0, 2)), narr: `UPI/NEFT CR ${ref} ${s.cust.name.toUpperCase().slice(0, 18)}`, ref, dr: 0, cr: amt });
  return { voucher, ref, amt, date: d };
}
// pay ~70% of purchases, collect ~75% of sales
purchases.forEach((p, i) => { if (i % 10 < 7) payVendor(p, ri(5, 40)); });
sales.forEach((s, i) => { if (i % 4 < 3) receiveCustomer(s, ri(3, 45)); });

// ---------- ANOMALIES ----------

// A1: Rs 49 lakh expense created AFTER close, posted into FY, by ADMIN-1, reversed 7 days later (BRD §6 example)
{
  const txnD = new Date(Date.UTC(2025, 2, 31));
  const v1 = 'JRN-90001';
  const createdTS = '2025-04-04 22:15';
  glLine(v1, 'Journal', txnD, createdTS, ACCOUNTS.MISC_EXP, 4900000, 0, 'Provision for pending vendor bills', 'Manual', 'ADMIN-1');
  glLine(v1, 'Journal', txnD, createdTS, ACCOUNTS.PROVISIONS, 0, 4900000, 'Provision for pending vendor bills', 'Manual', 'ADMIN-1');
  const v2 = 'JRN-90002';
  const revD = new Date(Date.UTC(2025, 3, 11)); // 11-Apr-2025 (next FY; still in GL export)
  const revTS = '2025-04-11 21:40';
  glLine(v2, 'Journal', revD, revTS, ACCOUNTS.PROVISIONS, 4900000, 0, 'Reversal of provision', 'Manual', 'ADMIN-1', v1);
  glLine(v2, 'Journal', revD, revTS, ACCOUNTS.MISC_EXP, 0, 4900000, 'Reversal of provision', 'Manual', 'ADMIN-1', v1);
}

// A2: two more backdated post-close journals by ADMIN-1 / MGR-1
[['JRN-90003', 830000, '2025-04-02 20:05', 'ADMIN-1', 'Year end adjustment'],
 ['JRN-90004', 615000, '2025-04-05 19:30', 'MGR-1', 'Reclassification entry']].forEach(([v, amt, ts, user, narr]) => {
  const txnD = new Date(Date.UTC(2025, 2, ri(28, 31)));
  glLine(v, 'Journal', txnD, ts, ACCOUNTS.REPAIRS, amt, 0, narr, 'Manual', user);
  glLine(v, 'Journal', txnD, ts, ACCOUNTS.SUNDRY_CR, 0, amt, narr, 'Manual', user);
});

// A3: round-amount manual journals with vague narrations by ACCT-3
[['JRN-90010', 200000, '2024-11-14'], ['JRN-90011', 300000, '2024-12-09'],
 ['JRN-90012', 500000, '2025-01-21'], ['JRN-90013', 400000, '2025-03-27']].forEach(([v, amt, ds]) => {
  const d = new Date(ds + 'T00:00:00Z');
  const ts = fmtTS(d, 19, ri(10, 50));
  glLine(v, 'Journal', d, ts, ACCOUNTS.MISC_EXP, amt, 0, 'adjustment', 'Manual', 'ACCT-3');
  glLine(v, 'Journal', d, ts, ACCOUNTS.SUNDRY_CR, 0, amt, 'adjustment', 'Manual', 'ACCT-3');
});

// A4: threshold splitting — three payments to Prakash Machinery just under Rs 50,000 within 5 days
{
  const vend = vendors.find(v => v.name === 'Prakash Machinery');
  [49000, 49500, 48750].forEach((amt, k) => {
    const d = new Date(Date.UTC(2024, 8, 16 + k * 2)); // 16/18/20-Sep-2024
    const voucher = vid('PMT');
    const ref = 'NEFT' + (bankRef++);
    const created = fmtTS(d, 17, 15 + k);
    glLine(voucher, 'Payment', d, created, ACCOUNTS.SUNDRY_CR, amt, 0, `Advance to ${vend.name} ${ref}`, 'PaymentModule', 'ACCT-1');
    glLine(voucher, 'Payment', d, created, ACCOUNTS.BANK, 0, amt, `Advance to ${vend.name} ${ref}`, 'PaymentModule', 'ACCT-1');
    bankLedger.push([fmtD(d), voucher, ref, '', amt.toFixed(2), `Payment ${vend.name}`]);
    bankStmt.push({ d, narr: `NEFT DR ${ref} PRAKASH MACHINERY`, ref, dr: amt, cr: 0 });
  });
}

// A5/A6: activity for the fuzzy-duplicate new vendor V-044 (Shri Ram Traders) right after creation
{
  const inv = { invNo: 'SRT/0091', date: '2025-02-14', vendor: v44, taxable: 460000, tax: 82800, voucher: null };
  const voucher = vid('PUR'); inv.voucher = voucher;
  const d = new Date(Date.UTC(2025, 1, 14));
  const created = fmtTS(d, 11, 25);
  glLine(voucher, 'Purchase', d, created, ACCOUNTS.PURCHASES, inv.taxable, 0, `Being goods from ${v44.name} inv ${inv.invNo}`, 'PurchaseModule', 'ADMIN-1');
  glLine(voucher, 'Purchase', d, created, ACCOUNTS.GST_INPUT, inv.tax, 0, `GST input on ${inv.invNo}`, 'PurchaseModule', 'ADMIN-1');
  glLine(voucher, 'Purchase', d, created, ACCOUNTS.SUNDRY_CR, 0, inv.taxable + inv.tax, `${v44.name} inv ${inv.invNo}`, 'PurchaseModule', 'ADMIN-1');
  purchases.push(inv);
  payVendor(inv, 3, { user: 'ADMIN-1' }); // paid within 3 days, same user
}

// A7: duplicate invoice booked twice (Trident Tools INV differs only by suffix)
{
  const vend = vendors.find(v => v.name === 'Trident Tools Ltd');
  const base = { taxable: 187450, tax: 33741 };
  [['TT/2287', '2024-10-07'], ['TT/2287A', '2024-10-21']].forEach(([invNo, ds]) => {
    const d = new Date(ds + 'T00:00:00Z');
    const voucher = vid('PUR');
    const created = fmtTS(d, 14, 30);
    glLine(voucher, 'Purchase', d, created, ACCOUNTS.PURCHASES, base.taxable, 0, `Being goods from ${vend.name} inv ${invNo}`, 'PurchaseModule', 'ACCT-2');
    glLine(voucher, 'Purchase', d, created, ACCOUNTS.GST_INPUT, base.tax, 0, `GST input on ${invNo}`, 'PurchaseModule', 'ACCT-2');
    glLine(voucher, 'Purchase', d, created, ACCOUNTS.SUNDRY_CR, 0, base.taxable + base.tax, `${vend.name} inv ${invNo}`, 'PurchaseModule', 'ACCT-2');
    purchases.push({ invNo, date: ds, vendor: vend, taxable: base.taxable, tax: base.tax, voucher });
  });
}

// A8: vendor bank-detail change at night by ADMIN-1, payment next morning, changed back (BRD §10 example)
{
  const vend = vendors.find(v => v.name === 'Fortune Chemicals');
  auditTrail.push(['2025-01-08 22:42', 'ADMIN-1', 'VendorMaster', vend.id, 'bank_account', vend.bank_account, 'XXXXXX771204', 'Modify']);
  const inv = { invNo: 'FC/5512', date: '2025-01-02', vendor: vend, taxable: 340000, tax: 61200, voucher: 'PUR-A8' };
  const d = new Date(Date.UTC(2025, 0, 2));
  const created = fmtTS(d, 12, 10);
  glLine(inv.voucher, 'Purchase', d, created, ACCOUNTS.PURCHASES, inv.taxable, 0, `Being goods from ${vend.name} inv ${inv.invNo}`, 'PurchaseModule', 'ACCT-1');
  glLine(inv.voucher, 'Purchase', d, created, ACCOUNTS.GST_INPUT, inv.tax, 0, `GST input on ${inv.invNo}`, 'PurchaseModule', 'ACCT-1');
  glLine(inv.voucher, 'Purchase', d, created, ACCOUNTS.SUNDRY_CR, 0, inv.taxable + inv.tax, `${vend.name} inv ${inv.invNo}`, 'PurchaseModule', 'ACCT-1');
  purchases.push(inv);
  payVendor(inv, 7, { user: 'ADMIN-1', bankLag: 0 }); // 09-Jan payment
  auditTrail.push(['2025-01-12 23:05', 'ADMIN-1', 'VendorMaster', vend.id, 'bank_account', 'XXXXXX771204', vend.bank_account, 'Modify']);
}
// routine audit-trail noise
for (let i = 0; i < 25; i++) {
  const vend = vendors[ri(0, vendors.length - 1)];
  const d = randDateInFY();
  auditTrail.push([fmtTS(d, ri(10, 17), ri(0, 59)), pick(NORMAL_USERS), 'VendorMaster', vend.id, pick(['phone', 'email', 'address']), 'old-value', 'new-value', 'Modify']);
}
auditTrail.sort((a, b) => a[0] < b[0] ? -1 : 1);

// ---------- GSTR-2B: portal view of purchases, with seeded differences ----------
{
  // choose stable anomaly sets from the routine purchases
  const routine = purchases.filter(p => p.vendor !== v44 && !['TT/2287A'].includes(p.invNo));
  const booksOnly = new Set([routine[12].invNo, routine[47].invNo, routine[88].invNo, 'SRT/0091', 'TT/2287A']); // in books, not in 2B
  const valueMismatch = new Map([
    [routine[23].invNo, 0.9],   // supplier reported 10% lower
    [routine[61].invNo, 1.05],  // supplier reported 5% higher
    [routine[130].invNo, 0.5],  // half reported
  ]);
  purchases.forEach(p => {
    if (booksOnly.has(p.invNo)) return;
    let taxable = p.taxable, tax = p.tax;
    if (valueMismatch.has(p.invNo)) {
      const f = valueMismatch.get(p.invNo);
      taxable = Math.round(taxable * f * 100) / 100;
      tax = Math.round(tax * f * 100) / 100;
    }
    gstr2b.push([p.vendor.gstin, p.vendor.name, p.invNo, p.date, taxable.toFixed(2), tax.toFixed(2), 'Filed']);
  });
  // 2B-only invoices (supplier filed, client never booked)
  [['Ganga Traders', 'GT/8801', '2024-07-19', 96500], ['Imperial Paints', 'IP/3302', '2024-11-28', 210300],
   ['Mount Cables', 'MC/1190', '2025-02-05', 54100]].forEach(([vname, invNo, ds, taxable]) => {
    const vend = vendors.find(v => v.name === vname);
    gstr2b.push([vend.gstin, vend.name, invNo, ds, taxable.toFixed(2), (Math.round(taxable * GST_RATE * 100) / 100).toFixed(2), 'Filed']);
  });
}

// ---------- bank statement extras ----------
// A9: bank-only items — quarterly bank charges & interest never booked
[['2024-06-30', 'BANK CHARGES QTR1', 2360], ['2024-09-30', 'BANK CHARGES QTR2', 2478],
 ['2024-12-31', 'BANK CHARGES QTR3', 2596], ['2025-03-31', 'BANK CHARGES QTR4', 2714]].forEach(([ds, narr, amt]) => {
  bankStmt.push({ d: new Date(ds + 'T00:00:00Z'), narr, ref: 'CHG' + ds.replace(/-/g, ''), dr: amt, cr: 0 });
});
// A10: books-only item — cheque issued 20-Mar-2025, never presented
{
  const vend = vendors.find(v => v.name === 'Deccan Logistics');
  const d = new Date(Date.UTC(2025, 2, 20));
  const voucher = vid('PMT');
  const created = fmtTS(d, 16, 42);
  glLine(voucher, 'Payment', d, created, ACCOUNTS.SUNDRY_CR, 118000, 0, `Payment to ${vend.name} chq 004512`, 'PaymentModule', 'ACCT-2');
  glLine(voucher, 'Payment', d, created, ACCOUNTS.BANK, 0, 118000, `Payment to ${vend.name} chq 004512`, 'PaymentModule', 'ACCT-2');
  bankLedger.push([fmtD(d), voucher, 'CHQ004512', '', '118000.00', `Payment ${vend.name}`]);
  // no bank statement line — stale cheque
}
// A11: grouped receipt — three book receipts of 1,00,000 arrive as ONE bank credit of 3,00,000
{
  const d = new Date(Date.UTC(2025, 0, 15));
  ['C-004', 'C-011', 'C-019'].forEach((cid, k) => {
    const voucher = vid('RCT');
    const ref = 'COLL2025' + (k + 1);
    const created = fmtTS(d, 12, 5 + k);
    glLine(voucher, 'Receipt', d, created, ACCOUNTS.BANK, 100000, 0, `Receipt from ${cid} ${ref}`, 'ReceiptModule', 'ACCT-1');
    glLine(voucher, 'Receipt', d, created, ACCOUNTS.SUNDRY_DR, 0, 100000, `Receipt from ${cid} ${ref}`, 'ReceiptModule', 'ACCT-1');
    bankLedger.push([fmtD(d), voucher, ref, '100000.00', '', `Receipt ${cid}`]);
  });
  bankStmt.push({ d, narr: 'NEFT CR CONSOLIDATED COLLECTION AGENT', ref: 'AGG300K', dr: 0, cr: 300000 });
}

// ---------- write outputs ----------
writeCsv('user_master.csv', ['user_id', 'role', 'privileged'], USERS);
writeCsv('vendor_master.csv', ['vendor_id', 'name', 'gstin', 'bank_account', 'ifsc', 'created_date', 'created_by', 'status'],
  vendors.map(v => [v.id, v.name, v.gstin, v.bank_account, v.ifsc, v.created_date, v.created_by, v.status]));
writeCsv('general_ledger.csv',
  ['voucher_id', 'voucher_type', 'txn_date', 'created_at', 'account_code', 'account_name', 'debit', 'credit', 'narration', 'source', 'user_id', 'reversal_of'], gl);
writeCsv('purchase_register.csv', ['invoice_no', 'invoice_date', 'vendor_id', 'vendor_name', 'gstin', 'taxable_value', 'tax_amount', 'total', 'voucher_id'],
  purchases.map(p => [p.invNo, p.date, p.vendor.id, p.vendor.name, p.vendor.gstin, p.taxable.toFixed(2), p.tax.toFixed(2), (p.taxable + p.tax).toFixed(2), p.voucher]));
writeCsv('sales_register.csv', ['invoice_no', 'invoice_date', 'customer_id', 'customer_name', 'gstin', 'taxable_value', 'tax_amount', 'total', 'voucher_id'],
  sales.map(s => [s.invNo, s.date, s.cust.id, s.cust.name, s.cust.gstin, s.taxable.toFixed(2), s.tax.toFixed(2), (s.taxable + s.tax).toFixed(2), s.voucher]));
writeCsv('gstr2b.csv', ['supplier_gstin', 'supplier_name', 'invoice_no', 'invoice_date', 'taxable_value', 'tax_amount', 'filing_status'], gstr2b);
writeCsv('audit_trail.csv', ['timestamp', 'user_id', 'object', 'record_id', 'field', 'old_value', 'new_value', 'action'], auditTrail);

// bank statement sorted by date with running balance
bankStmt.sort((a, b) => a.d - b.d || (a.ref < b.ref ? -1 : 1));
let bal = 5000000;
writeCsv('bank_statement.csv', ['date', 'narration', 'reference', 'debit', 'credit', 'balance'],
  bankStmt.map(t => {
    bal = Math.round((bal - t.dr + t.cr) * 100) / 100;
    return [fmtD(t.d), t.narr, t.ref, t.dr ? t.dr.toFixed(2) : '', t.cr ? t.cr.toFixed(2) : '', bal.toFixed(2)];
  }));
bankLedger.sort((a, b) => a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : (a[2] < b[2] ? -1 : 1));
writeCsv('bank_ledger.csv', ['date', 'voucher_id', 'reference', 'debit', 'credit', 'narration'], bankLedger);

// trial balance derived from GL (opening balances all zero for this synthetic year)
{
  const tb = new Map();
  gl.forEach(r => {
    const key = r[4] + '|' + r[5];
    const e = tb.get(key) || { dr: 0, cr: 0 };
    e.dr += r[6] ? parseFloat(r[6]) : 0;
    e.cr += r[7] ? parseFloat(r[7]) : 0;
    tb.set(key, e);
  });
  const rows = [...tb.entries()].sort().map(([k, e]) => {
    const [code, name] = k.split('|');
    return [code, name, '0.00', e.dr.toFixed(2), e.cr.toFixed(2), (e.dr - e.cr).toFixed(2)];
  });
  writeCsv('trial_balance.csv', ['account_code', 'account_name', 'opening', 'debit', 'credit', 'closing'], rows);
}

// ---------- GSTR-1: portal view of sales, with seeded differences (A15-A17) ----------
// NOTE: appended AFTER all random draws so every previously generated file stays byte-identical.
{
  const g1BooksOnly = new Set([sales[10].invNo, sales[50].invNo, sales[100].invNo]); // A15: not reported
  const g1ValueMismatch = new Map([[sales[20].invNo, 0.9], [sales[60].invNo, 1.05]]); // A16
  const gstr1 = [];
  sales.forEach(s => {
    if (g1BooksOnly.has(s.invNo)) return;
    let taxable = s.taxable, tax = s.tax;
    if (g1ValueMismatch.has(s.invNo)) {
      const f = g1ValueMismatch.get(s.invNo);
      taxable = Math.round(taxable * f * 100) / 100;
      tax = Math.round(tax * f * 100) / 100;
    }
    gstr1.push([s.cust.gstin, s.cust.name, s.invNo, s.date, taxable.toFixed(2), tax.toFixed(2), 'Filed']);
  });
  // A17: invoices reported in GSTR-1 that are not in the books
  [['CA/24-25/9001', '2024-08-14', 150000], ['CA/24-25/9002', '2025-02-20', 88000]].forEach(([invNo, ds, taxable], k) => {
    const cust = CUSTOMERS[k];
    gstr1.push([cust.gstin, cust.name, invNo, ds, taxable.toFixed(2), (Math.round(taxable * GST_RATE * 100) / 100).toFixed(2), 'Filed']);
  });
  writeCsv('gstr1.csv', ['customer_gstin', 'customer_name', 'invoice_no', 'invoice_date', 'taxable_value', 'tax_amount', 'filing_status'], gstr1);

  // ---------- GSTR-3B: monthly summary derived from GSTR-1, one seeded shortfall (A18) ----------
  const byPeriod = new Map();
  gstr1.forEach(r => {
    const period = r[3].slice(0, 7); // YYYY-MM
    const e = byPeriod.get(period) || { taxable: 0, tax: 0 };
    e.taxable += parseFloat(r[4]);
    e.tax += parseFloat(r[5]);
    byPeriod.set(period, e);
  });
  const gstr3b = [...byPeriod.entries()].sort().map(([period, e]) => {
    let tax = Math.round(e.tax * 100) / 100;
    if (period === '2025-01') tax = Math.round((tax - 50000) * 100) / 100; // A18: Rs 50,000 declared short
    return [period, e.taxable.toFixed(2), tax.toFixed(2)];
  });
  writeCsv('gstr3b.csv', ['period', 'taxable_value', 'tax_amount'], gstr3b);
}

// integrity check: GL must balance
const totDr = gl.reduce((s, r) => s + (r[6] ? parseFloat(r[6]) : 0), 0);
const totCr = gl.reduce((s, r) => s + (r[7] ? parseFloat(r[7]) : 0), 0);
console.log(`GL totals: Dr ${totDr.toFixed(2)} / Cr ${totCr.toFixed(2)} — ${Math.abs(totDr - totCr) < 0.01 ? 'BALANCED' : 'ERROR: UNBALANCED'}`);
