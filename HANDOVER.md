# Ledger Integrity & Audit Intelligence Platform — Handover

**Status:** MVP complete against BRD v1.0 (~94% of MVP requirement weight) · 36/36 automated tests green · verified live 19 Aug 2026
**Scoreboard:** the live status artifact ("Ledger Integrity Scoreboard") tracks coverage per BRD section and acceptance criterion.

Core product principle (BRD): the platform **flags risks and prepares evidence; it never declares fraud**. Human judgement is final. Every number is reproducible and traceable to a source file + row.

---

## 1 · Repository layout

| Folder | What it is |
|---|---|
| `Ledger_Integrity_Audit_Intelligence_BRD_v1.0.docx` | The requirements document everything is measured against |
| `phase0/` | Design-partner package: partner brief, discovery questionnaire, data-request list, pilot agreement outline, **synthetic sample data with 18 seeded anomaly groups** (`sample-data/`, regenerate with `node generate_data.js` — deterministic, seed 20260818), rule matrix, security baseline |
| `backend/` | **Product backend — Spring Boot 3.3.5 / Java 17 (Maven)** |
| `frontend/` | **Product frontend — React 18 + Vite + TypeScript** |
| `platform/` | Superseded TypeScript prototype of the import module. Reference only — do not extend |

## 2 · Running it

```
cd backend  && mvn spring-boot:run        # API on :8080  (H2 file DB in backend/data/)
cd frontend && npm install && npm run dev  # UI on :5173, Vite proxies /api -> :8080
cd backend  && mvn test                    # full suite, in-memory DBs, no server needed
```

First use: **Register firm** on the login screen (creates the firm + its first ADMIN/PARTNER user), then sign in. The current dev database already contains a demo firm — `partner@demo.firm` / `demo-pass-1` — with three engagements: CLIENT-A (full 3,008-row synthetic year), TALLY-CLIENT (Tally XML import), XLSX-DEMO (Excel import).

## 3 · What is built (by BRD section)

| Module | BRD | Highlights |
|---|---|---|
| Data import & quality | §5 DAT-001..006 | CSV, **Tally XML**, **Excel .xlsx** (dates normalised to ISO whatever the cell format; the manifest hashes the *original* workbook bytes). SHA-256 source manifest, row-level lineage `{file, row}`, mapping profiles with header checks, data-quality report (downloadable CSV), voucher-balance + trial-balance validation, idempotent delta re-imports |
| Journal-entry testing | §6 | Rule pack `mvp-pack-0.5.0` — 20 versioned rules (JE/VP/PET/MOT + STA statistical layer: peer-group Modified Z-score outliers, rare user-account combinations, threshold bunching, rolling-baseline activity spikes), parameters + population filters snapshotted on every run for reproducibility, source classification, risk-ranked **and** seeded-random sampling |
| GST reconciliation | §7 | Purchases↔GSTR-2B, Sales↔GSTR-1, GSTR-1↔3B; 1-rupee tolerance; fuzzy suggestions with confidence scores; manual links with mandatory reason (GST-007); correction schedule CSV (GST-008); **multi-GSTIN registration summary** via optional `own_gstin` column (GST-009) |
| Vendor & payment | §8 | Duplicate invoices, shared bank accounts, invoice splitting, bank-change-then-payment sequence |
| Audit-trail review | §10 | Coverage-gap detection (>30-day silences), trail disablement, configuration-change linking, report-only or exception-raising modes |
| Bank reconciliation | §11 | Exact / tolerance / one-to-many grouped / manual matches; unexplained-difference sign-off gate (BKR-006) |
| Period-end testing | §12 | Close-window activity, late posting lag, provision/suspense round amounts, close-volume baseline |
| Management override | §13 | SoD conflicts, privileged-user posting patterns |
| Evidence collection | §14 | Requests with due dates, versioned uploads, **client portal** (CLIENT role, sanitised DTOs, multi-file upload), overdue notifications, auditable secure deletion (SEC-006) |
| Workpapers | §15 | HTML snapshot + SHA-256, ordered sign-off with same-person rejection (AWP-005), locked when signed (AWP-006), firm-versioned templates (AWP-001), HTML/Word export |
| Benford's Law | §16 | Suitability gate **before** scoring (population size, orders of magnitude, dominant-value checks), first/second/first-two digit tests plus a last-two-digit terminal-pair supporting test, Nigrini MAD bands, drill-down, prior-period comparison, deliberately neutral wording |
| Risk scoring & cases | §17 | Exceptions consolidated into cases by shared voucher/vendor/bank/invoice tokens (union-find, merge-stable); explainable scores; firm-configurable weights; reviewer priority override with recorded reason |
| Dashboards & alerts | §18 | Portfolio view, risk explorer, deduplicated in-app notifications (NFR-003), methodology settings |
| Security & tenancy | §19 | Session auth (BCrypt), roles ADMIN/PARTNER/MANAGER/ASSOCIATE/CLIENT, firm-scoped TenantGuard (cross-tenant → 404), append-only audit log on every request |

**Exception lifecycle** (§3.3): NEW → UNDER_REVIEW / INFO_REQUIRED → EXPLAINED / CONFIRMED / NOT_APPLICABLE / ESCALATED → CLOSED; decision states require a documented reason. The exception pipeline is idempotent: identity = sha256(ruleId | voucher tokens), so re-running rules never duplicates findings.

**Proof against ground truth:** all 18 seeded anomaly groups (A1–A18 in `phase0/SEEDED_ANOMALIES.md`) are detected; the ₹49L JRN-90001 case consolidates 9 cross-module signals.

## 4 · What is intentionally not built, and why

| Item | Reason |
|---|---|
| MFA, TLS, at-rest encryption, support-access controls (AC-15/16 hardening) | Deployment concerns — do them on the real hosting target, not the dev laptop |
| Revenue/expense module (REM, §9) | Blocked on design-partner data; rules without real dispatch/inventory data would be speculation |
| GST amendment handling | Needs real amended-return samples from a pilot |
| AC-17/18 (pilot outcomes), §25.1 committees | Only real pilots can close these — the `phase0/` kit exists precisely to start them |
| PDF bank-statement parsing | Deferred by design; CSV covers the pilot scope |

## 5 · Operational notes (read before changing things)

- **Dev DB** is H2 in PostgreSQL mode at `backend/data/platform.*` (`ddl-auto: update`). Tests use throwaway in-memory DBs. For production, move to real PostgreSQL — the schema is written to be compatible.
- **Evolving enums** must be declared `@Column(columnDefinition = "varchar(N)")` — H2 creates native enum columns otherwise, which reject values added later. This bit three times (roles, GST categories, bank match types); the pattern is applied everywhere it matters now.
- **CSRF is disabled** (documented MVP trade-off — JSON session auth). Revisit with the deployment hardening pass.
- **Sessions are in-memory** — a backend restart signs everyone out.
- Money is **integer paise** (`long`) everywhere; never floats. Dates are `LocalDate`/`LocalDateTime`, ISO on the wire.
- Every rule run stores its **pack version, parameters, and population filter** as JSON — that is what makes results reproducible; keep it that way when adding rules (bump the pack version).

## 6 · Suggested next steps

1. Recruit 2–3 design-partner firms with the `phase0/` kit; run pilots on real data (closes AC-17/18, unblocks REM and GST amendments).
2. Deployment hardening: PostgreSQL, TLS, MFA, at-rest encryption, CSRF, persistent sessions, backups (§19 remainder).
3. Tune rule parameters and risk weights against pilot feedback — both are already firm-configurable at runtime.
