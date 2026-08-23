import { api } from './api';
import { useCallback, useEffect, useState } from 'react';

interface Workpaper {
  id: string;
  version: number;
  title: string;
  status: 'DRAFT' | 'PREPARED' | 'REVIEWED' | 'SIGNED';
  contentSha256: string;
  createdAt: string;
  preparedBy: string | null;
  reviewedBy: string | null;
  approvedBy: string | null;
}

const NEXT_ROLE: Record<Workpaper['status'], { role: string; label: string } | null> = {
  DRAFT: { role: 'PREPARER', label: 'Sign as preparer' },
  PREPARED: { role: 'MANAGER', label: 'Sign as manager' },
  REVIEWED: { role: 'PARTNER', label: 'Sign as partner' },
  SIGNED: null,
};

export default function WorkpaperPanel({ engagementId }: { engagementId: string }) {
  const [list, setList] = useState<Workpaper[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(async () => {
    const res = await fetch(`/api/engagements/${engagementId}/workpapers`);
    if (res.ok) setList(await res.json());
  }, [engagementId]);

  useEffect(() => { void load(); }, [load]);

  async function generate() {
    setBusy(true);
    setError(null);
    try {
      const res = await fetch(`/api/engagements/${engagementId}/workpapers`, { method: 'POST' });
      const body = await res.json();
      if (!res.ok) throw new Error(body.error ?? `Generate failed (${res.status})`);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  async function sign(w: Workpaper) {
    const next = NEXT_ROLE[w.status];
    if (!next) return;
    setBusy(true);
    setError(null);
    try {
      const res = await fetch(`/api/workpapers/${w.id}/sign`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ role: next.role }),
      });
      const body = await res.json();
      if (!res.ok) throw new Error(body.error ?? `Sign failed (${res.status})`);
      await load();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card">
      <h2>9 · Workpapers</h2>
      <div className="btn-row">
        <button onClick={generate} disabled={busy}>Generate workpaper version</button>
        <span className="sub">Sign-offs are recorded under your login identity.</span>
        <a href={api(`/api/engagements/${engagementId}/audit-pack.zip`)} download>
          <button type="button">Audit File Pack (.zip)</button>
        </a>
      </div>
      {error && <p className="error">{error}</p>}

      {list.length > 0 && (
        <table>
          <thead>
            <tr><th>Version</th><th>Status</th><th>Prepared</th><th>Reviewed</th><th>Approved</th><th>SHA-256</th><th></th><th></th></tr>
          </thead>
          <tbody>
            {list.map((w) => {
              const next = NEXT_ROLE[w.status];
              return (
                <tr key={w.id}>
                  <td>v{w.version}</td>
                  <td><span className={`sev ${w.status === 'SIGNED' ? 'sev-low' : 'sev-medium'}`}>{w.status}</span>{w.status === 'SIGNED' && ' 🔒'}</td>
                  <td>{w.preparedBy ?? '—'}</td>
                  <td>{w.reviewedBy ?? '—'}</td>
                  <td>{w.approvedBy ?? '—'}</td>
                  <td className="mono">{w.contentSha256.slice(0, 12)}…</td>
                  <td>
                    {next && (
                      <button onClick={() => sign(w)} disabled={busy}>
                        {next.label}
                      </button>
                    )}
                  </td>
                  <td><a href={api(`/api/workpapers/${w.id}/export.html`)} download>HTML</a> · <a href={api(`/api/workpapers/${w.id}/export.doc`)} download>Word</a> · <a href={api(`/api/workpapers/${w.id}/export.pdf`)} download>PDF</a></td>
                </tr>
              );
            })}
          </tbody>
        </table>
      )}
      {list.length === 0 && <p className="sub">No workpaper versions yet. Generate one after running the analysis.</p>}
      <p className="sub">A signed version is locked; regenerating creates a new version (AWP-006). The export opens in Word or any browser.</p>
    </section>
  );
}
