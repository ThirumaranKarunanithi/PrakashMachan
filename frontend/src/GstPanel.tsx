import { api } from './api';
import { useCallback, useEffect, useState } from 'react';
import { inr } from './types';

interface ImportOutcome {
  totalRows: number;
  added: number;
  skipped: number;
  problems: string[];
}

interface ReconcileResult {
  matched: number;
  value_mismatch: number;
  books_only: number;
  g2b_only: number;
  itcExposurePaise: number;
  exceptionsCreated: number;
  skippedExisting: number;
}

interface MatchRow {
  category: string;
  confidence: number | null;
  matchedFields: string | null;
  gstin: string;
  invoiceNo: string;
  partyName: string;
  booksTaxablePaise: number | null;
  g2bTaxablePaise: number | null;
  taxDiffPaise: number;
  voucherId: string | null;
}

const CATEGORIES = ['SUGGESTED', 'VALUE_MISMATCH', 'BOOKS_ONLY', 'G2B_ONLY', 'MATCHED'] as const;

function ManualLinkForm({ engagementId, onLinked }: { engagementId: string; onLinked: () => void }) {
  const [side, setSide] = useState('PURCHASE');
  const [booksGstin, setBooksGstin] = useState('');
  const [booksInvoiceNo, setBooksInvoiceNo] = useState('');
  const [portalGstin, setPortalGstin] = useState('');
  const [portalInvoiceNo, setPortalInvoiceNo] = useState('');
  const [reason, setReason] = useState('');
  const [decidedBy, setDecidedBy] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      const res = await fetch(`/api/engagements/${engagementId}/gst/manual-links`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ side, booksGstin, booksInvoiceNo, portalGstin, portalInvoiceNo, reason, decidedBy }),
      });
      const body = await res.json();
      if (!res.ok) throw new Error(body.error ?? `Failed (${res.status})`);
      onLinked();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <div style={{ padding: '8px 0' }}>
      <div className="form-grid">
        <label>Side
          <select value={side} onChange={(e) => setSide(e.target.value)}>
            <option value="PURCHASE">Purchases ↔ GSTR-2B</option>
            <option value="SALES">Sales ↔ GSTR-1</option>
          </select>
        </label>
        <label>Books GSTIN<input value={booksGstin} onChange={(e) => setBooksGstin(e.target.value)} /></label>
        <label>Books invoice no<input value={booksInvoiceNo} onChange={(e) => setBooksInvoiceNo(e.target.value)} /></label>
        <label>Portal GSTIN<input value={portalGstin} onChange={(e) => setPortalGstin(e.target.value)} /></label>
        <label>Portal invoice no<input value={portalInvoiceNo} onChange={(e) => setPortalInvoiceNo(e.target.value)} /></label>
        <label>Reason (required)<input value={reason} onChange={(e) => setReason(e.target.value)} placeholder="e.g. supplier filed with a typo'd number" /></label>
        <label>Decided by<input value={decidedBy} onChange={(e) => setDecidedBy(e.target.value)} /></label>
      </div>
      <button onClick={submit} disabled={busy || !booksGstin || !booksInvoiceNo || !portalGstin || !portalInvoiceNo || !reason.trim() || !decidedBy.trim()}>
        Record manual link
      </button>
      {error && <p className="error">{error}</p>}
    </div>
  );
}

interface SalesReconcileResult {
  matched: number;
  value_mismatch: number;
  books_only: number;
  g2b_only: number;
  taxExposurePaise: number;
  exceptionsCreated: number;
  skippedExisting: number;
}

interface PeriodComparison {
  period: string;
  gstr1TaxPaise: number;
  declaredTaxPaise: number | null;
  taxDiffPaise: number;
}

interface Gstr3bResult {
  periods: PeriodComparison[];
  differences: number;
  totalTaxDiffPaise: number;
  exceptionsCreated: number;
}

export default function GstPanel({ engagementId, onReconciled }: { engagementId: string; onReconciled: () => void }) {
  const [counts, setCounts] = useState<{
    purchaseInvoices: number; gstr2bInvoices: number;
    salesInvoices: number; gstr1Invoices: number; gstr3bPeriods: number;
  } | null>(null);
  const [salesFile, setSalesFile] = useState<File | null>(null);
  const [g1File, setG1File] = useState<File | null>(null);
  const [g3bFile, setG3bFile] = useState<File | null>(null);
  const [salesResult, setSalesResult] = useState<SalesReconcileResult | null>(null);
  const [g3bResult, setG3bResult] = useState<Gstr3bResult | null>(null);
  const [purchaseFile, setPurchaseFile] = useState<File | null>(null);
  const [g2bFile, setG2bFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lastImport, setLastImport] = useState<string | null>(null);
  const [result, setResult] = useState<ReconcileResult | null>(null);
  const [category, setCategory] = useState<string>('VALUE_MISMATCH');
  const [rows, setRows] = useState<MatchRow[]>([]);

  const loadStatus = useCallback(async () => {
    const res = await fetch(`/api/engagements/${engagementId}/gst/status`);
    if (res.ok) setCounts(await res.json());
  }, [engagementId]);

  const loadMatches = useCallback(async (cat: string) => {
    const res = await fetch(`/api/engagements/${engagementId}/gst/matches?category=${cat}`);
    if (res.ok) setRows(await res.json());
  }, [engagementId]);

  useEffect(() => { void loadStatus(); }, [loadStatus]);

  async function reconcileSales() {
    setBusy(true);
    setError(null);
    try {
      const res1 = await fetch(`/api/engagements/${engagementId}/gst/reconcile-sales`, { method: 'POST' });
      const b1 = await res1.json();
      if (!res1.ok) throw new Error(b1.error ?? `Reconcile failed (${res1.status})`);
      setSalesResult(b1 as SalesReconcileResult);
      const res2 = await fetch(`/api/engagements/${engagementId}/gst/reconcile-3b`, { method: 'POST' });
      const b2 = await res2.json();
      if (!res2.ok) throw new Error(b2.error ?? `3B reconcile failed (${res2.status})`);
      setG3bResult(b2 as Gstr3bResult);
      onReconciled();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function upload(kind: 'purchases' | 'gstr2b' | 'sales' | 'gstr1' | 'gstr3b-summary', file: File | null) {
    if (!file) return;
    setBusy(true);
    setError(null);
    try {
      const form = new FormData();
      form.append('file', file);
      const res = await fetch(`/api/engagements/${engagementId}/gst/${kind}`, { method: 'POST', body: form });
      const body = await res.json();
      if (!res.ok) throw new Error(body.error ?? `Import failed (${res.status})`);
      const o = body as ImportOutcome;
      setLastImport(`${kind}: ${o.added} added, ${o.skipped} already present${o.problems.length ? `, ${o.problems.length} problem(s)` : ''}`);
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
      const res = await fetch(`/api/engagements/${engagementId}/gst/reconcile`, { method: 'POST' });
      const body = await res.json();
      if (!res.ok) throw new Error(body.error ?? `Reconcile failed (${res.status})`);
      setResult(body as ReconcileResult);
      await loadMatches(category);
      onReconciled();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card">
      <h2>5 · GST reconciliation — books ↔ returns</h2>
      <p className="sub">
        Loaded: {counts ? `${counts.purchaseInvoices.toLocaleString('en-IN')} purchases · ${counts.gstr2bInvoices.toLocaleString('en-IN')} GSTR-2B · ${counts.salesInvoices.toLocaleString('en-IN')} sales · ${counts.gstr1Invoices.toLocaleString('en-IN')} GSTR-1 · ${counts.gstr3bPeriods} GSTR-3B period(s)` : '…'}
      </p>
      <h3>Inward: purchase register ↔ GSTR-2B (GS-01)</h3>
      <div className="form-grid">
        <label>Purchase register (CSV)
          <input type="file" accept=".csv" onChange={(e) => setPurchaseFile(e.target.files?.[0] ?? null)} />
        </label>
        <label>GSTR-2B (CSV)
          <input type="file" accept=".csv" onChange={(e) => setG2bFile(e.target.files?.[0] ?? null)} />
        </label>
      </div>
      <div className="btn-row">
        <button onClick={() => upload('purchases', purchaseFile)} disabled={busy || !purchaseFile}>Import purchases</button>
        <button onClick={() => upload('gstr2b', g2bFile)} disabled={busy || !g2bFile}>Import GSTR-2B</button>
        <button onClick={reconcile} disabled={busy || !counts || counts.purchaseInvoices === 0 || counts.gstr2bInvoices === 0}>
          {busy ? 'Working…' : 'Reconcile'}
        </button>
      </div>
      {lastImport && <p className="sub">{lastImport}</p>}
      {error && <p className="error">{error}</p>}

      {result && (
        <>
          <table>
            <tbody>
              <tr><th>Matched</th><td className="num">{result.matched.toLocaleString('en-IN')}</td></tr>
              <tr><th>Value mismatch</th><td className="num">{result.value_mismatch}</td></tr>
              <tr><th>Books only (not in 2B)</th><td className="num">{result.books_only}</td></tr>
              <tr><th>2B only (not in books)</th><td className="num">{result.g2b_only}</td></tr>
              <tr><th>Potential ITC / tax at stake*</th><td className="num">₹ {inr(result.itcExposurePaise)}</td></tr>
              <tr><th>Exceptions raised</th><td className="num">{result.exceptionsCreated} new, {result.skippedExisting} already raised</td></tr>
            </tbody>
          </table>
          <p className="sub">* Estimated amount at stake — legal ITC eligibility remains a professional decision (BRD GST-004).</p>

          <label>Show category
            <select value={category} onChange={(e) => { setCategory(e.target.value); void loadMatches(e.target.value); }}>
              {CATEGORIES.map((c) => <option key={c} value={c}>{c}</option>)}
            </select>
          </label>
          <table>
            <thead><tr><th>Invoice</th><th>Party</th><th>GSTIN</th><th>Books taxable</th><th>2B taxable</th><th>Tax diff</th><th>Voucher</th></tr></thead>
            <tbody>
              {rows.map((m, k) => (
                <tr key={k}>
                  <td>{m.invoiceNo}</td>
                  <td>{m.partyName}</td>
                  <td className="mono">{m.gstin}</td>
                  <td className="num">{m.booksTaxablePaise == null ? '—' : inr(m.booksTaxablePaise)}</td>
                  <td className="num">{m.g2bTaxablePaise == null ? '—' : inr(m.g2bTaxablePaise)}</td>
                  <td className="num">{inr(m.taxDiffPaise)}</td>
                  <td>{m.voucherId || '—'}{m.confidence != null && <div className="sub">confidence {(m.confidence * 100).toFixed(0)}% · {m.matchedFields}</div>}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}

      <details>
        <summary>Manual invoice link (GST-007) — pair a books invoice with a portal invoice</summary>
        <ManualLinkForm engagementId={engagementId} onLinked={() => setLastImport('Manual link recorded — re-run the reconciliation to apply it.')} />
      </details>
      <p>
        <a href={api(`/api/engagements/${engagementId}/gst/correction-schedule.csv`)} download>
          Download pre-filing correction schedule (CSV, GST-008)
        </a>
        <span className="sub"> — every unresolved difference with financial effect and suggested action; owner and due date are the professional's to assign.</span>
      </p>

      <h3>Outward: sales register ↔ GSTR-1 ↔ GSTR-3B (GS-02 / GS-03)</h3>
      <div className="form-grid">
        <label>Sales register (CSV)
          <input type="file" accept=".csv" onChange={(e) => setSalesFile(e.target.files?.[0] ?? null)} />
        </label>
        <label>GSTR-1 (CSV)
          <input type="file" accept=".csv" onChange={(e) => setG1File(e.target.files?.[0] ?? null)} />
        </label>
        <label>GSTR-3B summary (CSV)
          <input type="file" accept=".csv" onChange={(e) => setG3bFile(e.target.files?.[0] ?? null)} />
        </label>
      </div>
      <div className="btn-row">
        <button onClick={() => upload('sales', salesFile)} disabled={busy || !salesFile}>Import sales</button>
        <button onClick={() => upload('gstr1', g1File)} disabled={busy || !g1File}>Import GSTR-1</button>
        <button onClick={() => upload('gstr3b-summary', g3bFile)} disabled={busy || !g3bFile}>Import GSTR-3B</button>
        <button onClick={reconcileSales} disabled={busy || !counts || counts.salesInvoices === 0 || counts.gstr1Invoices === 0}>
          {busy ? 'Working…' : 'Reconcile outward'}
        </button>
      </div>

      {salesResult && (
        <table>
          <tbody>
            <tr><th>Matched</th><td className="num">{salesResult.matched.toLocaleString('en-IN')}</td></tr>
            <tr><th>Value mismatch</th><td className="num">{salesResult.value_mismatch}</td></tr>
            <tr><th>In books, not in GSTR-1</th><td className="num">{salesResult.books_only}</td></tr>
            <tr><th>In GSTR-1, not in books</th><td className="num">{salesResult.g2b_only}</td></tr>
            <tr><th>Potential tax at stake*</th><td className="num">₹ {inr(salesResult.taxExposurePaise)}</td></tr>
            <tr><th>Exceptions raised</th><td className="num">{salesResult.exceptionsCreated} new, {salesResult.skippedExisting} already raised</td></tr>
          </tbody>
        </table>
      )}

      {g3bResult && (
        <>
          <div className={g3bResult.differences === 0 ? 'banner ok' : 'banner warn'}>
            {g3bResult.differences === 0
              ? 'GSTR-1 detail agrees with the GSTR-3B declared summary for every period.'
              : `${g3bResult.differences} period(s) differ — total tax difference ₹ ${inr(g3bResult.totalTaxDiffPaise)}. Correction and filing remain professional decisions.`}
          </div>
          <table>
            <thead><tr><th>Period</th><th>GSTR-1 tax</th><th>GSTR-3B declared</th><th>Difference</th></tr></thead>
            <tbody>
              {g3bResult.periods.map((p) => (
                <tr key={p.period} className={p.taxDiffPaise > 100 ? 'selected' : ''}>
                  <td>{p.period}</td>
                  <td className="num">{inr(p.gstr1TaxPaise)}</td>
                  <td className="num">{p.declaredTaxPaise == null ? '—' : inr(p.declaredTaxPaise)}</td>
                  <td className="num">{p.taxDiffPaise > 100 ? inr(p.taxDiffPaise) : ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </>
      )}
    </section>
  );
}
