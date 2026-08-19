# Security Requirements Baseline — Phase 0/1

*Derived from BRD §19 and §4.3. This is the commitment set shown to design partners and the checklist engineering must satisfy before any real (even anonymised) partner data is accepted.*

## Phase 0 rules of engagement (before we have a product)

- Only **synthetic or anonymised** data is accepted. The anonymisation checklist in `../design-partner-kit/data-request-list.md` is mandatory.
- Partner files are stored encrypted, access limited to named team members, and deleted on request within 30 days with written confirmation.
- No partner data is ever placed in a shared AI training corpus (BRD SEC-005).

## Product requirements (BRD §19.1) — build checklist

| ID | Requirement | Phase | Verification |
|---|---|---|---|
| SEC-001 | Firm- and engagement-level tenant isolation | MVP | Independent penetration/access test |
| SEC-002 | Encryption in transit (TLS 1.2+) and at rest | MVP | Config review + test |
| SEC-003 | MFA + role-based access, least privilege | MVP | Permission matrix review |
| SEC-004 | Tamper-evident logging of import, run, view, export, decision, permission change, deletion | MVP | Log completeness test |
| SEC-005 | No client data in shared AI training without contract | MVP | Contract + architecture review |
| SEC-006 | Customer-controlled retention and secure deletion | MVP | Deletion produces auditable record |
| SEC-007 | Time-bound, logged support access | MVP | Support-access history reviewable |
| SEC-008 | Export watermarking / classification labels | Later | Export traceable to engagement + user |

## Data governance (BRD §19.3)

- Classify separately: raw client data · derived results · evidence · workpapers · system logs.
- Source manifest + checksum for every imported file (also required by DAT-001).
- Record data owner, purpose, location, retention period, deletion status.
- Mask/tokenise personal fields where full values are unnecessary.
- Restrict and log bulk export.

## Non-functional targets to design against (BRD §19.2)

- ≥ 1 million journal lines per engagement.
- Reproducibility: same snapshot + same rule version ⇒ identical results.
- Explainability: every case exposes rule, source records, calculation, reviewer history.
- Accessibility: no conclusion conveyed by colour alone.

## Open security decisions for Phase 0 interviews

1. Where must data reside? (India data-residency expectations from partners/clients.)
2. Cloud multi-tenant acceptable for MVP, or do any partners require single-tenant?
3. What client-consent language do partners' engagement letters already contain?
4. Retention default: engagement + how many years (align with firm documentation policy / SA 230)?
