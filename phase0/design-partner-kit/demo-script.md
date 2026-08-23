# PRAMETRA Pilot Demo Script — 15 minutes, live platform

*Runbook for a partner meeting. Everything below uses the live demo at
https://liaiapp.magizhchi.software (opens signed-in to a shared demo firm holding
CLIENT-A, a synthetic financial year with 18 seeded anomaly patterns). Practise once
before the meeting; total talking time ~12 minutes, leaving room for questions.*

## 0 · Framing (1 min)

> "Everything you'll see is computed from a full-year general ledger — 3,000 rows,
> not a sample. The platform never says 'fraud'. It says 'these entries deserve your
> review, and here is exactly why, down to the row of the original file'."

## 1 · Overview (2 min) — select CLIENT-A in the sidebar

- Point at the stat tiles: population, open exceptions with HIGH count, **de-duplicated
  estimated exposure** ("several rules flagging the same voucher count once — most tools
  get this wrong").
- The two charts: exposure by posting month; which rules drive the risk.

## 2 · The star case (4 min) — Core Analysis → top case

- Open the top case (the vendor cluster). Point at the **family chips**:
  > "Four *independent* method families corroborate here — reconciliation, behaviour,
  > statistical, relationship. Corroboration is what raises priority; ten variants of
  > one signal can't."
- In the drawer: click **View source rows** on an exception —
  > "This is the flagged voucher inside its original file, neighbours included.
  > Every finding in the platform can do this."
- Click **✨ Explain in plain language** — read the AI draft aloud, then point at the
  label: *AI draft — review required*.
  > "The AI only ever narrates facts the engine computed. It cannot touch a number,
  > and nothing becomes a record without a human."
- Make one decision (status + reason) — show it lands with your identity, then open
  **History**: append-only, nothing overwritable.

## 3 · Benford properly (2 min) — Benford panel

- Run **First digit** on ALL VOUCHERS: nonconformity, drill into the excess digit.
- Then the line that lands with every auditor:
  > "Before scoring anything it runs a suitability gate — small populations,
  > fixed-price sets, narrow ranges are *refused*, not scored. A Benford chart on an
  > unsuitable population is worse than none, so the platform won't draw one."
- Show the **Value %** column: "digit 1 has 31% of the entries but only 22% of the
  money — the summation test shows where the value hides."

## 4 · GST triangle + vendor risk (3 min)

- GST view: purchases↔2B and sales↔GSTR-1 counts, the correction schedule CSV
  ("this is the file your GST team actually wants").
- Vendor view → **Build vendor risk report**:
  > "Shri Ram Traders tops the list at 55/100 — three exception signals, a GST
  > mismatch with ITC at stake, and a bank account shared with a suspiciously
  > similar vendor name. Every component is capped and explained."

## 5 · The deliverable (2 min) — Workpapers

- Generate a workpaper; show sign-off order enforcement (same person can't sign twice).
- Click **Audit File Pack (.zip)**:
  > "One click: workpaper HTML and PDF, the exception register in Excel, the GST
  > correction schedule, SHA-256 checksums of every source file, and the exact
  > methodology — rule pack version, weights, caps — that produced the results.
  > That's what reproducibility means here."

## 6 · Close (1 min)

> "What you saw is seeded data. The pilot is: one of your real engagements,
> anonymised, run end-to-end, with a weekly half-hour where you tell us which flags
> were real and which were noise. We measure two numbers together — false-positive
> rate and reviewer hours saved — and those numbers decide whether this is worth
> paying for. Free during the pilot, founding terms at launch."

## If asked…

- **"Can it declare fraud?"** — No, by design; the BRD forbids it. It prepares
  evidence; you conclude.
- **"Where is our data?"** — Isolated per firm, encrypted in transit and at rest,
  never shared, never used to train shared models, deletable on request in writing.
- **"What about our Tally files?"** — Tally XML, CSV and Excel imports are live;
  we'll build a mapping profile for your exact export in onboarding.
- **"What's not built?"** — Revenue/expense analytics and GST amendment handling —
  deliberately, because building them without real data would be guesswork. Your
  pilot data is what unblocks them.
