import { useState } from 'react';

export interface Me {
  email: string;
  displayName: string;
  role: string;
  firmId: string;
  firmName: string;
  passwordResetRequired?: boolean;
}

export default function AuthPanel({ onAuthed }: { onAuthed: (me: Me) => void }) {
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [firmName, setFirmName] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function submit() {
    setBusy(true);
    setError(null);
    try {
      const url = mode === 'login' ? '/api/auth/login' : '/api/auth/register-firm';
      const body = mode === 'login'
        ? { email, password }
        : { firmName, displayName, email, password };
      const res = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body),
      });
      const data = await res.json();
      if (!res.ok) throw new Error(data.error ?? `Failed (${res.status})`);
      onAuthed(data as Me);
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className="card" style={{ maxWidth: 460, margin: '48px auto' }}>
      <h2>{mode === 'login' ? 'Sign in' : 'Register your firm'}</h2>
      <div className="form-grid" style={{ gridTemplateColumns: '1fr' }}>
        {mode === 'register' && (
          <>
            <label>Firm name
              <input value={firmName} onChange={(e) => setFirmName(e.target.value)} placeholder="Sharma & Associates" />
            </label>
            <label>Your name
              <input value={displayName} onChange={(e) => setDisplayName(e.target.value)} placeholder="CA Asha Sharma" />
            </label>
          </>
        )}
        <label>Email
          <input type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="you@firm.in" />
        </label>
        <label>Password {mode === 'register' && <span className="sub">(min 8 characters)</span>}
          <input type="password" value={password} onChange={(e) => setPassword(e.target.value)}
                 onKeyDown={(e) => e.key === 'Enter' && submit()} />
        </label>
      </div>
      <button onClick={submit} disabled={busy || !email.trim() || !password
        || (mode === 'register' && (!firmName.trim() || !displayName.trim()))}>
        {busy ? 'Working…' : mode === 'login' ? 'Sign in' : 'Create firm & sign in'}
      </button>
      {error && <p className="error">{error}</p>}
      <p className="sub" style={{ marginTop: 14 }}>
        {mode === 'login' ? (
          <>New firm? <a href="#" onClick={(e) => { e.preventDefault(); setMode('register'); setError(null); }}>Register</a></>
        ) : (
          <>Already registered? <a href="#" onClick={(e) => { e.preventDefault(); setMode('login'); setError(null); }}>Sign in</a></>
        )}
      </p>
      <p className="sub">Each firm's data is fully isolated (SEC-001). Every action is recorded in the audit log (SEC-004).</p>
    </section>
  );
}
