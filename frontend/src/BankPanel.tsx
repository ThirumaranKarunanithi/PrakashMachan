import { useCallback, useEffect, useState } from 'react';
import { inr } from './types';

interface ImportOutcome {
  totalRows: number;
  added: number;
  skipped: number;
  problems: string[];
}

interface ReconcileResult {
  exact: number;
  tolerance: number;
  grouped: number;
  bank_only: number;
  books_only: number;
  statementNetPaise: number;
  ledgerNetPaise: number;
  unexplainedPaise: number;
  exceptionsCreated: number;
  skippedExisting: number;
}

interface MatchRow {
  matchType: string;
  date: string;
  reference: string;
  description: string;
  amountPaise: number;
  outflow: boolean;
  voucherIds: string;
  dateGapDays: number | null;
}

const TYPES = ['BANK_ONLY', 'BOOKS_ONLY', 'MANUAL', 'GROUPED', 'TOLERANCE', 'EXACT'] as const;

export default function BankPanel({ engagementId, onReconciled }: { engagementId: string; onReconciled: () => void }) {
  const [counts, setCounts] = useState<{ statementLines: number; ledgerLines: number } | null>(null);
  const [stmtFile, setStmtFile] = useState<File | null>(null);
  const [ledgerFile, setLedgerFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lastImport, setLastImport] = useState<string | null>(null);
  const [result, setResult] = useState<ReconcileResult | null>(null);
  const [type, setType] = useState<string>('BANK_ONLY');
  const [rows, setRows] = useState<MatchRow[]>([]);

  const loadStatus = useCallback(async () => {
    const res = await fetch(`/api/engagements/${engagementId}/bank/status`);
    if (res.ok) setCounts(await res.json());
  }, [engagementId]);

  const loadItems = useCallback(async (t: string) => {
    const res = await fetch(`/api/engagements/${engagementId}/bank/items?type=${t}`);
    if (res.ok) setRows(await res.json());
  }, [engagementId]);

  useEffect(() => { void loadStatus(); }, [loadStatus]);

  async function upload(kind: 'statement' | 'ledger', file: File | null) {
    if (!file) return;
    setBusy(true);
    setError(null);
    try {
      const form = new FormData();
      form.append('file', file);
      const res = await fetch(`/api/engagements/${engagementId}/bank/${kind}`, { method: 'POST', body: form });
      const body = await res.json();
      if (!res.ok) throw new Error(body.error ?? `Import failed (${res.status})`);
      const o = body as ImportOutcome;
      setLastImport(`${kind === 'statement' ? 'Bank statement' : 'Bank ledger'}: ${o.added} added, ${o.skipped} already present`);
      await loadStatus();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function reconcile() {
    setBusy(true);
    setError(null);
    try {
      const res = await fetch(`/api/engagements/${engagementId}/bank/reconcile`, { method: 'POST' });
      const body = await res.json();
      if (!res.ok) throw new Error(body.error ?? `Reconcile failed (${res.status})`);
      setResult(body as ReconcileResult);
      await loadItems(type);
      onReconciled();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card">
      <h2>7 · Bank reconciliation — statement ↔ books</h2>
      <p className="sub">
        Loaded: {counts ? `${counts.statementLines.toLocaleString('en-IN')} statement line(s) · ${counts.ledgerLines.toLocaleString('en-IN')} book entry(ies)` : '…'}
      </p>
      <div className="form-grid">
        <label>Bank statement (CSV)
          <input type="file" accept=".csv" onChange={(e) => setStmtFile(e.target.files?.[0] ?? null)} />
        </label>
        <label>Bank ledger from books (CSV)
          <input type="file" accept=".csv" onChange={(e) => setLedgerFile(e.target.files?.[0] ?? null)} />
        </label>
      </div>
      <div className="btn-row">
        <button onClick={() => upload('statement', stmtFile)} disabled={busy || !stmtFile}>Import statement</button>
        <button onClick={() => upload('ledger', ledgerFile)} disabled={busy || !ledgerFile}>Import ledger</button>
        <button onClick={reconcile} disabled={busy || !counts || counts.statementLines === 0 || counts.ledgerLines === 0}>
          {busy ? 'Working…' : 'Reconcile'}
        </button>
      </div>
      {lastImport && <p className="sub">{lastImport}</p>}
      {error && <p className="error">{error}</p>}

      {result && (
        <>
          <div className={result.unexplainedPaise === 0 ? 'banner ok' : 'banner warn'}>
            {result.unexplainedPaise === 0
              ? 'Closing difference fully explained by the identified reconciling items.'
              : `Unexplained difference of ₹ ${inr(Math.abs(result.unexplainedPaise))} remains — sign-off blocked until resolved (BKR-006).`}
          </div>
          <table>
            <tbody>
              <tr><th>Exact matches</th><td className="num">{result.exact.toLocaleString('en-IN')}</td></tr>
              <tr><th>Date-tolerance matches</th><td className="num">{result.tolerance.toLocaleString('en-IN')}</td></tr>
              <tr><th>Grouped (one-to-many)</th><td className="num">{result.grouped}</td></tr>
              <tr><th>Bank-only items</th><td className="num">{result.bank_only}</td></tr>
              <tr><th>Books-only items</th><td className="num">{result.books_only}</td></tr>
              <tr><th>Exceptions raised</th><td className="num">{result.exceptionsCreated} new, {result.skippedExisting} already raised</td></tr>
            </tbody>
          </table>

          <label>Show items
            <select value={type} onChange={(e) => { setType(e.target.value); void loadItems(e.target.value); }}>
              {TYPES.map((t) => <option key={t} value={t}>{t}</option>)}
            </select>
          </label>
          <table>
            <thead><tr><th>Date</th><th>Reference</th><th>Description</th><th>Amount</th><th>Dir</th><th>Book vouchers</th></tr></thead>
            <tbody>
              {rows.map((m, k) => (
                <tr key={k}>
                  <td>{m.date}</td>
                  <td className="mono">{m.reference}</td>
                  <td>{m.description}</td>
                  <td className="num">₹ {inr(m.amountPaise)}</td>
                  <td>{m.outflow ? 'out' : 'in'}</td>
                  <td>{m.voucherIds || '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </section>
  );
}
