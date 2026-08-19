# Seeded Anomalies — Ground Truth

*Every anomaly deliberately planted in the synthetic CLIENT-A dataset (FY 2024-25, close date 31-Mar-2025). The spike — and later the MVP — must surface each of these. Anything else flagged is a candidate false positive to discuss with design partners.*

| # | Anomaly | Where | BRD rule area | Key records |
|---|---|---|---|---|
| A1 | Rs 49,00,000 provision **created 04-Apr-2025 (after close), posted 31-Mar-2025** by ADMIN-1, **reversed 7 days later** — the BRD §6 illustrative example | general_ledger.csv | JE-03 post-close, JE-09 quick reversal, JE-02 privileged user | JRN-90001 / JRN-90002 |
| A2 | Two more backdated post-close journals (Rs 8,30,000 ADMIN-1; Rs 6,15,000 MGR-1) | general_ledger.csv | JE-03, JE-02 | JRN-90003 / JRN-90004 |
| A3 | Four round-amount manual journals (2L/3L/4L/5L) with narration "adjustment", all by ACCT-3, posted after 19:00 | general_ledger.csv | JE-07 round amounts, JE-10 vague narration | JRN-90010..90013 |
| A4 | Threshold splitting: 3 payments to Prakash Machinery of 49,000 / 49,500 / 48,750 within 5 days (approval threshold 50,000) | general_ledger.csv, bank | VP-05 split payments | 16/18/20-Sep-2024 |
| A5 | Fuzzy duplicate vendors: **V-014 "Shree Ram Traders"** and **V-044 "Shri Ram Traders"** share the same bank account + IFSC; both created by ADMIN-1 | vendor_master.csv | VP-01 duplicate vendor, VP-02 shared bank | V-014, V-044 |
| A6 | New-vendor immediate activity: V-044 created 10-Feb-2025, invoice SRT/0091 on 14-Feb, paid within 3 days, all touched by ADMIN-1 | vendor_master, purchase_register, GL | VP-03, XC-02 (SoD) | SRT/0091 |
| A7 | Duplicate invoice booked twice: Trident Tools **TT/2287** and **TT/2287A**, identical amounts (1,87,450 + tax) | purchase_register.csv | VP-04 / GS-04 duplicate invoice | TT/2287, TT/2287A |
| A8 | Vendor bank account changed **22:42 on 08-Jan-2025 by ADMIN-1**, payment released 09-Jan, detail changed back 12-Jan — the BRD §10 illustrative example | audit_trail.csv, GL | VP-06, ATR-004 | Fortune Chemicals, FC/5512 |
| A9 | Bank-only items: 4 quarterly bank-charge debits never booked | bank_statement.csv | BK-04 bank-only | CHG2024xxxx refs |
| A10 | Books-only item: cheque 004512 to Deccan Logistics (1,18,000) issued 20-Mar-2025, never presented | bank_ledger.csv | BK-04 books-only, BK-05 stale | CHQ004512 |
| A11 | Grouped receipt: three book receipts of 1,00,000 each = one bank credit of 3,00,000 — the BRD §11 illustrative example | bank | BK-03 one-to-many | AGG300K |
| A12 | GST books-only: 5 purchase invoices absent from GSTR-2B (incl. SRT/0091 and the duplicate TT/2287A) | gstr2b.csv vs purchase_register | GS-01 | see spike output |
| A13 | GST value mismatch: 3 invoices reported in 2B at 90% / 105% / 50% of booked value | gstr2b.csv | GS-01 | see spike output |
| A14 | GST 2B-only: 3 supplier invoices filed in 2B but never booked (GT/8801, IP/3302, MC/1190) | gstr2b.csv | GS-01 | GT/8801, IP/3302, MC/1190 |

| A15 | GSTR-1 under-reporting: 3 sales invoices in the register are absent from GSTR-1 (sales index 10/50/100) | gstr1.csv vs sales_register | GS-02 | see spike/backend output |
| A16 | GSTR-1 value mismatch: 2 invoices reported at 90% / 105% of booked value (sales index 20/60) | gstr1.csv | GS-02 | see output |
| A17 | GSTR-1-only: 2 invoices reported in GSTR-1 but never booked | gstr1.csv | GS-02 | CA/24-25/9001, CA/24-25/9002 |
| A18 | GSTR-3B shortfall: tax declared for period 2025-01 is Rs 50,000 below the GSTR-1 detail total | gstr3b.csv | GS-03 | period 2025-01 |

**Regenerating:** `node generate_data.js` — fully deterministic (seed 20260818); identical output every run.

**Note on realism:** amounts, names, GSTINs and bank accounts are synthetic and structurally simplified (e.g., single 18% GST rate, no CGST/SGST split, zero opening balances). Real partner data in Phase 0 interviews will be messier — that mess is exactly what the discovery interviews must catalogue.
