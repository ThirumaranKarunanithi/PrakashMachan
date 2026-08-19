# Pilot Data Request & Anonymisation Checklist

*Given to each design partner to prepare one anonymised engagement dataset. Field list mirrors BRD §5 (minimum data fields). Files may be Excel/CSV; Tally XML also accepted.*

## Files requested (one financial year, one entity)

| # | File | Minimum fields | Notes |
|---|---|---|---|
| 1 | General ledger / journal export | Voucher ID, voucher type, transaction date, creation date (if available), account code+name, debit, credit, narration, source module, user, reversal link | Tally: Daybook export incl. all vouchers |
| 2 | Trial balance | Account code, opening, debit, credit, closing | Same period as GL |
| 3 | Sales register | Invoice no/date, customer, GSTIN, taxable value, tax amounts, HSN/SAC, place of supply, credit notes | |
| 4 | Purchase register | Invoice no/date, supplier, GSTIN, taxable value, tax amounts, RCM flag, credit notes | |
| 5 | Vendor / customer master | ID, name, GSTIN, bank account, IFSC, creation date, created-by, status | |
| 6 | GSTR-1, GSTR-2B, GSTR-3B | Standard portal downloads (JSON/Excel) for the same periods | Keep tax-period versions |
| 7 | Bank statement(s) | Date, value date, debit/credit, amount, reference, narration, running balance | CSV/Excel preferred over PDF |
| 8 | Audit trail / user log (if available) | Object, old value, new value, user, timestamp, action | Tally Edit Log if enabled |

## Anonymisation checklist (do BEFORE sharing)

- [ ] Replace legal entity name with a code (e.g., "CLIENT-A").
- [ ] Replace customer/vendor names with consistent pseudonyms (keep the SAME pseudonym everywhere — matching must still work).
- [ ] Mask GSTINs consistently: keep state code + a stable fake core (same input → same masked output).
- [ ] Mask bank account numbers consistently (keep last 4 digits if possible).
- [ ] Remove PAN, Aadhaar, personal phone numbers, personal emails and addresses.
- [ ] Replace employee/user names with role codes (e.g., "ACCT-1", "ADMIN-1").
- [ ] Keep ALL dates, amounts, account codes, narration structure and record counts unchanged — these drive the analysis.
- [ ] Confirm client consent / engagement-letter permission for anonymised data sharing.

**Consistency matters more than secrecy technique:** if "Sharma Traders" becomes "VENDOR-017" in the vendor master, it must be "VENDOR-017" in the purchase register, GSTR-2B and bank narration too, or reconciliation tests will falsely fail.
