import { useCallback, useEffect, useState } from 'react';
import { inr } from './types';

interface Bucket {
  valuePct: number;
  digit: string;
  observed: number;
  observedPct: number;
  expectedPct: number;
  excess: number;
}

interface BenfordRun {
  id: string;
  population: string;
  digitTest: string;
  executedAt: string;
  eligibleCount: number;
  eligibleValuePaise: number;
  excludedZeros: number;
  excludedNegatives: number;
  excludedReversals: number;
  suitability: 'SUITABLE' | 'SUITABLE_WITH_CAUTION' | 'NOT_SUITABLE';
  suitabilityReasons: string;
  mad: number | null;
  conformity: string;
  result: { buckets: Bucket[]; topExcessDigit?: string; note?: string; topContributorsByUser?: { user: string; count: number }[] };
}

interface DrillRow {
  voucherId: string;
  txnDate: string;
  userId: string;
  amountPaise: number;
  narration: string;
  sourceRefs: string;
}

const POPULATIONS = ['ALL_VOUCHERS', 'MANUAL_JOURNALS', 'PAYMENTS', 'PURCHASES', 'SALES'] as const;
const TESTS = ['FIRST', 'SECOND', 'FIRST_TWO', 'LAST_TWO', 'SECOND_ORDER'] as const;
const TEST_LABELS: Record<string, string> = {
  FIRST: 'First digit',
  SECOND: 'Second digit',
  FIRST_TWO: 'First two digits',
  LAST_TWO: 'Last two digits (terminal pair — supporting test)',
  SECOND_ORDER: 'Second-order (differences — supporting test)',
};

export default function BenfordPanel({ engagementId, onChanged }: { engagementId: string; onChanged: () => void }) {
  const [population, setPopulation] = useState<string>('ALL_VOUCHERS');
  const [digitTest, setDigitTest] = useState<string>('FIRST');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [run, setRun] = useState<BenfordRun | null>(null);
  const [drill, setDrill] = useState<{ digit: string; rows: DrillRow[] } | null>(null);

  const loadLatest = useCallback(async () => {
    const res = await fetch(`/api/engagements/${engagementId}/benford-runs`);
    if (res.ok) {
      const list = (await res.json()) as BenfordRun[];
      if (list.length > 0) setRun(list[0]);
    }
  }, [engagementId]);

  useEffect(() => { void loadLatest(); }, [loadLatest]);

  async function execute() {
    setBusy(true);
    setError(null);
    setDrill(null);
    try {
      const res = await fetch(`/api/engagements/${engagementId}/benford-runs`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ population, digitTest }),
      });
      const body = await res.json();
      if (!res.ok) throw new Error(body.error ?? `Run failed (${res.status})`);
      setRun(body as BenfordRun);
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function drilldown(digit: string) {
    if (!run) return;
    const res = await fetch(`/api/benford-runs/${run.id}/drilldown?digit=${digit}`);
    if (res.ok) setDrill({ digit, rows: await res.json() });
  }

  const suitClass = run?.suitability === 'SUITABLE' ? 'ok' : run?.suitability === 'SUITABLE_WITH_CAUTION' ? 'warn' : 'warn';
  // runs stored before the storage migration can carry an empty result - never crash on them
  const buckets = run?.result?.buckets ?? [];
  const maxPct = Math.max(...buckets.map((b) => Math.max(b.observedPct, b.expectedPct)), 1);

  return (
    <section className="card">
      <h2>Digit forensics — Benford &amp; supporting tests</h2>
      <div className="form-grid">
        <label>Population (BEN-001)
          <select value={population} onChange={(e) => setPopulation(e.target.value)}>
            {POPULATIONS.map((p) => <option key={p} value={p}>{p.replaceAll('_', ' ')}</option>)}
          </select>
        </label>
        <label>Digit test
          <select value={digitTest} onChange={(e) => setDigitTest(e.target.value)}>
            {TESTS.map((t) => <option key={t} value={t}>{TEST_LABELS[t] ?? t}</option>)}
          </select>
        </label>
      </div>
      <button onClick={execute} disabled={busy}>{busy ? 'Running…' : 'Run Benford analysis'}</button>
      {error && <p className="error">{error}</p>}

      {run && (
        <>
          {/* the suitability verdict outranks the digit chart (BRD §16.4) */}
          <div className={`banner ${suitClass}`}>
            {run.suitability.replaceAll('_', ' ')} — {run.suitabilityReasons}
          </div>
          <table>
            <tbody>
              <tr><th>Population</th><td>{run.population} · {run.digitTest} digit test · {run.eligibleCount.toLocaleString('en-IN')} eligible amounts · ₹ {inr(run.eligibleValuePaise)}</td></tr>
              <tr><th>Exclusions (reported, never silent)</th><td>{run.excludedZeros} zero · {run.excludedNegatives} negative · {run.excludedReversals} reversal-linked</td></tr>
              <tr><th>Conformity</th><td>
                {run.conformity === 'NOT_ASSESSED'
                  ? 'Not assessed — population unsuitable; contributes zero risk points.'
                  : `${run.conformity} (MAD ${run.mad?.toFixed(4)})`}
              </td></tr>
            </tbody>
          </table>

          {buckets.length === 0 && (
            <p className="sub">This run's stored detail predates a storage migration — run the analysis again to regenerate it.</p>
          )}
          {run.result?.note && <p className="sub" style={{ fontStyle: 'italic' }}>{run.result.note}</p>}
          <p className="sub">
            In plain language: in naturally occurring amounts, smaller leading digits appear more often
            (1 ≈ 30%, 9 ≈ 4.6%). A deviation is a clue about which entries deserve review — never proof of anything.
            Click a row to see the exact contributing transactions.
          </p>

          <div className="benford-table-wrap">
            <table>
              <thead><tr><th>Digit</th><th>Observed</th><th>Observed %</th><th>Expected %</th><th title="Summation test: this bucket's share of total VALUE — a spike marks large-amount concentration">Value %</th><th>Excess</th><th>Distribution</th></tr></thead>
              <tbody>
                {buckets.map((b) => (
                  <tr key={b.digit}
                      className={(drill?.digit ?? run.result?.topExcessDigit) === b.digit ? 'selected' : ''}
                      style={{ cursor: 'pointer' }}
                      onClick={() => void drilldown(b.digit)}>
                    <td><strong>{b.digit}</strong></td>
                    <td className="num">{b.observed.toLocaleString('en-IN')}</td>
                    <td className="num">{b.observedPct.toFixed(2)}</td>
                    <td className="num">{b.expectedPct.toFixed(2)}</td>
                    <td className="num">{b.valuePct != null ? b.valuePct.toFixed(2) : ''}</td>
                    <td className="num">{b.excess > 0 ? '+' + b.excess : ''}</td>
                    <td style={{ minWidth: 160 }}>
                      <div className="bar obs" style={{ width: `${(b.observedPct / maxPct) * 100}%` }} />
                      <div className="bar exp" style={{ width: `${(b.expectedPct / maxPct) * 100}%` }} />
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="sub"><span className="bar obs" style={{ display: 'inline-block', width: 24 }} /> observed · <span className="bar exp" style={{ display: 'inline-block', width: 24 }} /> expected</p>

          {(() => {
            // QA P2: the contributor line must follow the SELECTED digit, not stay
            // frozen on the max-excess default — otherwise digit-4 transactions get
            // attributed to digit-2's users when reading top-to-bottom
            if (drill && drill.digit !== run.result?.topExcessDigit) {
              const byUser = new Map<string, number>();
              for (const r of drill.rows) byUser.set(r.userId, (byUser.get(r.userId) ?? 0) + 1);
              const top = [...byUser.entries()].sort((a, b) => b[1] - a[1]).slice(0, 5);
              return top.length > 0 && (
                <p className="sub">
                  Digit {drill.digit} contributors by user:{' '}
                  {top.map(([u, n]) => `${u} (${n})`).join(', ')}
                </p>
              );
            }
            return run.result?.topContributorsByUser && run.result.topContributorsByUser.length > 0 && (
              <p className="sub">
                Digit {run.result.topExcessDigit} contributors by user:{' '}
                {run.result.topContributorsByUser.map((c) => `${c.user} (${c.count})`).join(', ')}
              </p>
            );
          })()}

          {drill && (
            <>
              <h3>Transactions beginning with {drill.digit} ({drill.rows.length}{drill.rows.length === 500 ? '+' : ''})</h3>
              <table>
                <thead><tr><th>Voucher</th><th>Date</th><th>User</th><th>Amount</th><th>Narration</th></tr></thead>
                <tbody>
                  {drill.rows.slice(0, 50).map((r) => (
                    <tr key={r.voucherId}>
                      <td>{r.voucherId}</td>
                      <td>{r.txnDate}</td>
                      <td>{r.userId}</td>
                      <td className="num">₹ {inr(r.amountPaise)}</td>
                      <td>{r.narration}<div className="sub mono">{r.sourceRefs}</div></td>
                    </tr>
                  ))}
                </tbody>
              </table>
              {drill.rows.length > 50 && <p className="sub">Showing first 50 of {drill.rows.length}.</p>}
            </>
          )}
        </>
      )}
    </section>
  );
}
