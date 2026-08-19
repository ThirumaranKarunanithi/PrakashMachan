# MVP Rule Prioritisation Matrix

*The 34-rule MVP pack from BRD §21.1, laid out for design partners to rank. Each partner scores **Value** (1–5: how often this finds something that matters) — the other columns are pre-filled engineering estimates to be refined.*

**Build effort:** S = days, M = 1–2 weeks, L = 2+ weeks (single engineer, after data import exists).
**Data dependency:** what the rule cannot run without — drives which import features come first.

## Journal-entry controls (10)

| ID | Rule | Effort | Data dependency | Partner value (1–5) |
|---|---|---|---|---|
| JE-01 | Manual vs system-generated entries | S | GL with source module | |
| JE-02 | Privileged-user postings | S | GL with user + privileged-user list | |
| JE-03 | Post-close entries (created after close, posted before) | M | GL with creation timestamp + close calendar | |
| JE-04 | Backdated entries (txn date ≠ creation date pattern) | M | GL with creation timestamp | |
| JE-05 | Duplicate / near-duplicate journals | M | GL | |
| JE-06 | Unusual account combinations | L | GL + client history | |
| JE-07 | Round amounts | S | GL | |
| JE-08 | Threshold proximity (just below approval limits) | S | GL + threshold config | |
| JE-09 | Quick reversal after period end | M | GL with reversal links or matching | |
| JE-10 | Blank / vague narration | S | GL | |

## GST controls (8)

| ID | Rule | Effort | Data dependency | Partner value (1–5) |
|---|---|---|---|---|
| GS-01 | Purchase register ↔ GSTR-2B match | L | Purchase register + 2B | |
| GS-02 | Sales register ↔ GSTR-1 match | L | Sales register + GSTR-1 | |
| GS-03 | GSTR-1 ↔ GSTR-3B summary check | M | GSTR-1 + 3B | |
| GS-04 | Duplicate invoice in register/return | S | Registers | |
| GS-05 | Tax-amount mismatch (rate × taxable ≠ tax) | S | Registers | |
| GS-06 | GSTIN validity / place-of-supply consistency | M | Registers + GSTIN master | |
| GS-07 | Credit-note tracing to original invoice | M | Registers | |
| GS-08 | Timing / amendment tracking across periods | L | Multi-period returns | |

## Vendor / payment controls (6)

| ID | Rule | Effort | Data dependency | Partner value (1–5) |
|---|---|---|---|---|
| VP-01 | Exact + fuzzy duplicate vendors | M | Vendor master | |
| VP-02 | Shared bank account / contact details | S | Vendor master with bank fields | |
| VP-03 | New-vendor immediate large activity | S | Vendor master + purchases | |
| VP-04 | Duplicate invoice / duplicate payment | M | Purchases + payments | |
| VP-05 | Split transactions below approval threshold | M | Purchases/payments + threshold config | |
| VP-06 | Bank-detail change shortly before payment | M | Vendor master history or audit trail | |

## Bank controls (5)

| ID | Rule | Effort | Data dependency | Partner value (1–5) |
|---|---|---|---|---|
| BK-01 | Exact match (amount + date + reference) | M | Bank statement + bank ledger | |
| BK-02 | Tolerance match (date window) | S* | BK-01 | |
| BK-03 | Grouped match (one-to-many / many-to-one) | L | BK-01 | |
| BK-04 | Bank-only / books-only classification | S* | BK-01 | |
| BK-05 | Stale / aged unreconciled items | S | BK-01 + prior status | |

*after BK-01 exists

## Cross-cutting controls (5)

| ID | Rule | Effort | Data dependency | Partner value (1–5) |
|---|---|---|---|---|
| XC-01 | Period-end window analysis | M | GL + close calendar | |
| XC-02 | Management-override / SoD conflicts | L | User-role data + approvals | |
| XC-03 | Missing-evidence tracking | M | Evidence workflow | |
| XC-04 | Benford suitability gate + first/two-digit analysis | M | Any monetary population | |
| XC-05 | Case consolidation (related flags → one case) | L | All other rules | |

## How we will prioritise

Priority = mean partner Value score, tie-broken by lower effort. Rules that depend on data most clients cannot supply (e.g., creation timestamps missing from older Tally exports) get a **data-availability discount** noted during interviews (question B.9/D.18 in the questionnaire).

**Working hypothesis to validate:** GS-01 (books↔2B), JE-03/04 (post-close/backdated), VP-01/02 (duplicate vendors/shared bank) and BK-01..04 are the highest-value first wave.
