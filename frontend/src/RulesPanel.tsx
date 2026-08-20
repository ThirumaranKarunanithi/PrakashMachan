import { useCallback, useEffect, useState } from 'react';
import { inr } from './types';

interface RuleRun {
  id: string;
  packVersion: string;
  executedAt: string;
  populationVouchers: number;
  findings: number;
  exceptionsCreated: number;
  skippedExisting: number;
}

interface ExceptionCase {
  id: string;
  caseId: string | null;
  ruleId: string;
  ruleName: string;
  severity: 'HIGH' | 'MEDIUM' | 'LOW';
  exposurePaise: number;
  reason: string;
  voucherIds: string;
  sourceRefs: string;
  status: string;
  decisionNote: string | null;
  decidedBy: string | null;
  decidedAt: string | null;
}

interface InvestigationCase {
  id: string;
  caseNo: number;
  title: string;
  severity: 'HIGH' | 'MEDIUM' | 'LOW';
  priorityScore: number;
  effectivePriority: number;
  overriddenPriority: number | null;
  overrideReason: string | null;
  overriddenBy: string | null;
  exposurePaise: number;
  voucherIds: string;
  exceptionCount: number;
  openCount: number;
  exceptions: ExceptionCase[];
}

const STATUSES = [
  'NEW', 'UNDER_REVIEW', 'INFO_REQUIRED',
  'EXPLAINED', 'CONFIRMED', 'NOT_APPLICABLE', 'ESCALATED', 'CLOSED',
] as const;

const DECISION_STATES = new Set(['EXPLAINED', 'CONFIRMED', 'NOT_APPLICABLE', 'ESCALATED', 'CLOSED']);

interface Slice {
  key: string;
  count: number;
  highCount: number;
  exposurePaise: number;
}

interface Explorer {
  byRule: Slice[];
  byUser: Slice[];
  byMonth: Slice[];
  byAccount: Slice[];
}

function ExplorerTable({ title, slices }: { title: string; slices: Slice[] }) {
  if (slices.length === 0) return null;
  const max = Math.max(...slices.map((s) => s.exposurePaise), 1);
  return (
    <div style={{ flex: '1 1 220px', minWidth: 220 }}>
      <h3>{title}</h3>
      <table>
        <tbody>
          {slices.slice(0, 6).map((s) => (
            <tr key={s.key}>
              <td>{s.key}<div className="bar obs" style={{ width: `${(s.exposurePaise / max) * 100}%` }} /></td>
              <td className="num">{s.count}{s.highCount > 0 ? ` (${s.highCount}H)` : ''}<br />
                <span className="sub">₹ {inr(s.exposurePaise)}</span></td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

export default function RulesPanel({ engagementId }: { engagementId: string }) {
  const [privilegedUsers, setPrivilegedUsers] = useState('ADMIN-1, MGR-1');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lastRun, setLastRun] = useState<RuleRun | null>(null);
  const [cases, setCases] = useState<InvestigationCase[]>([]);
  const [showResolved, setShowResolved] = useState(true);
  const [explorer, setExplorer] = useState<Explorer | null>(null);

  const loadCases = useCallback(async () => {
    const res = await fetch(`/api/engagements/${engagementId}/cases`);
    if (res.ok) setCases(await res.json());
    const ex = await fetch(`/api/engagements/${engagementId}/risk-explorer`);
    if (ex.ok) setExplorer(await ex.json());
  }, [engagementId]);

  useEffect(() => { void loadCases(); }, [loadCases]);

  async function runRules() {
    setBusy(true);
    setError(null);
    try {
      const res = await fetch(`/api/engagements/${engagementId}/rule-runs`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          privilegedUsers: privilegedUsers.split(',').map((s) => s.trim()).filter(Boolean),
        }),
      });
      const body = await res.json();
      if (!res.ok) throw new Error(body.error ?? `Run failed (${res.status})`);
      setLastRun(body.run as RuleRun);
      await loadCases();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  const visible = cases.filter((c) => showResolved || c.openCount > 0);

  return (
    <section className="card">
      <h2>3 · Investigation cases</h2>
      <div className="form-grid">
        <label>Privileged users (comma-separated)
          <input value={privilegedUsers} onChange={(e) => setPrivilegedUsers(e.target.value)} />
        </label>
        <label>View
          <select value={showResolved ? 'all' : 'open'} onChange={(e) => setShowResolved(e.target.value === 'all')}>
            <option value="all">All cases</option>
            <option value="open">Open cases only</option>
          </select>
        </label>
      </div>
      <button onClick={runRules} disabled={busy}>
        {busy ? 'Running…' : 'Run rule pack'}
      </button>
      {error && <p className="error">{error}</p>}
      {lastRun && (
        <p className="sub">
          Pack {lastRun.packVersion} · {lastRun.populationVouchers.toLocaleString('en-IN')} vouchers ·{' '}
          {lastRun.findings} findings · {lastRun.exceptionsCreated} new exceptions ·{' '}
          {lastRun.skippedExisting} already raised · {cases.length} consolidated case(s)
        </p>
      )}

      <details>
        <summary>Samples (JET-008 / BEN-013)</summary>
        <SamplesBlock engagementId={engagementId} />
      </details>
      <details>
        <summary>Methodology settings (RSK-003 / AWP-001)</summary>
        <MethodologyBlock />
      </details>

      {explorer && (explorer.byRule.length > 0) && (
        <details>
          <summary>Risk explorer — what drives the open risk (BRD §18.1)</summary>
          <div style={{ display: 'flex', gap: 18, flexWrap: 'wrap' }}>
            <ExplorerTable title="By rule" slices={explorer.byRule} />
            <ExplorerTable title="By user" slices={explorer.byUser} />
            <ExplorerTable title="By month" slices={explorer.byMonth} />
            <ExplorerTable title="By account" slices={explorer.byAccount} />
          </div>
        </details>
      )}

      {visible.map((c) => <CaseView key={c.id} c={c} onSaved={loadCases} />)}
      {visible.length === 0 && <p className="sub">No {showResolved ? '' : 'open '}cases. Run the rule pack after importing data.</p>}
    </section>
  );
}

function CaseView({ c, onSaved }: { c: InvestigationCase; onSaved: () => Promise<void> }) {
  const [open, setOpen] = useState(c.severity === 'HIGH');
  const [showOverride, setShowOverride] = useState(false);
  const [timeline, setTimeline] = useState<{ when: string; source: string; description: string }[] | null>(null);

  async function loadTimeline() {
    if (timeline) { setTimeline(null); return; }
    const res = await fetch(`/api/cases/${c.id}/timeline`);
    if (res.ok) setTimeline(await res.json());
  }
  const [priority, setPriority] = useState(String(c.effectivePriority));
  const [reason, setReason] = useState(c.overrideReason ?? '');
  const [error, setError] = useState<string | null>(null);

  async function saveOverride(clear: boolean) {
    setError(null);
    try {
      const res = await fetch(`/api/cases/${c.id}/priority`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(clear ? { priority: null } : { priority: Number(priority), reason }),
      });
      const body = await res.json();
      if (!res.ok) throw new Error(body.error ?? `Failed (${res.status})`);
      setShowOverride(false);
      await onSaved();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }

  return (
    <div className="case">
      <button className="case-head" onClick={() => setOpen(!open)} aria-expanded={open}>
        <span className="case-no">CASE-{String(c.caseNo).padStart(3, '0')}</span>
        <span className={`sev sev-${c.severity.toLowerCase()}`}>{c.severity}</span>
        <span className="case-title">{c.title}</span>
        <span className="case-meta">
          priority {c.effectivePriority}{c.overriddenPriority != null ? ` (score ${c.priorityScore}, overridden by ${c.overriddenBy})` : ''} ·
          ₹ {inr(c.exposurePaise)} · {c.openCount}/{c.exceptionCount} open {open ? '▾' : '▸'}
        </span>
      </button>
      {open && (
        <div style={{ padding: '4px 14px 0' }}>
          <a href="#" className="sub" onClick={(e) => { e.preventDefault(); setShowOverride(!showOverride); }}>
            {showOverride ? 'Hide priority override' : 'Override review priority (RSK-004)'}
          </a>
          {' · '}
          <a href="#" className="sub" onClick={(e) => { e.preventDefault(); void loadTimeline(); }}>
            {timeline ? 'Hide timeline' : 'Show timeline (AC-08)'}
          </a>
          {timeline && (
            <table>
              <tbody>
                {timeline.map((t, i) => (
                  <tr key={i}>
                    <td style={{ whiteSpace: 'nowrap' }}>{t.when.replace('T', ' ')}</td>
                    <td><span className="sev sev-low">{t.source}</span></td>
                    <td>{t.description}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
          {showOverride && (
            <div className="btn-row" style={{ marginTop: 6 }}>
              <input type="number" min={0} max={100} step={1} style={{ width: 80 }} value={priority} onChange={(e) => setPriority(e.target.value)} />
              <input placeholder="Recorded reason (required)" value={reason} onChange={(e) => setReason(e.target.value)} style={{ flex: 1, minWidth: 200 }} />
              <button onClick={() => void saveOverride(false)} disabled={!reason.trim()}>Save</button>
              {c.overriddenPriority != null && <button onClick={() => void saveOverride(true)}>Clear override</button>}
            </div>
          )}
          {error && <p className="error">{error}</p>}
        </div>
      )}
      {open && (
        <table>
          <thead>
            <tr><th>Rule</th><th>Severity</th><th>Exposure</th><th>Why it was flagged</th><th>Status &amp; decision</th></tr>
          </thead>
          <tbody>
            {c.exceptions.map((e) => <ExceptionRow key={e.id} c={e} onSaved={onSaved} />)}
          </tbody>
        </table>
      )}
    </div>
  );
}

function SamplesBlock({ engagementId }: { engagementId: string }) {
  const [samples, setSamples] = useState<{ method: string; sampleSize: number; seed: number | null; voucherIds: string; selectedBy: string }[]>([]);
  const [method, setMethod] = useState('RISK_RANKED');
  const [size, setSize] = useState('10');
  const [seed, setSeed] = useState('');
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    const res = await fetch(`/api/engagements/${engagementId}/samples`);
    if (res.ok) setSamples(await res.json());
  }, [engagementId]);
  useEffect(() => { void load(); }, [load]);

  async function select() {
    setError(null);
    if (!Number.isInteger(Number(size)) || Number(size) < 1 || Number(size) > 500) {
      setError('Sample size must be a whole number between 1 and 500.');
      return;
    }
    try {
      const res = await fetch(`/api/engagements/${engagementId}/samples`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ method, size: Number(size), seed: seed ? Number(seed) : null }),
      });
      const body = await res.json();
      if (!res.ok) throw new Error(body.error ?? `Failed (${res.status})`);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }

  return (
    <div style={{ padding: '8px 0' }}>
      <div className="btn-row">
        <select value={method} onChange={(e) => setMethod(e.target.value)}>
          <option value="RISK_RANKED">Risk-ranked</option>
          <option value="RANDOM">Random (seeded)</option>
        </select>
        <input type="number" min={1} max={500} step={1} style={{ width: 70 }} value={size} onChange={(e) => setSize(e.target.value)} />
        {method === 'RANDOM' && <input placeholder="Seed (optional)" style={{ width: 120 }} value={seed} onChange={(e) => setSeed(e.target.value)} />}
        <button onClick={select}>Select sample</button>
      </div>
      {error && <p className="error">{error}</p>}
      {samples.map((s, i) => (
        <p key={i} className="sub">{s.method} · {s.sampleSize} voucher(s){s.seed != null ? ` · seed ${s.seed}` : ''} · by {s.selectedBy}: <span className="mono">{s.voucherIds}</span></p>
      ))}
    </div>
  );
}

function MethodologyBlock() {
  const [high, setHigh] = useState('10');
  const [medium, setMedium] = useState('5');
  const [low, setLow] = useState('2');
  const [headerTitle, setHeaderTitle] = useState('Engagement Workpaper');
  const [footerNote, setFooterNote] = useState('');
  const [updatedBy, setUpdatedBy] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetch('/api/methodology/risk-weights').then((r) => r.json()).then((w) => {
      setHigh(String(w.highWeight)); setMedium(String(w.mediumWeight)); setLow(String(w.lowWeight));
    }).catch(() => {});
    fetch('/api/methodology/workpaper-template').then((r) => r.json()).then((t) => {
      setHeaderTitle(t.headerTitle); setFooterNote(t.footerNote ?? '');
    }).catch(() => {});
  }, []);

  async function save() {
    setError(null); setMessage(null);
    try {
      const r1 = await fetch('/api/methodology/risk-weights', {
        method: 'PUT', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ highWeight: Number(high), mediumWeight: Number(medium), lowWeight: Number(low), updatedBy }),
      });
      if (!r1.ok) throw new Error((await r1.json()).error);
      const r2 = await fetch('/api/methodology/workpaper-template', {
        method: 'PUT', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ headerTitle, footerNote: footerNote || null, updatedBy }),
      });
      if (!r2.ok) throw new Error((await r2.json()).error);
      setMessage('Saved as a new version. Scores update on the next rule run; workpapers on the next generation.');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }

  return (
    <div style={{ padding: '8px 0' }}>
      <div className="btn-row">
        <label>HIGH <input type="number" min={0} step={1} style={{ width: 60 }} value={high} onChange={(e) => setHigh(e.target.value)} /></label>
        <label>MEDIUM <input type="number" min={0} step={1} style={{ width: 60 }} value={medium} onChange={(e) => setMedium(e.target.value)} /></label>
        <label>LOW <input type="number" min={0} step={1} style={{ width: 60 }} value={low} onChange={(e) => setLow(e.target.value)} /></label>
      </div>
      <div className="btn-row">
        <input placeholder="Workpaper header title" style={{ flex: 1, minWidth: 220 }} value={headerTitle} onChange={(e) => setHeaderTitle(e.target.value)} />
        <input placeholder="Footer note (optional)" style={{ flex: 1, minWidth: 220 }} value={footerNote} onChange={(e) => setFooterNote(e.target.value)} />
        <input placeholder="Updated by" value={updatedBy} onChange={(e) => setUpdatedBy(e.target.value)} />
        <button onClick={save} disabled={!updatedBy.trim()}>Save methodology</button>
      </div>
      {message && <p className="ok-text">{message}</p>}
      {error && <p className="error">{error}</p>}
      <p className="sub">Changes are versioned append-only — past configurations stay reviewable (RSK-003).</p>
    </div>
  );
}

function ExceptionRow({ c, onSaved }: { c: ExceptionCase; onSaved: () => Promise<void> }) {
  const [status, setStatus] = useState(c.status);
  const [note, setNote] = useState(c.decisionNote ?? '');
  const [saving, setSaving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const dirty = status !== c.status || note !== (c.decisionNote ?? '');
  const needsNote = DECISION_STATES.has(status) && !note.trim();

  async function save() {
    setSaving(true);
    setError(null);
    try {
      const res = await fetch(`/api/exceptions/${c.id}`, {
        method: 'PATCH',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ status, note: note.trim() || null }),
      });
      const body = await res.json();
      if (!res.ok) throw new Error(body.error ?? `Save failed (${res.status})`);
      await onSaved();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setSaving(false);
    }
  }

  return (
    <tr>
      <td><strong>{c.ruleId}</strong><br /><span className="sub">{c.ruleName}</span></td>
      <td><span className={`sev sev-${c.severity.toLowerCase()}`}>{c.severity}</span></td>
      <td className="num">₹ {inr(c.exposurePaise)}</td>
      <td>
        {c.reason}
        <div className="sub mono">{c.sourceRefs}</div>
        {c.decidedBy && <div className="sub">Decided by {c.decidedBy} · {c.decidedAt && new Date(c.decidedAt).toLocaleString('en-IN')}</div>}
        <DecisionHistory exceptionId={c.id} />
      </td>
      <td className="decision">
        <select value={status} onChange={(e) => setStatus(e.target.value)}>
          {STATUSES.map((s) => <option key={s} value={s}>{s}</option>)}
        </select>
        <textarea
          placeholder={DECISION_STATES.has(status) ? 'Documented reason (required)' : 'Note (optional)'}
          value={note}
          onChange={(e) => setNote(e.target.value)}
          rows={2}
        />
        <button onClick={save} disabled={!dirty || saving || needsNote}>
          {saving ? 'Saving…' : 'Save decision'}
        </button>
        {error && <p className="error">{error}</p>}
      </td>
    </tr>
  );
}

function DecisionHistory({ exceptionId }: { exceptionId: string }) {
  const [entries, setEntries] = useState<
    { fromStatus: string | null; toStatus: string; note: string | null; decidedBy: string; decidedAt: string }[] | null
  >(null);

  async function load() {
    if (entries !== null) { setEntries(null); return; } // toggle closed
    const res = await fetch(`/api/exceptions/${exceptionId}/history`);
    setEntries(res.ok ? await res.json() : []);
  }

  return (
    <div className="sub">
      <a href="#" onClick={(e) => { e.preventDefault(); void load(); }}>
        {entries === null ? 'History ▸' : 'History ▾'}
      </a>
      {entries !== null && (entries.length === 0
        ? <div>No status changes yet.</div>
        : entries.map((h, i) => (
            <div key={i}>
              {new Date(h.decidedAt).toLocaleString('en-IN')} · {h.fromStatus ?? 'NEW'} → {h.toStatus} · {h.decidedBy}
              {h.note && <> — {h.note}</>}
            </div>
          )))}
    </div>
  );
}
