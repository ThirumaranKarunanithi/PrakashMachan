import { useCallback, useEffect, useState } from 'react';

interface ImportOutcome {
  totalRows: number;
  added: number;
  skipped: number;
  problems: string[];
}

interface AtrReport {
  events: number;
  firstEvent: string | null;
  lastEvent: string | null;
  gaps: { from: string; to: string; days: number }[];
  monthsWithoutEvents: string[];
  eventsByObject: Record<string, number>;
  disablementEvents: number;
  exceptionsCreated: number;
  skippedExisting: number;
}

export default function VendorPanel({ engagementId }: { engagementId: string }) {
  const [counts, setCounts] = useState<{ vendors: number; auditTrailEvents: number } | null>(null);
  const [vendorFile, setVendorFile] = useState<File | null>(null);
  const [auditFile, setAuditFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lastImport, setLastImport] = useState<string | null>(null);
  const [atr, setAtr] = useState<AtrReport | null>(null);

  async function analyze() {
    setBusy(true);
    setError(null);
    try {
      const res = await fetch(`/api/engagements/${engagementId}/vendor-data/analyze`, { method: 'POST' });
      const body = await res.json();
      if (!res.ok) throw new Error(body.error ?? `Analysis failed (${res.status})`);
      setAtr(body as AtrReport);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  const loadStatus = useCallback(async () => {
    const res = await fetch(`/api/engagements/${engagementId}/vendor-data/status`);
    if (res.ok) setCounts(await res.json());
  }, [engagementId]);

  useEffect(() => { void loadStatus(); }, [loadStatus]);

  async function upload(kind: 'vendors' | 'audit-trail', file: File | null) {
    if (!file) return;
    setBusy(true);
    setError(null);
    try {
      const form = new FormData();
      form.append('file', file);
      const res = await fetch(`/api/engagements/${engagementId}/vendor-data/${kind}`, { method: 'POST', body: form });
      const body = await res.json();
      if (!res.ok) throw new Error(body.error ?? `Import failed (${res.status})`);
      const o = body as ImportOutcome;
      setLastImport(`${kind === 'vendors' ? 'Vendor master' : 'Audit trail'}: ${o.added} added, ${o.skipped} already present${o.problems.length ? `, ${o.problems.length} problem(s)` : ''}`);
      await loadStatus();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card">
      <h2>6 · Vendor master &amp; audit trail</h2>
      <p className="sub">
        Loaded: {counts ? `${counts.vendors.toLocaleString('en-IN')} vendor(s) · ${counts.auditTrailEvents.toLocaleString('en-IN')} audit-trail event(s)` : '…'}
        {' '}— used by the vendor/payment rules (VP-01…06) on the next rule-pack run.
      </p>
      <div className="form-grid">
        <label>Vendor master (CSV)
          <input type="file" accept=".csv" onChange={(e) => setVendorFile(e.target.files?.[0] ?? null)} />
        </label>
        <label>Audit trail (CSV)
          <input type="file" accept=".csv" onChange={(e) => setAuditFile(e.target.files?.[0] ?? null)} />
        </label>
      </div>
      <div className="btn-row">
        <button onClick={() => upload('vendors', vendorFile)} disabled={busy || !vendorFile}>Import vendor master</button>
        <button onClick={() => upload('audit-trail', auditFile)} disabled={busy || !auditFile}>Import audit trail</button>
      </div>
      {lastImport && <p className="sub">{lastImport}</p>}
      <div className="btn-row">
        <button onClick={analyze} disabled={busy || !counts || counts.auditTrailEvents === 0}>
          Analyse audit-trail completeness (ATR-002/003)
        </button>
      </div>
      {atr && (
        <>
          <div className={atr.gaps.length === 0 && atr.disablementEvents === 0 ? 'banner ok' : 'banner warn'}>
            {atr.events.toLocaleString('en-IN')} event(s), {atr.firstEvent?.slice(0, 10)} → {atr.lastEvent?.slice(0, 10)} ·{' '}
            {atr.gaps.length} coverage gap(s) ≥ 30 days · {atr.disablementEvents} configuration event(s) ·{' '}
            {atr.exceptionsCreated} new exception(s), {atr.skippedExisting} already raised
          </div>
          {atr.gaps.length > 0 && (
            <table>
              <thead><tr><th>Gap from</th><th>To</th><th>Days</th></tr></thead>
              <tbody>
                {atr.gaps.map((g) => (
                  <tr key={g.from}><td>{g.from}</td><td>{g.to}</td><td className="num">{g.days}</td></tr>
                ))}
              </tbody>
            </table>
          )}
          {atr.monthsWithoutEvents.length > 0 && (
            <p className="sub">Months without any events: {atr.monthsWithoutEvents.join(', ')}</p>
          )}
          <p className="sub">A gap is a coverage limitation to state in the workpaper — never proof that logging was disabled (BRD §10).</p>
        </>
      )}
      {error && <p className="error">{error}</p>}
    </section>
  );
}
