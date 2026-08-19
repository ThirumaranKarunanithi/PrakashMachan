import { api } from './api';
import { useCallback, useEffect, useState } from 'react';

interface ExceptionRef {
  id: string;
  ruleId: string;
  ruleName: string;
  voucherIds: string;
  status: string;
}

interface DocumentDto {
  id: string;
  version: number;
  fileName: string;
  sizeBytes: number;
  sha256: string;
  uploadedBy: string;
  uploadedAt: string;
}

interface RequestDto {
  id: string;
  exceptionId: string;
  title: string;
  description: string | null;
  requestedBy: string;
  dueDate: string | null;
  status: 'OPEN' | 'RESPONDED' | 'ACCEPTED' | 'REJECTED';
  overdue: boolean;
  decisionNote: string | null;
  decidedBy: string | null;
  documents: DocumentDto[];
}

export default function EvidencePanel({ engagementId, onChanged }: { engagementId: string; onChanged: () => void }) {
  const [requests, setRequests] = useState<RequestDto[]>([]);
  const [openExceptions, setOpenExceptions] = useState<ExceptionRef[]>([]);
  const [error, setError] = useState<string | null>(null);
  // create form
  const [exceptionId, setExceptionId] = useState('');
  const [title, setTitle] = useState('');
  const [dueDate, setDueDate] = useState('');
  const [requestedBy, setRequestedBy] = useState('');
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    const [reqRes, exRes] = await Promise.all([
      fetch(`/api/engagements/${engagementId}/evidence-requests`),
      fetch(`/api/engagements/${engagementId}/exceptions`),
    ]);
    if (reqRes.ok) setRequests(await reqRes.json());
    if (exRes.ok) {
      const all = (await exRes.json()) as ExceptionRef[];
      setOpenExceptions(all);
      if (all.length > 0 && !exceptionId) setExceptionId(all[0].id);
    }
  }, [engagementId]); // eslint-disable-line react-hooks/exhaustive-deps

  useEffect(() => { void load(); }, [load]);

  async function create() {
    setBusy(true);
    setError(null);
    try {
      const res = await fetch(`/api/exceptions/${exceptionId}/evidence-requests`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ title, requestedBy, dueDate: dueDate || null }),
      });
      const body = await res.json();
      if (!res.ok) throw new Error(body.error ?? `Create failed (${res.status})`);
      setTitle('');
      await load();
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card">
      <h2>8 · Evidence room</h2>
      <div className="form-grid">
        <label>For exception
          <select value={exceptionId} onChange={(e) => setExceptionId(e.target.value)}>
            {openExceptions.map((x) => (
              <option key={x.id} value={x.id}>{x.ruleId} · {x.voucherIds} · {x.status}</option>
            ))}
          </select>
        </label>
        <label>What is needed
          <input value={title} onChange={(e) => setTitle(e.target.value)} placeholder="e.g. Provision computation and approval" />
        </label>
        <label>Due date
          <input type="date" value={dueDate} onChange={(e) => setDueDate(e.target.value)} />
        </label>
        <label>Requested by
          <input value={requestedBy} onChange={(e) => setRequestedBy(e.target.value)} placeholder="Your name" />
        </label>
      </div>
      <button onClick={create} disabled={busy || !exceptionId || !title.trim() || !requestedBy.trim()}>
        Create evidence request
      </button>
      {error && <p className="error">{error}</p>}

      {requests.map((r) => <RequestView key={r.id} r={r} onChanged={async () => { await load(); onChanged(); }} />)}
      {requests.length === 0 && <p className="sub">No evidence requests yet.</p>}

      <details>
        <summary>Client portal access (CDC-002)</summary>
        <ClientAccessForm engagementId={engagementId} />
      </details>
    </section>
  );
}

function ClientAccessForm({ engagementId }: { engagementId: string }) {
  const [email, setEmail] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [password, setPassword] = useState('');
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function create() {
    setError(null);
    setMessage(null);
    try {
      const res = await fetch(`/api/engagements/${engagementId}/client-users`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ email, displayName, password }),
      });
      const body = await res.json();
      if (!res.ok) throw new Error(body.error ?? `Failed (${res.status})`);
      setMessage(`Client access created for ${body.email}. Share the sign-in details with them securely — they will see only this engagement's document requests.`);
      setEmail(''); setDisplayName(''); setPassword('');
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    }
  }

  return (
    <div style={{ padding: '8px 0' }}>
      <div className="form-grid">
        <label>Client email<input type="email" value={email} onChange={(e) => setEmail(e.target.value)} /></label>
        <label>Client name<input value={displayName} onChange={(e) => setDisplayName(e.target.value)} /></label>
        <label>Initial password (min 8)<input type="password" value={password} onChange={(e) => setPassword(e.target.value)} /></label>
      </div>
      <button onClick={create} disabled={!email.trim() || !displayName.trim() || password.length < 8}>
        Create client access
      </button>
      {message && <p className="ok-text">{message}</p>}
      {error && <p className="error">{error}</p>}
    </div>
  );
}

function RequestView({ r, onChanged }: { r: RequestDto; onChanged: () => Promise<void> }) {
  const [file, setFile] = useState<File | null>(null);
  const [note, setNote] = useState(r.decisionNote ?? '');
  const [decider, setDecider] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function call(url: string, init: RequestInit) {
    setBusy(true);
    setError(null);
    try {
      const res = await fetch(url, init);
      const body = await res.json();
      if (!res.ok) throw new Error(body.error ?? `Failed (${res.status})`);
      await onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  function upload() {
    if (!file) return;
    const form = new FormData();
    form.append('file', file);
    form.append('uploadedBy', 'client-user');
    void call(`/api/evidence-requests/${r.id}/documents`, { method: 'POST', body: form });
  }

  function decide(decision: 'ACCEPTED' | 'REJECTED') {
    void call(`/api/evidence-requests/${r.id}/decision`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ decision, note, decidedBy: decider || 'auditor' }),
    });
  }

  const statusClass = r.status === 'ACCEPTED' ? 'sev-low' : r.status === 'REJECTED' ? 'sev-high' : 'sev-medium';

  return (
    <div className="case">
      <div className="case-head" style={{ cursor: 'default' }}>
        <span className={`sev ${statusClass}`}>{r.status}</span>
        {r.overdue && <span className="sev sev-high">OVERDUE</span>}
        <span className="case-title">{r.title}</span>
        <span className="case-meta">
          requested by {r.requestedBy}{r.dueDate ? ` · due ${r.dueDate}` : ''} · {r.documents.length} file(s)
        </span>
      </div>
      <div style={{ padding: '10px 14px' }}>
        {r.documents.length > 0 && (
          <table>
            <thead><tr><th>v</th><th>File</th><th>Bytes</th><th>SHA-256</th><th>Uploaded by</th><th></th></tr></thead>
            <tbody>
              {r.documents.map((d) => (
                <tr key={d.id}>
                  <td>{d.version}</td>
                  <td>{d.fileName}</td>
                  <td className="num">{d.sizeBytes.toLocaleString('en-IN')}</td>
                  <td className="mono">{d.sha256.slice(0, 12)}…</td>
                  <td>{d.uploadedBy}</td>
                  <td><a href={api(`/api/evidence-documents/${d.id}/download`)} download>download</a></td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
        {r.decisionNote && <p className="sub">Decision: {r.decisionNote} — {r.decidedBy}</p>}
        {r.status !== 'ACCEPTED' && (
          <div className="btn-row">
            <input type="file" onChange={(e) => setFile(e.target.files?.[0] ?? null)} />
            <button onClick={upload} disabled={busy || !file}>Upload response (as client)</button>
            {r.status === 'RESPONDED' && (
              <>
                <input placeholder="Sufficiency reason (required)" value={note} onChange={(e) => setNote(e.target.value)} />
                <input placeholder="Decided by" value={decider} onChange={(e) => setDecider(e.target.value)} />
                <button onClick={() => decide('ACCEPTED')} disabled={busy || !note.trim()}>Accept</button>
                <button onClick={() => decide('REJECTED')} disabled={busy || !note.trim()}>Reject</button>
              </>
            )}
          </div>
        )}
        {error && <p className="error">{error}</p>}
      </div>
    </div>
  );
}
