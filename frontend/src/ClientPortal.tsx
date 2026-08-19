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

  const load = useCallback(async () => {
    const res = await fetch('/api/client/requests');
    if (res.ok) setRequests(await res.json());
    else setError('Could not load your requests. Please try again.');
  }, []);

  useEffect(() => { void load(); }, [load]);

  const open = requests.filter((r) => r.status === 'OPEN' || r.status === 'REJECTED');

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
