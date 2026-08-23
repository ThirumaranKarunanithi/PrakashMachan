import { api, setCsrfToken } from './api';
import { useCallback, useEffect, useState } from 'react';
import type { Engagement, ImportBatch, ImportSummary, MappingProfile } from './types';
import { inr } from './types';
import RulesPanel from './RulesPanel';
import GstPanel from './GstPanel';
import VendorPanel from './VendorPanel';
import BankPanel from './BankPanel';
import WorkpaperPanel from './WorkpaperPanel';
import EvidencePanel from './EvidencePanel';
import AuthPanel from './AuthPanel';
import BenfordPanel from './BenfordPanel';
import ClientPortal from './ClientPortal';
import NotificationBell from './NotificationBell';
import type { Me } from './AuthPanel';

export default function App() {
  const [me, setMe] = useState<Me | null | undefined>(undefined); // undefined = loading
  const [engagements, setEngagements] = useState<Engagement[]>([]);
  const [selected, setSelected] = useState<Engagement | null>(null);
  const [history, setHistory] = useState<ImportBatch[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [casesVersion, setCasesVersion] = useState(0);
  const [view, setView] = useState('overview');

  useEffect(() => {
    fetch('/api/auth/me')
      .then((r) => (r.ok ? r.json() : null))
      .then(async (m) => {
        if (m) return setMe(m);
        // demo mode: sign straight into the shared demo firm; fall back to the login screen
        const demo = await fetch('/api/auth/demo', { method: 'POST' })
          .then((r) => (r.ok ? r.json() : null))
          .catch(() => null);
        setMe(demo);
      })
      .catch(() => setMe(null));
  }, []);

  async function logout() {
    await fetch('/api/auth/logout', { method: 'POST' });
    setMe(null);
    setSelected(null);
    setEngagements([]);
  }

  const refresh = useCallback(async (selectId?: string) => {
    try {
      const list: Engagement[] = await (await fetch('/api/engagements')).json();
      setEngagements(list);
      const want = selectId ?? selected?.id;
      const found = list.find((e) => e.id === want) ?? null;
      setSelected(found);
      if (found) {
        const detail = await (await fetch(`/api/engagements/${found.id}`)).json();
        setHistory(detail.imports as ImportBatch[]);
      } else {
        setHistory([]);
      }
    } catch {
      setError('Cannot reach the backend. Is it running on :8080?');
    }
  }, [selected?.id]);

  useEffect(() => {
    if (!me) { setCsrfToken(null); return; }
    fetch('/api/auth/csrf')
      .then((r) => (r.ok ? r.json() : null))
      .then((t) => setCsrfToken(t ? t.token : null))
      .catch(() => setCsrfToken(null));
    if (me.role !== 'CLIENT') void refresh();
  }, [me]); // eslint-disable-line react-hooks/exhaustive-deps

  if (me === undefined) return <main><p className="sub">Loading…</p></main>;
  if (me && me.role === 'CLIENT') {
    return <ClientPortal me={me} onLogout={() => void logout()} />;
  }
  if (me === null) {
    return (
      <main>
        <header>
          <h1>PRAMETRA</h1>
          <p className="sub">Financial Integrity &amp; Audit Intelligence Platform — evidence behind every number.</p>
        </header>
        <AuthPanel onAuthed={setMe} />
      </main>
    );
  }

  const NAV: { group: string; items: { key: string; label: string; ico: string; lockedUnless?: string[] }[] }[] = [
    { group: 'Platform', items: [
      { key: 'overview', label: 'Overview', ico: '▦' },
      { key: 'engagements', label: 'Engagements', ico: '▤' },
      { key: 'ingest', label: 'Prametra Foundation', ico: '⇪' },
    ]},
    { group: 'Analysis', items: [
      { key: 'analysis', label: 'Prametra Prism', ico: '◬' },
      { key: 'gst', label: 'Prametra GST', ico: '☰', lockedUnless: ['GST'] },
      { key: 'vendor', label: 'Prametra Vendor · Trail', ico: '⛓', lockedUnless: ['VENDOR', 'AUDIT_TRAIL'] },
      { key: 'bank', label: 'Prametra Bank', ico: '🏦', lockedUnless: ['BANK'] },
    ]},
    { group: 'Customer Subscription', items: [
      { key: 'customers', label: 'Customers', ico: '👥' },
      { key: 'billing', label: 'Pricing & Billing', ico: '₹' },
    ]},
    { group: 'Workflow', items: [
      { key: 'evidence', label: 'Prametra Evidence', ico: '✉' },
      { key: 'workpapers', label: 'Prametra Workpapers', ico: '✍' },
      { key: 'security', label: 'Account Security', ico: '🛡' },
    ]},
  ];

  const needsEngagement = !['engagements', 'customers', 'billing', 'security'].includes(view);

  return (
    <div className="shell">
      <header className="appbar">
        <span className="brand"><span className="logo">P</span>PRAMETRA
          <span style={{ fontWeight: 400, fontSize: '0.78rem', color: '#c6d2e4', marginLeft: 10 }}>Evidence behind every number.</span>
        </span>
        <span className="spacer" />
        <NotificationBell />
        <span className="whoami">{me.displayName} · <b>{me.firmName}</b> · {me.role}</span>
        <span className="whoami"><button onClick={logout}>Sign out</button></span>
      </header>

      <nav className="sidebar">
        <div className="eng-select">
          <label>Engagement
            <select value={selected?.id ?? ''} onChange={(e) => {
              const found = engagements.find((x) => x.id === e.target.value) ?? null;
              setSelected(found);
              void refresh(found?.id);
            }}>
              <option value="">— select —</option>
              {engagements.map((e) => <option key={e.id} value={e.id}>{e.clientName} · {e.fyStart.slice(0, 4)}-{e.fyEnd.slice(2, 4)}</option>)}
            </select>
          </label>
        </div>
        {NAV.map((g) => (
          <div className="nav-group" key={g.group}>
            <div className="nav-label">{g.group}</div>
            {g.items.map((it) => {
              const locked = it.lockedUnless && selected && !it.lockedUnless.some((m) => has(selected, m));
              return (
                <button key={it.key} className={'nav-item' + (view === it.key ? ' active' : '')}
                        onClick={() => setView(it.key)}>
                  <span className="ico">{it.ico}</span><span className="txt">{it.label}</span>
                  {locked ? <span className="lock">🔒</span> : null}
                </button>
              );
            })}
          </div>
        ))}
      </nav>

      <div className="content">
        {error && <p className="error">{error}</p>}
        {needsEngagement && !selected && (
          <section className="card">
            <h2>Select an engagement</h2>
            <p className="sub">Pick an engagement in the sidebar, or create one under Engagements.</p>
            <button onClick={() => setView('engagements')}>Go to Engagements</button>
          </section>
        )}

        {view === 'security' && <SecurityView me={me} onChanged={(m) => setMe(m)} />}
        {view === 'customers' && <CustomersView onOpen={(id) => {
          const found = engagements.find((x) => x.id === id) ?? null;
          setSelected(found); void refresh(id); setView('overview');
        }} />}
        {view === 'billing' && <BillingView />}
        {view === 'engagements' && (
          <EngagementPanel
            engagements={engagements}
            selected={selected}
            onSelect={(e) => { setSelected(e); void refresh(e?.id); }}
            onCreated={(id) => { void refresh(id); }}
          />
        )}

        {selected && view === 'overview' && (
          <OverviewView key={`ov-${selected.id}-${casesVersion}`} engagement={selected} onOpenAnalysis={() => setView('analysis')} />
        )}
        {selected && view === 'ingest' && (
          <ImportPanel engagement={selected} history={history} onImported={() => void refresh(selected.id)} />
        )}
        {selected && view === 'analysis' && (
          <>
            <h2 className="view-title">PRAMETRA PRISM</h2>
            <p className="view-sub">Multi-Model Financial Integrity Engine — one population, multiple lenses, explainable results.</p>
            <RulesPanel key={`rules-${selected.id}-${casesVersion}`} engagementId={selected.id} />
            <BenfordPanel engagementId={selected.id} onChanged={() => setCasesVersion((v) => v + 1)} />
          </>
        )}
        {selected && view === 'gst' && (has(selected, 'GST')
          ? <GstPanel engagementId={selected.id} onReconciled={() => setCasesVersion((v) => v + 1)} />
          : <LockedModule name="Prametra GST" module="GST" engagement={selected} onChanged={() => void refresh(selected.id)} />)}
        {selected && view === 'vendor' && (has(selected, 'VENDOR') || has(selected, 'AUDIT_TRAIL')
          ? <VendorPanel engagementId={selected.id} />
          : <LockedModule name="Prametra Vendor + Prametra Trail" module="VENDOR,AUDIT_TRAIL" engagement={selected} onChanged={() => void refresh(selected.id)} />)}
        {selected && view === 'bank' && (has(selected, 'BANK')
          ? <BankPanel engagementId={selected.id} onReconciled={() => setCasesVersion((v) => v + 1)} />
          : <LockedModule name="Prametra Bank" module="BANK" engagement={selected} onChanged={() => void refresh(selected.id)} />)}
        {selected && view === 'evidence' && (
          <EvidencePanel engagementId={selected.id} onChanged={() => setCasesVersion((v) => v + 1)} />
        )}
        {selected && view === 'workpapers' && <WorkpaperPanel engagementId={selected.id} />}
      </div>
    </div>
  );
}

const ALL_MODULES = [
  ['GST', 'Prametra GST'],
  ['BANK', 'Prametra Bank'],
  ['VENDOR', 'Prametra Vendor'],
  ['AUDIT_TRAIL', 'Prametra Trail'],
] as const;

function has(e: Engagement, module: string) {
  return (e.modules ?? []).includes(module);
}

/** Subscription gate UI: what the module adds, and a one-click enable (ADMIN/PARTNER). */
function LockedModule({ name, module, engagement, onChanged }: {
  name: string; module: string; engagement: Engagement; onChanged: () => void;
}) {
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function enable() {
    setBusy(true);
    setError(null);
    try {
      const next = [...new Set([...(engagement.modules ?? []), ...module.split(',')])];
      const res = await fetch(`/api/engagements/${engagement.id}/modules`, {
        method: 'PUT', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ modules: next }),
      });
      const body = await res.json();
      if (!res.ok) throw new Error(body.error ?? `Failed (${res.status})`);
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card" style={{ opacity: 0.75 }}>
      <h2>🔒 {name}</h2>
      <p className="sub">
        This engagement's subscription does not include the {name} module.
        The core integrity engine keeps running; enabling the module adds its
        imports, reconciliations and signals to the same consolidated cases.
      </p>
      <button onClick={enable} disabled={busy}>{busy ? 'Enabling…' : 'Enable module'}</button>
      {error && <p className="error">{error}</p>}
    </section>
  );
}


// ---------- overview (Screen-4 style: tiles + charts + top signals) ----------

interface OvSlice { key: string; count: number; highCount: number; exposurePaise: number }
interface OvExplorer { byRule: OvSlice[]; byUser: OvSlice[]; byMonth: OvSlice[]; byAccount: OvSlice[] }

function OverviewView({ engagement, onOpenAnalysis }: { engagement: Engagement; onOpenAnalysis: () => void }) {
  const [row, setRow] = useState<PortfolioRow | null>(null);
  const [explorer, setExplorer] = useState<OvExplorer | null>(null);

  useEffect(() => {
    fetch('/api/dashboard')
      .then((r) => (r.ok ? r.json() : []))
      .then((rows: PortfolioRow[]) => setRow(rows.find((r) => r.engagementId === engagement.id) ?? null))
      .catch(() => {});
    fetch(`/api/engagements/${engagement.id}/risk-explorer`)
      .then((r) => (r.ok ? r.json() : null))
      .then(setExplorer)
      .catch(() => {});
  }, [engagement.id]);

  return (
    <>
      <h2 className="view-title">{engagement.clientName} — {engagement.fyStart} → {engagement.fyEnd}</h2>
      <p className="view-sub">Core subscription plus: {(engagement.modules ?? []).join(', ') || 'none (Core only)'} · population {engagement.populationCount.toLocaleString('en-IN')} rows</p>

      <div className="tiles-row">
        <div className="stile"><b>{engagement.populationCount.toLocaleString('en-IN')}</b><span>ledger rows in population</span></div>
        <div className={'stile' + ((row?.openHigh ?? 0) > 0 ? ' alert' : '')}><b>{row ? row.openExceptions : '—'}</b><span>open exceptions{row && row.openHigh > 0 ? ` (${row.openHigh} HIGH)` : ''}</span></div>
        <div className="stile"><b>{row ? `${row.openCases}/${row.totalCases}` : '—'}</b><span>open cases</span></div>
        <div className="stile"><b>{row ? '₹ ' + inr(row.estimatedExposurePaise) : '—'}</b><span>estimated exposure (de-duplicated)</span></div>
        <div className="stile"><b>{row ? '₹ ' + inr(row.confirmedExposurePaise) : '—'}</b><span>confirmed misstatement</span></div>
        <div className={'stile' + ((row?.overdueEvidence ?? 0) > 0 ? ' alert' : '')}><b>{row ? row.overdueEvidence : '—'}</b><span>overdue evidence</span></div>
        <div className="stile"><b>{row ? row.workpaperStatus : '—'}</b><span>workpaper</span></div>
      </div>

      {explorer && explorer.byMonth.length > 0 && (
        <div className="charts-row">
          <MonthExposureChart slices={explorer.byMonth} />
          <RuleExposureChart slices={explorer.byRule} onOpen={onOpenAnalysis} />
        </div>
      )}
      {(!explorer || explorer.byMonth.length === 0) && (
        <section className="card">
          <p className="sub">No open risk yet — import data and run the rule pack under Core Analysis.</p>
          <button onClick={onOpenAnalysis}>Open Core Analysis</button>
        </section>
      )}
    </>
  );
}

/** Open exposure by posting month — single series (sequential job), accent hue. */
function MonthExposureChart({ slices }: { slices: OvSlice[] }) {
  const data = [...slices].filter((s) => s.key !== '(n/a)').sort((a, b) => a.key.localeCompare(b.key));
  if (data.length === 0) return null;
  const W = 420, H = 190, padL = 8, padB = 26, padT = 14;
  const bw = Math.min(34, (W - padL * 2) / data.length - 6);
  const max = Math.max(...data.map((d) => d.exposurePaise), 1);
  const maxIdx = data.findIndex((d) => d.exposurePaise === max);
  return (
    <div className="chart-card">
      <h3>Open exposure by posting month</h3>
      <div className="legend">₹ exposure of open exceptions, grouped by the flagged voucher's month</div>
      <svg viewBox={`0 0 ${W} ${H}`} width="100%" role="img" aria-label="Open exposure by posting month">
        {data.map((d, i) => {
          const h = Math.max(3, ((H - padB - padT) * d.exposurePaise) / max);
          const x = padL + i * ((W - padL * 2) / data.length) + 3;
          const y = H - padB - h;
          return (
            <g key={d.key}>
              <rect x={x} y={y} width={bw} height={h} rx={4} fill="#2563eb">
                <title>{`${d.key}: ₹ ${inr(d.exposurePaise)} · ${d.count} signal(s)${d.highCount ? ` · ${d.highCount} HIGH` : ''}`}</title>
              </rect>
              {i === maxIdx && <text className="val" x={x + bw / 2} y={y - 4} textAnchor="middle">₹ {inr(d.exposurePaise)}</text>}
              <text x={x + bw / 2} y={H - 8} textAnchor="middle">{d.key.slice(2).replace('-', '/')}</text>
            </g>
          );
        })}
      </svg>
    </div>
  );
}

/** Top rules by open exposure — horizontal bars, one series, direct labels. */
function RuleExposureChart({ slices, onOpen }: { slices: OvSlice[]; onOpen: () => void }) {
  const data = [...slices].sort((a, b) => b.exposurePaise - a.exposurePaise).slice(0, 6);
  if (data.length === 0) return null;
  const W = 420, rowH = 27, padT = 6;
  const H = padT + data.length * rowH + 4;
  const max = Math.max(...data.map((d) => d.exposurePaise), 1);
  return (
    <div className="chart-card">
      <h3>What drives the open risk</h3>
      <div className="legend">top rules by ₹ exposure of open signals — <a href="#" onClick={(e) => { e.preventDefault(); onOpen(); }}>open the cases</a></div>
      <svg viewBox={`0 0 ${W} ${H}`} width="100%" role="img" aria-label="Top rules by open exposure">
        {data.map((d, i) => {
          const y = padT + i * rowH;
          const w = Math.max(3, (W - 190) * (d.exposurePaise / max));
          return (
            <g key={d.key}>
              <text x={0} y={y + 14}>{d.key.split(' ')[0]}</text>
              <rect x={56} y={y + 4} width={w} height={14} rx={4} fill="#2563eb">
                <title>{`${d.key}: ₹ ${inr(d.exposurePaise)} · ${d.count} signal(s)`}</title>
              </rect>
              <text className="val" x={56 + w + 6} y={y + 15}>₹ {inr(d.exposurePaise)}</text>
            </g>
          );
        })}
      </svg>
    </div>
  );
}



// ---------- account security (Phase A: MFA) ----------

function SecurityView({ me, onChanged }: { me: Me; onChanged: (m: Me) => void }) {
  const [setup, setSetup] = useState<{ secret: string; otpauthUri: string } | null>(null);
  const [code, setCode] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function post(url: string, body?: object) {
    setBusy(true); setError(null); setMessage(null);
    try {
      const res = await fetch(url, {
        method: 'POST',
        headers: body ? { 'Content-Type': 'application/json' } : undefined,
        body: body ? JSON.stringify(body) : undefined,
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.error ?? `Failed (${res.status})`);
      return data;
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
      return null;
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card" style={{ maxWidth: 640 }}>
      <h2>Account security — {me.email}</h2>
      <p className="sub">Multi-factor authentication (SEC-001): once enabled, every sign-in needs your password AND a 6-digit code from an authenticator app (Google Authenticator, Microsoft Authenticator, Aegis…).</p>

      {me.mfaEnabled ? (
        <>
          <p className="ok-text">✓ MFA is enabled on this account.</p>
          <div className="btn-row">
            <input inputMode="numeric" maxLength={6} placeholder="Current 6-digit code" value={code}
                   onChange={(e) => setCode(e.target.value)} style={{ width: 170 }} />
            <button disabled={busy || code.length !== 6} onClick={async () => {
              const m = await post('/api/auth/mfa/disable', { code });
              if (m) { onChanged(m); setCode(''); setMessage('MFA disabled.'); }
            }}>Disable MFA</button>
          </div>
        </>
      ) : !setup ? (
        <button disabled={busy} onClick={async () => {
          const s2 = await post('/api/auth/mfa/setup');
          if (s2) setSetup(s2);
        }}>Set up MFA</button>
      ) : (
        <>
          <p>1 · In your authenticator app, choose <b>Add account → Enter key manually</b> and paste:</p>
          <p className="mono" style={{ fontSize: '1rem', userSelect: 'all' }}>{setup.secret}</p>
          <p className="sub">(or add via URI: <span className="mono">{setup.otpauthUri}</span>)</p>
          <p>2 · Enter the 6-digit code the app shows to switch MFA on:</p>
          <div className="btn-row">
            <input inputMode="numeric" maxLength={6} placeholder="6-digit code" value={code}
                   onChange={(e) => setCode(e.target.value)} style={{ width: 150 }} />
            <button disabled={busy || code.length !== 6} onClick={async () => {
              const m = await post('/api/auth/mfa/enable', { code });
              if (m) { onChanged(m); setSetup(null); setCode(''); setMessage('MFA is now required on every sign-in.'); }
            }}>Verify & enable</button>
          </div>
        </>
      )}
      {message && <p className="ok-text">{message}</p>}
      {error && <p className="error">{error}</p>}
      <p className="sub" style={{ marginTop: 14 }}>Also here: <b>change password</b> any time via your profile — and evidence documents are encrypted at rest once APP_ENCRYPTION_KEY is set on the server.</p>
    </section>
  );
}

// ---------- commercial layer (Screen 2): customers, pricing, billing ----------

function CustomersView({ onOpen }: { onOpen: (engagementId: string) => void }) {
  const [customers, setCustomers] = useState<{
    name: string; engagementYears: number; latestFy: string; latestEngagementId: string;
    modules: string[]; estimatedFeePaise: number;
  }[]>([]);

  useEffect(() => {
    fetch('/api/customers').then((r) => (r.ok ? r.json() : [])).then(setCustomers).catch(() => {});
  }, []);

  return (
    <section className="card">
      <h2>Customer subscription management</h2>
      <p className="sub">Each customer's latest client-year, its module subscription, and the estimated fee from your price list. Core is always included.</p>
      {customers.length === 0 ? <p className="sub">No customers yet — create an engagement first.</p> : (
        <table>
          <thead><tr><th>Customer</th><th>Client-years</th><th>Latest FY</th><th>Subscription</th><th className="num">Estimated fee / client-year</th><th></th></tr></thead>
          <tbody>
            {customers.map((c) => (
              <tr key={c.name}>
                <td><strong>{c.name}</strong></td>
                <td className="num">{c.engagementYears}</td>
                <td>{c.latestFy}</td>
                <td>
                  <span className="sev sev-low">Core</span>{' '}
                  {c.modules.map((m) => <span key={m} className="sev sev-low">{m.replace('_', ' ')}</span>)}
                  {c.modules.length === 0 && <span className="sub"> (Core only)</span>}
                </td>
                <td className="num">₹ {inr(c.estimatedFeePaise)}</td>
                <td><a href="#" onClick={(e) => { e.preventDefault(); onOpen(c.latestEngagementId); }}>Open ▸</a></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  );
}

function BillingView() {
  const [pricing, setPricing] = useState<Record<string, string>>({});
  const [meta, setMeta] = useState<{ version: number; updatedBy: string } | null>(null);
  const [billing, setBilling] = useState<{
    lines: { client: string; financialYear: string; modules: string[]; corePaise: number; addOnsPaise: number; feePaise: number }[];
    totalPaise: number;
  } | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const PRICE_FIELDS: [string, string][] = [
    ['corePaise', 'Core (always included)'], ['gstPaise', 'GST Reconciliation'],
    ['bankPaise', 'Bank Reconciliation'], ['vendorPaise', 'Vendor & Payment Analytics'],
    ['auditTrailPaise', 'Audit Trail & Override'],
  ];

  async function load() {
    const p = await fetch('/api/pricing').then((r) => r.json());
    setMeta({ version: p.version, updatedBy: p.updatedBy });
    const next: Record<string, string> = {};
    for (const [k] of PRICE_FIELDS) next[k] = String(Math.round(p[k] / 100));
    setPricing(next);
    setBilling(await fetch('/api/billing').then((r) => r.json()));
  }
  useEffect(() => { void load(); }, []);

  async function save() {
    setError(null); setMessage(null);
    try {
      const body: Record<string, number> = {};
      for (const [k] of PRICE_FIELDS) body[k] = Number(pricing[k]) * 100;
      const res = await fetch('/api/pricing', {
        method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
      });
      const out = await res.json();
      if (!res.ok) throw new Error(out.error ?? `Failed (${res.status})`);
      setMessage('Price list saved as version ' + out.version + '. Fees below reflect the new list.');
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }

  return (
    <>
      <section className="card">
        <h2>Pricing &amp; plans</h2>
        <p className="sub">Per client-year prices (₹). These are your firm's placeholders until real commercial terms are set — versioned append-only{meta ? ` · v${meta.version} · ${meta.updatedBy}` : ''}.</p>
        <div className="btn-row">
          {PRICE_FIELDS.map(([k, label]) => (
            <label key={k}>{label}
              <input type="number" min={0} step={500} style={{ width: 110 }}
                value={pricing[k] ?? ''} onChange={(e) => setPricing({ ...pricing, [k]: e.target.value })} />
            </label>
          ))}
        </div>
        <button onClick={save}>Save price list</button>
        {message && <p className="ok-text">{message}</p>}
        {error && <p className="error">{error}</p>}
      </section>

      <section className="card">
        <h2>Billing summary</h2>
        <p className="sub">One line per client-year at the current price list. <a href={api('/api/billing.csv')} download>Download CSV</a>. The platform computes fees; invoicing and payment stay outside it.</p>
        {billing && (
          <table>
            <thead><tr><th>Client</th><th>Financial year</th><th>Modules</th><th className="num">Core</th><th className="num">Add-ons</th><th className="num">Fee</th></tr></thead>
            <tbody>
              {billing.lines.map((l, i) => (
                <tr key={i}>
                  <td>{l.client}</td><td>{l.financialYear}</td>
                  <td>{l.modules.length ? l.modules.join(', ') : 'Core only'}</td>
                  <td className="num">₹ {inr(l.corePaise)}</td>
                  <td className="num">₹ {inr(l.addOnsPaise)}</td>
                  <td className="num"><strong>₹ {inr(l.feePaise)}</strong></td>
                </tr>
              ))}
              <tr><td colSpan={5}><strong>Total (all client-years)</strong></td>
                <td className="num"><strong>₹ {inr(billing.totalPaise)}</strong></td></tr>
            </tbody>
          </table>
        )}
      </section>
    </>
  );
}

// ---------- engagement selection / creation ----------

interface PortfolioRow {
  engagementId: string;
  populationCount: number;
  openExceptions: number;
  openHigh: number;
  confirmedExceptions: number;
  openCases: number;
  totalCases: number;
  overdueEvidence: number;
  estimatedExposurePaise: number;
  confirmedExposurePaise: number;
  workpaperStatus: string;
}

function EngagementPanel(props: {
  engagements: Engagement[];
  selected: Engagement | null;
  onSelect: (e: Engagement | null) => void;
  onCreated: (id: string) => void;
}) {
  const [portfolio, setPortfolio] = useState<Map<string, PortfolioRow>>(new Map());
  useEffect(() => {
    fetch('/api/dashboard')
      .then((r) => (r.ok ? r.json() : []))
      .then((rows: PortfolioRow[]) => setPortfolio(new Map(rows.map((r) => [r.engagementId, r]))))
      .catch(() => {});
  }, [props.engagements]);
  const [clientName, setClientName] = useState('');
  const [fyStart, setFyStart] = useState('2024-04-01');
  const [fyEnd, setFyEnd] = useState('2025-03-31');
  const [closeDate, setCloseDate] = useState('2025-03-31');
  const [modules, setModules] = useState<string[]>(ALL_MODULES.map(([k]) => k));
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function create() {
    setBusy(true);
    setError(null);
    try {
      const res = await fetch('/api/engagements', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ clientName, fyStart, fyEnd, closeDate, modules }),
      });
      const body = await res.json();
      if (!res.ok) throw new Error(body.error ?? `Create failed (${res.status})`);
      setClientName('');
      props.onCreated(body.id as string);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card">
      <h2>1 · Engagement</h2>
      {props.engagements.length > 0 && (
        <table>
          <thead>
            <tr><th></th><th>Client</th><th>Financial year</th><th>Population</th>
              <th>Open (HIGH)</th><th>Open cases</th><th>Overdue evidence</th>
              <th>Est. exposure</th><th>Confirmed</th><th>Workpaper</th></tr>
          </thead>
          <tbody>
            {props.engagements.map((e) => {
              const p = portfolio.get(e.id);
              return (
                <tr key={e.id} className={props.selected?.id === e.id ? 'selected' : ''}>
                  <td>
                    <input
                      type="radio"
                      name="engagement"
                      checked={props.selected?.id === e.id}
                      onChange={() => props.onSelect(e)}
                    />
                  </td>
                  <td>{e.clientName}</td>
                  <td>{e.fyStart} → {e.fyEnd}</td>
                  <td className="num">{e.populationCount.toLocaleString('en-IN')}</td>
                  <td className="num">{p ? <>{p.openExceptions} {p.openHigh > 0 && <span className="sev sev-high">{p.openHigh} HIGH</span>}</> : '—'}</td>
                  <td className="num">{p ? `${p.openCases}/${p.totalCases}` : '—'}</td>
                  <td className="num">{p ? (p.overdueEvidence > 0 ? <span className="sev sev-high">{p.overdueEvidence}</span> : '0') : '—'}</td>
                  <td className="num">{p ? `₹ ${inr(p.estimatedExposurePaise)}` : '—'}</td>
                  <td className="num">{p ? `₹ ${inr(p.confirmedExposurePaise)}` : '—'}</td>
                  <td>{p?.workpaperStatus ?? '—'}</td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
      <p className="sub">Estimated exposure (open items) and confirmed misstatement are reported separately (RSK-005).</p>
      {props.selected && (
        <p className="sub">
          <a href="#" style={{ color: 'var(--err)' }} onClick={async (ev) => {
            ev.preventDefault();
            if (!confirm(`Permanently delete engagement ${props.selected!.clientName} and ALL its data? This produces an auditable deletion record and cannot be undone.`)) return;
            const res = await fetch(`/api/engagements/${props.selected!.id}`, { method: 'DELETE' });
            if (res.ok) { props.onSelect(null); props.onCreated(''); }
            else alert((await res.json()).error ?? 'Deletion failed');
          }}>Delete selected engagement (ADMIN, SEC-006)</a>
        </p>
      )}
      <details open={props.engagements.length === 0}>
        <summary>New engagement</summary>
        <div className="form-grid">
          <label>Client name
            <input value={clientName} onChange={(e) => setClientName(e.target.value)} placeholder="CLIENT-A" />
          </label>
          <label>FY start
            <input type="date" value={fyStart} onChange={(e) => setFyStart(e.target.value)} />
          </label>
          <label>FY end
            <input type="date" value={fyEnd} onChange={(e) => setFyEnd(e.target.value)} />
          </label>
          <label>Close date
            <input type="date" value={closeDate} onChange={(e) => setCloseDate(e.target.value)} />
          </label>
        </div>
        <p className="sub" style={{ margin: '8px 0 2px' }}>Subscription — Core (import, integrity engine, cases, evidence, workpapers) is always included; add-on modules:</p>
        <div className="btn-row">
          {ALL_MODULES.map(([key, label]) => (
            <label key={key} style={{ flexDirection: 'row', alignItems: 'center', gap: 6 }}>
              <input type="checkbox" checked={modules.includes(key)}
                onChange={(e) => setModules(e.target.checked ? [...modules, key] : modules.filter((m) => m !== key))} />
              {label}
            </label>
          ))}
        </div>
        <button onClick={create} disabled={busy || !clientName.trim()}>
          {busy ? 'Creating…' : 'Create engagement'}
        </button>
        {error && <p className="error">{error}</p>}
      </details>
    </section>
  );
}

// ---------- import ----------

function ImportPanel(props: { engagement: Engagement; history: ImportBatch[]; onImported: () => void }) {
  const [mappings, setMappings] = useState<MappingProfile[]>([]);
  const [mapping, setMapping] = useState('');
  const [glFile, setGlFile] = useState<File | null>(null);
  const [tbFile, setTbFile] = useState<File | null>(null);
  const [tallyFile, setTallyFile] = useState<File | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [summary, setSummary] = useState<ImportSummary | null>(null);

  useEffect(() => {
    fetch('/api/mappings')
      .then((r) => r.json())
      .then((list: MappingProfile[]) => {
        setMappings(list);
        if (list.length > 0) setMapping(list[0].name);
      })
      .catch(() => setError('Cannot load mapping profiles.'));
  }, []);

  async function runImport() {
    if (!glFile || !tbFile || !mapping) return;
    setBusy(true);
    setError(null);
    setSummary(null);
    try {
      const form = new FormData();
      form.append('gl', glFile);
      form.append('tb', tbFile);
      form.append('mapping', mapping);
      const isXlsx = glFile.name.toLowerCase().endsWith('.xlsx') || tbFile.name.toLowerCase().endsWith('.xlsx');
      const endpoint = isXlsx ? 'imports/xlsx' : 'imports';
      const res = await fetch(`/api/engagements/${props.engagement.id}/${endpoint}`, { method: 'POST', body: form });
      const body = await res.json();
      if (!res.ok) throw new Error(body.error ?? `Import failed (${res.status})`);
      setSummary(body as ImportSummary);
      props.onImported();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <>
      <section className="card">
        <h2>2 · Import population — {props.engagement.clientName}</h2>
        <div className="form-grid">
          <label>Mapping profile
            <select value={mapping} onChange={(e) => setMapping(e.target.value)}>
              {mappings.map((m) => (
                <option key={m.name} value={m.name}>{m.name} — {m.description ?? m.sourceType}</option>
              ))}
            </select>
          </label>
          <label>General ledger (CSV)
            <input type="file" accept=".csv,.xlsx" onChange={(e) => setGlFile(e.target.files?.[0] ?? null)} />
          </label>
          <label>Trial balance (CSV)
            <input type="file" accept=".csv,.xlsx" onChange={(e) => setTbFile(e.target.files?.[0] ?? null)} />
          </label>
        </div>
        <button onClick={runImport} disabled={busy || !glFile || !tbFile || !mapping}>
          {busy ? 'Importing…' : 'Import & validate'}
        </button>

        <div className="form-grid" style={{ marginTop: 14 }}>
          <label>Tally Daybook XML (uses the optional TB above for DAT-002)
            <input type="file" accept=".xml" onChange={(e) => setTallyFile(e.target.files?.[0] ?? null)} />
          </label>
        </div>
        <button onClick={async () => {
          if (!tallyFile) return;
          setBusy(true);
          setError(null);
          setSummary(null);
          try {
            const form = new FormData();
            form.append('xml', tallyFile);
            if (tbFile) form.append('tb', tbFile);
            const res = await fetch(`/api/engagements/${props.engagement.id}/imports/tally`, { method: 'POST', body: form });
            const body = await res.json();
            if (!res.ok) throw new Error(body.error ?? `Import failed (${res.status})`);
            setSummary(body as ImportSummary);
            props.onImported();
          } catch (e) {
            setError(e instanceof Error ? e.message : String(e));
          } finally {
            setBusy(false);
          }
        }} disabled={busy || !tallyFile}>
          {busy ? 'Importing…' : 'Import Tally XML'}
        </button>
        {error && <p className="error">{error}</p>}

        {props.history.length > 0 && (
          <>
            <h3>Import history</h3>
            <table>
              <thead>
                <tr><th>When</th><th>Profile</th><th>Rows</th><th>Added</th><th>Skipped</th><th>Issues</th><th>Balanced</th><th>TB</th><th></th></tr>
              </thead>
              <tbody>
                {props.history.map((b) => (
                  <tr key={b.id}>
                    <td>{new Date(b.importedAt).toLocaleString('en-IN')}</td>
                    <td>{b.profile}</td>
                    <td className="num">{b.totalRows.toLocaleString('en-IN')}</td>
                    <td className="num">{b.addedRows.toLocaleString('en-IN')}</td>
                    <td className="num">{b.skippedRows.toLocaleString('en-IN')}</td>
                    <td className="num">{b.issueCount}</td>
                    <td>{b.balanced ? '✓' : '✗'}</td>
                    <td>{b.tbAgrees ? '✓' : '✗'}</td>
                    <td>
                      <a href={api(`/api/engagements/${props.engagement.id}/imports/${b.id}/quality-report.csv`)} download>
                        quality CSV
                      </a>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </>
        )}
      </section>

      {summary && <SummaryView engagementId={props.engagement.id} summary={summary} />}
    </>
  );
}

// ---------- import result ----------

function SummaryView({ engagementId, summary }: { engagementId: string; summary: ImportSummary }) {
  return (
    <>
      <section className="card">
        <h2>3 · Completeness (DAT-002)</h2>
        <div className={summary.readyForAnalysis ? 'banner ok' : 'banner warn'}>
          {summary.readyForAnalysis
            ? 'Population is balanced and agrees to the trial balance — ready for analysis.'
            : 'Unexplained differences must be shown before analysis starts — review below.'}
        </div>
        <table>
          <tbody>
            <tr><th>Rows in upload</th><td>{summary.totalRows.toLocaleString('en-IN')} ({summary.cleanRows.toLocaleString('en-IN')} normalised)</td></tr>
            <tr><th>Added to population</th><td>{summary.addedRows.toLocaleString('en-IN')} (skipped as already present: {summary.skippedRows.toLocaleString('en-IN')})</td></tr>
            <tr><th>Engagement population</th><td>{summary.populationCount.toLocaleString('en-IN')} rows</td></tr>
            <tr><th>Total debits</th><td className="num">₹ {inr(summary.totalDebitPaise)}</td></tr>
            <tr><th>Total credits</th><td className="num">₹ {inr(summary.totalCreditPaise)}</td></tr>
            <tr><th>Debits = credits</th><td>{summary.balanced ? '✓ Balanced' : `✗ Unbalanced (${summary.voucherImbalanceCount} voucher(s))`}</td></tr>
            <tr><th>Trial balance</th><td>{summary.tbAgrees ? '✓ Agrees' : `✗ ${summary.tbDifferences.length} account difference(s)`}</td></tr>
          </tbody>
        </table>
        {!summary.balanced && summary.voucherImbalances.length > 0 && (
          <table>
            <thead><tr><th>Voucher</th><th>Debit</th><th>Credit</th><th>Difference</th></tr></thead>
            <tbody>
              {summary.voucherImbalances.map((v) => (
                <tr key={v.voucherId}>
                  <td>{v.voucherId}</td>
                  <td className="num">{inr(v.debit)}</td>
                  <td className="num">{inr(v.credit)}</td>
                  <td className="num">{inr(v.difference)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {!summary.tbAgrees && summary.tbDifferences.length > 0 && (
          <table>
            <thead><tr><th>Account</th><th>Ledger Dr</th><th>Ledger Cr</th><th>TB Dr</th><th>TB Cr</th><th>Difference</th></tr></thead>
            <tbody>
              {summary.tbDifferences.map((d) => (
                <tr key={d.accountCode}>
                  <td>{d.accountCode} {d.accountName}</td>
                  <td className="num">{inr(d.ledgerDebit)}</td>
                  <td className="num">{inr(d.ledgerCredit)}</td>
                  <td className="num">{inr(d.tbDebit)}</td>
                  <td className="num">{inr(d.tbCredit)}</td>
                  <td className="num">{inr(d.difference)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section className="card">
        <h2>4 · Data quality (DAT-003)</h2>
        {summary.issueCount === 0 ? (
          <p className="ok-text">No data-quality issues found.</p>
        ) : (
          <>
            <table>
              <thead><tr><th>Issue type</th><th>Count</th></tr></thead>
              <tbody>
                {Object.entries(summary.issueSummary).map(([type, count]) => (
                  <tr key={type}><td>{type}</td><td className="num">{count}</td></tr>
                ))}
              </tbody>
            </table>
            <table>
              <thead><tr><th>Source</th><th>Type</th><th>Message</th></tr></thead>
              <tbody>
                {summary.issues.map((i, k) => (
                  <tr key={k}>
                    <td>{i.file}:{i.row}</td>
                    <td>{i.type}</td>
                    <td>{i.message}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            {summary.issuesTruncated && <p className="sub">Showing first 500 issues — download the CSV for the full list.</p>}
          </>
        )}
        <p>
          <a href={api(`/api/engagements/${engagementId}/imports/${summary.importId}/quality-report.csv`)} download>
            Download data-quality report (CSV)
          </a>
        </p>
      </section>

      <section className="card">
        <h2>5 · Source manifest (DAT-001)</h2>
        <table>
          <thead><tr><th>File</th><th>Rows</th><th>Bytes</th><th>SHA-256</th></tr></thead>
          <tbody>
            {summary.files.map((f) => (
              <tr key={f.file}>
                <td>{f.file}</td>
                <td className="num">{f.rows.toLocaleString('en-IN')}</td>
                <td className="num">{f.bytes.toLocaleString('en-IN')}</td>
                <td className="mono">{f.sha256}</td>
              </tr>
            ))}
          </tbody>
        </table>
        <p className="sub">Same source snapshot + same rule version ⇒ same results (BRD reproducibility principle).</p>
      </section>
    </>
  );
}
