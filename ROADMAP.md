# Working Plan — from MVP-live to pilot-proven

Status at start: platform deployed (frontend `liaiapp.magizhchi.software` on Hostinger, API `liai.magizhchi.software` on Railway), ~94% of BRD MVP weight built, 36/36 tests green, 14/18 acceptance criteria met. What remains is not primarily code: production hardening, real pilots, and the build items that need real data.

---

## Phase A — Production hardening (weeks 1–2) · closes AC-15/16

Do these **before any real client data enters the system**.

| # | Item | Why / BRD ref |
|---|---|---|
| A1 | **Confirm Postgres persistence + automated backups.** Verify `SPRING_DATASOURCE_*` vars point at Railway Postgres (smoke-test: does a login survive a redeploy?). Enable Railway backups or a nightly `pg_dump`. | Without this, a redeploy wipes everything |
| A2 | **Persistent sessions** (Spring Session JDBC) so backend restarts don't sign everyone out | NFR polish, cheap |
| A3 | **MFA (TOTP)** for staff roles + minimum password policy | SEC-001, AC-15 |
| A4 | **Re-enable CSRF** with the SPA cookie-to-header token pattern | documented MVP trade-off, now due |
| A5 | **At-rest encryption for evidence documents** + retention policy execution | SEC-004/005, AC-16 |
| A6 | **Error monitoring + uptime check** (Railway logs → alert, or Sentry free tier) | you want to know before the pilot firm does |
| A7 | Housekeeping: delete the SMOKE-TEST firm, register the real firm, create staff users | — |

**Exit criteria:** a redeploy loses nothing; a stolen password alone can't get in; evidence files encrypted at rest.

## Phase B — Pilot recruitment (weeks 2–4, in parallel)

The `phase0/` kit was built exactly for this.

| # | Item |
|---|---|
| B1 | Shortlist 5–6 candidate CA firms; approach with `phase0/partner-brief` |
| B2 | Demo using the seeded CLIENT-A engagement (18 known anomalies → live case walk-through, ₹49L JRN-90001 case as the showpiece) |
| B3 | Run `phase0/discovery-questionnaire` with interested firms; collect their actual export formats (Tally version, Excel layouts) |
| B4 | Sign 2–3 pilots on `phase0/pilot-agreement-outline` (includes data-handling terms) |

**Exit criteria:** 2–3 signed pilot firms with named engagement teams.

## Phase C — Pilot execution (weeks 4–10) · closes AC-17/18

| # | Item |
|---|---|
| C1 | Onboard firm 1: build mapping profiles for their real GL/TB exports (the mapping-profile system is built for this — expect 1–2 new profiles per firm) |
| C2 | Import one full FY per pilot client; run the complete rule pack + GST + bank + Benford |
| C3 | Weekly review loop with each firm: which exceptions were real, which were noise → tune rule parameters and risk weights (both firm-configurable at runtime, no deploys needed) |
| C4 | **Measure AC-17** (false-positive rate ≤ agreed threshold) and **AC-18** (reviewer time saved vs. their manual process) — log per engagement |
| C5 | Collect the data samples that unblock Phase D: dispatch/inventory registers, amended GST returns, approval workflows |

**Exit criteria:** documented AC-17/18 numbers from at least 2 firms; prioritized feedback backlog.

## Phase D — Data-gated build items (from week 6, as pilot data arrives)

In priority order, each unblocked by a specific Phase C input:

1. **REM module (§9)** — revenue/expense mismatch rules, once dispatch/inventory data exists (currently 0%, deliberately)
2. **GST amendment handling** — needs real amended-return samples
3. **Approval-sequence checks (§13)** — needs a firm's real approval workflow (takes §13 from 57% up)
4. **Vendor relationship graph + whitelist (§8)** — takes §8 from 72% up
5. **Override / period-end timeline views (AC-08)**
6. Rule pack `0.5.0` — pilot-tuned parameters as new defaults

## Ongoing

- Bug triage from pilots ahead of new features; keep the test suite green on every push (Railway auto-deploys `main`)
- Update the scoreboard artifact at each phase boundary
- **Decision point after Phase C:** pricing/licensing model and go-to-market, informed by measured AC-18 time savings

---

*Sequencing logic: A before C because real client data must not enter an unhardened system. B overlaps A because recruitment has long lead times and needs no code. D trails C deliberately — building REM or amendment handling before seeing real data would be speculation, which is why the BRD gated them.*
