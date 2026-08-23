# Design Partner Brief — Ledger Integrity & Audit Intelligence Platform

*One page for a CA firm partner deciding whether to join the pilot programme.*

## What it is — built and running today

A multi-client platform that analyses **complete transaction populations** (not samples), flags unusual entries through **21 independent detection rules across six risk families** (statistical, deterministic, reconciliation, behaviour, relationship, evidence), reconciles books against GSTR-1/2B/3B and bank statements, collects client evidence through a secure portal, and produces review-ready workpapers with a one-click **Audit File Pack** (workpaper PDF, exception register, correction schedule, source-file checksums, methodology record).

**The product never declares fraud.** It ranks what deserves review, shows the exact source rows behind every signal, and keeps every conclusion in the auditor's hands.

**Live demo:** https://liaiapp.magizhchi.software — a full synthetic financial year with 18 seeded anomaly patterns, all detected. Ten minutes, no installation.

## Highlights a partner will care about

- **Explainable priority scores** — every case shows its capped per-family breakdown; four statistical signals can never masquerade as four independent facts.
- **Benford & digit forensics done properly** — a suitability gate refuses to score unsuitable populations; six digit tests including terminal-pair and summation; drill-down to exact vouchers.
- **Per-vendor risk report** — composite scores from signals the platform already holds; the seeded duplicate-vendor cluster ranks first, caught by a shared bank account.
- **Everything traceable** — SHA-256 manifests of source files, row-level lineage, versioned rule packs, append-only decision history with session-attributed identity.
- **AI drafting, safely bounded** — plain-language explanations and case summaries, always labelled drafts, never touching the numbers.
- **Security** — firm-level isolation, TOTP MFA, CSRF protection, database-backed sessions, evidence documents encrypted at rest (AES-256-GCM), append-only audit log.

## What you get as a design partner

- Free access for your firm during the pilot period.
- Direct influence over rules, thresholds, workpaper templates and the roadmap (revenue/expense analytics and GST amendments are deliberately waiting for real pilot data).
- Founding-partner commercial terms at launch.
- A pilot engagement run end-to-end (upload → exceptions → evidence → signed workpaper) with our support.

## What we ask of you

| Ask | Effort |
|---|---|
| One discovery interview (workflow walkthrough) | 60–90 min, once |
| One real engagement's data (GL, registers, GST files, bank statement — anonymised per our checklist) | 2–4 hours with our checklist |
| Weekly noise-vs-real review of flagged exceptions during the pilot | 30–45 min/week |
| Judge the two success metrics with us: false-positive rate and reviewer time saved | Built into the weekly review |

## Data protection commitments (summary)

- Anonymised data during onboarding; we supply the anonymisation checklist.
- Your data is never shared with other firms and never used to train shared AI models.
- AI drafting sends only engine-computed facts to the AI provider, is clearly disclosed in the agreement, and can be disabled entirely.
- Firm-level and engagement-level isolation; encrypted in transit and at rest; you can require deletion at any time, confirmed in writing.
- Full commitments: `pilot-agreement-outline.md` and `../security/security-requirements.md`.

## What this is not

- Not audit, tax or legal advice. Your firm's methodology and sign-off remain authoritative.
- Not an automatic GST filing tool, and not a system that edits your client's books.

**Contact:** [Product Sponsor name / email]
