# Pilot Success Metrics — AC-17 / AC-18 Measurement Plan

*The two numbers that decide whether the platform is worth paying for. Agreed with
the firm at kickoff, measured jointly in the weekly review, reported at pilot end.
The exception register export (Excel) is the raw data for both.*

## Metric 1 — Signal quality (AC-17: false-positive rate)

Every flagged exception ends the pilot in one of these buckets, decided by the
firm's reviewer (the platform records the decision and who made it):

| Bucket | Platform status | Counts as |
|---|---|---|
| Real issue — adjustment, recovery, or documented management action | CONFIRMED / ESCALATED | True positive |
| Legitimate once explained, but *worth having looked at* | EXPLAINED | Useful signal |
| Noise — should not have been flagged | NOT_APPLICABLE | False positive |

**Measures** (from the exception register at pilot end):

- **Precision** = (true positives + useful signals) ÷ all reviewed exceptions
- **Noise rate** = false positives ÷ all reviewed exceptions
- **Target** (proposed, to agree at kickoff): noise rate ≤ 30% on the first
  engagement, improving after one parameter-tuning cycle — rule thresholds and
  family caps are firm-configurable at runtime, so tuning needs no deployment.

Also tracked: did the top-5 priority cases contain the engagement's genuinely most
important findings? (Partner judgement, yes/no per case — this validates the
family-capped scoring, not just individual rules.)

## Metric 2 — Reviewer time (AC-18: hours saved)

Baseline first: in the kickoff interview, the firm estimates hours its team spent
on the equivalent work last year for the same client (journal testing, GST
reconciliation, bank reconciliation, workpaper assembly). Then during the pilot:

| Activity | How measured |
|---|---|
| Exception review sessions | Logged per weekly session (start/stop, attendees) |
| Evidence collection | Days from request to response via the portal (the platform records both timestamps) |
| Workpaper assembly | Time from "analysis complete" to signed workpaper |

**Measure**: pilot hours vs. baseline estimate, stated as hours saved per
engagement and as a percentage. **Target** (proposed): ≥ 30% reduction on the
review-and-assembly portion.

## Weekly review agenda (30–45 min)

1. New exceptions since last week — bucket each (5 min per unclear one, max).
2. One parameter-tuning decision if noise clusters on a rule (raise a threshold,
   adjust a family cap) — recorded in the versioned methodology config.
3. Data the platform still needs (unlocks the roadmap: revenue/expense analytics
   needs dispatch/inventory data; GST amendment handling needs amended returns).
4. Two-line log entry: date, decisions made, parameters changed.

## Pilot-end report (we produce it)

- Both metrics with the underlying register attached
- Every parameter change and its effect on noise
- The firm's verdict per top-5 case
- Recommendation: proceed to commercial terms / iterate / stop
