import { api } from './api';
import { useCallback, useEffect, useState } from 'react';
import type { Me } from './AuthPanel';

interface ClientDocument {
  id: string;
  version: number;
  fileName: string;
  sizeBytes: number;
  uploadedBy: string;
  uploadedAt: string;
}

interface ClientRequest {
  id: string;
  title: string;
  description: string | null;
  dueDate: string | null;
  status: 'OPEN' | 'RESPONDED' | 'ACCEPTED' | 'REJECTED';
  overdue: boolean;
  responseNote: string | null;
  documents: ClientDocument[];
}

const STATUS_TEXT: Record<ClientRequest['status'], string> = {
  OPEN: 'Waiting for your documents',
  RESPONDED: 'Received — under review by your auditor',
  ACCEPTED: 'Complete — thank you',
  REJECTED: 'More information needed',
};

export default function ClientPortal({ me, onLogout }: { me: Me; onLogout: () => void }) {
  const [requests, setRequests] = useState<ClientRequest[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [resetDone, setResetDone] = useState(false);

  const load = useCallback(async () => {
    const res = await fetch('/api/client/requests');
    if (res.ok) setRequests(await res.json());
    else setError('Could not load your requests. Please try again.');
  }, []);

  useEffect(() => { void load(); }, [load]);

  const open = requests.filter((r) => r.status === 'OPEN' || r.status === 'REJECTED');

  // the initial password was chosen by the audit firm — it must be replaced before use
  if (me.passwordResetRequired && !resetDone) {
    return <ForcePasswordReset onDone={() => setResetDone(true)} onLogout={onLogout} />;
  }

  return (
    <main>
      <header className="topbar">
        <div>
          <h1>Document requests</h1>
          <p className="sub">{me.displayName} · prepared by {me.firmName}</p>
        </div>
        <div className="whoami"><button onClick={onLogout}>Sign out</button></div>
      </header>
      {error && <p className="error">{error}</p>}

      <section className="card">
        <p className="sub">
          {open.length === 0
            ? 'Nothing is waiting on you right now.'
            : `${open.length} request(s) need your documents.`}
        </p>
        {requests.map((r) => <RequestCard key={r.id} r={r} onChanged={load} />)}
        {requests.length === 0 && <p className="sub">No document requests yet.</p>}
      </section>
    </main>
  );
}

function RequestCard({ r, onChanged }: { r: ClientRequest; onChanged: () => Promise<void> }) {
  const [files, setFiles] = useState<File[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function upload() {
    if (files.length === 0) return;
    setBusy(true);
    setError(null);
    try {
      for (const f of files) {
        const form = new FormData();
        form.append('file', f);
        const res = await fetch(`/api/client/requests/${r.id}/documents`, { method: 'POST', body: form });
        const body = await res.json();
        if (!res.ok) throw new Error(body.error ?? `Upload failed (${res.status})`);
      }
      setFiles([]);
      await onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  const statusClass = r.status === 'ACCEPTED' ? 'sev-low' : r.status === 'REJECTED' ? 'sev-high' : 'sev-medium';

  return (
    <div className="case">
      <div className="case-head" style={{ cursor: 'default' }}>
        <span className={`sev ${statusClass}`}>{STATUS_TEXT[r.status]}</span>
        {r.overdue && <span className="sev sev-high">OVERDUE</span>}
        <span className="case-title">{r.title}</span>
        <span className="case-meta">{r.dueDate ? `due ${r.dueDate}` : ''}</span>
      </div>
      <div style={{ padding: '10px 14px' }}>
        {r.description && <p className="sub">{r.description}</p>}
        {r.responseNote && <p className="error">Your auditor asked: {r.responseNote}</p>}
        {r.documents.length > 0 && (
          <table>
            <thead><tr><th>File</th><th>Version</th><th>Uploaded</th></tr></thead>
            <tbody>
              {r.documents.map((d) => (
                <tr key={d.id}>
                  <td><a href={api(`/api/client/documents/${d.id}/download`)} download>{d.fileName}</a></td>
                  <td className="num">v{d.version}</td>
                  <td>{new Date(d.uploadedAt).toLocaleString('en-IN')}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {r.status !== 'ACCEPTED' && (
          <div className="btn-row">
            <input type="file" multiple onChange={(e) => setFiles(Array.from(e.target.files ?? []))} />
            <button onClick={upload} disabled={busy || files.length === 0}>{busy ? 'Uploading…' : files.length > 1 ? `Upload ${files.length} documents` : 'Upload document'}</button>
          </div>
        )}
        {error && <p className="error">{error}</p>}
      </div>
    </div>
  );
}

function ForcePasswordReset({ onDone, onLogout }: { onDone: () => void; onLogout: () => void }) {
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [confirm, setConfirm] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function change() {
    setError(null);
    if (next !== confirm) { setError('The new passwords do not match.'); return; }
    setBusy(true);
    try {
      const res = await fetch('/api/auth/change-password', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ currentPassword: current, newPassword: next }),
      });
      const body = await res.json();
      if (!res.ok) throw new Error(body.error ?? `Failed (${res.status})`);
      onDone();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <main>
      <header>
        <h1>Set your password</h1>
        <p className="sub">
          Your account was created with a temporary password chosen by your audit firm.
          Please set your own password before continuing — nobody else should know it.
        </p>
      </header>
      <section className="card">
        <div className="form-grid">
          <label>Temporary password
            <input type="password" value={current} onChange={(e) => setCurrent(e.target.value)} />
          </label>
          <label>New password (min 8 characters)
            <input type="password" value={next} onChange={(e) => setNext(e.target.value)} />
          </label>
          <label>Confirm new password
            <input type="password" value={confirm} onChange={(e) => setConfirm(e.target.value)} />
          </label>
        </div>
        <div className="btn-row">
          <button onClick={change} disabled={busy || !current || next.length < 8 || !confirm}>
            {busy ? 'Saving…' : 'Set password'}
          </button>
          <button onClick={onLogout}>Sign out</button>
        </div>
        {error && <p className="error">{error}</p>}
      </section>
    </main>
  );
}
