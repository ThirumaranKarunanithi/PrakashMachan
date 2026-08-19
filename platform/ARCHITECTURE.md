# Platform Architecture — Phase 1 starting decisions

*Working decisions for the MVP core. Anything marked OPEN is deliberately deferred; everything else can still be challenged, but code currently assumes it.*

## Decided (for now)

| Area | Decision | Why |
|---|---|---|
| Language | TypeScript (strict) on Node.js | Team tooling already Node; strong typing for a correctness-critical domain; runs everywhere we might deploy |
| Money | **Integer paise** end-to-end (`bigint`-free, `number` safe ≤ ₹90 crore per line; revisit for larger lines) | Floating-point rupees cause reconciliation false-positives — the one bug this product can never have |
| Dates | ISO `YYYY-MM-DD` strings internally; source formats converted at the import boundary via mapping profile | String compare = chronological compare; no timezone surprises |
| Core shape | Framework-free library (`src/core`, `src/import`) + thin CLI | The BRD demands reproducibility and testability; web/API layers come later and stay thin |
| Lineage | Every normalised row carries `{file, row}` source reference (DAT-005) | Every exception must trace to its original record |
| Reproducibility | SHA-256 checksum + source manifest per imported file (DAT-001) | Same snapshot + same rule version ⇒ same results |
| Tests | `node --test` (built-in) against compiled output; sample data from `../phase0/sample-data` as the integration fixture | Zero test-framework dependencies |

## OPEN — to decide with design-partner input / before first real data

1. **Database.** PostgreSQL is the working favourite (relational fits vouchers/exceptions; row-level security helps tenant isolation SEC-001). Not needed until the exception workflow persists state.
2. **API + UI framework.** Deferred until the import core is proven.
3. **Tally XML ingestion.** MVP scope per BRD §4.1 — parser to be built after CSV/Excel path is solid; mapping-profile design already accommodates it (`sourceType`).
4. **Excel (.xlsx) parsing.** Needs a dependency choice (e.g. exceljs) — deferred; CSV covers Phase 0/pilot data.
5. **Deployment/residency.** India data-residency expectations — Phase 0 interview question (security baseline §Open decisions).

## Module map (current)

```
src/core/csv.ts        RFC-4180-ish CSV parse/serialise
src/core/checksum.ts   SHA-256 file checksums + source manifest (DAT-001)
src/import/types.ts    Standard field model (BRD §5) + issue/report types
src/import/mapping.ts  Reusable column-mapping profiles (DAT-004)
src/import/normalize.ts Source rows -> standard ledger rows with lineage (DAT-005)
src/import/quality.ts  Data-quality report: missing/invalid/duplicate/non-numeric/unmapped (DAT-003)
src/import/validate.ts Dr=Cr, per-voucher balance, ledger-vs-TB reconciliation (DAT-002)
src/import/delta.ts    Row-identity hashing for duplicate-free delta imports (DAT-006)
src/cli.ts             `import-gl` command — runs the full pipeline, writes report + manifest
```
