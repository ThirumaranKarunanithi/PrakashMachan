import { useCallback, useEffect, useState } from 'react';

interface NotificationItem {
  type: string;
  message: string;
  createdAt: string;
  read: boolean;
}

export default function NotificationBell() {
  const [unread, setUnread] = useState(0);
  const [items, setItems] = useState<NotificationItem[]>([]);
  const [open, setOpen] = useState(false);

  const load = useCallback(async () => {
    const res = await fetch('/api/notifications');
    if (res.ok) {
      const body = await res.json();
      setUnread(body.unread as number);
      setItems(body.items as NotificationItem[]);
    }
  }, []);

  useEffect(() => {
    void load();
    const t = setInterval(() => void load(), 30_000);
    return () => clearInterval(t);
  }, [load]);

  async function markRead() {
    await fetch('/api/notifications/read-all', { method: 'POST' });
    await load();
  }

  return (
    <div className="bell-wrap">
      <button className="bell" onClick={() => setOpen(!open)} aria-label="Notifications">
        🔔{unread > 0 && <span className="bell-badge">{unread}</span>}
      </button>
      {open && (
        <div className="bell-panel">
          <div className="bell-head">
            <b>Notifications</b>
            {unread > 0 && <a href="#" onClick={(e) => { e.preventDefault(); void markRead(); }}>Mark all read</a>}
          </div>
          {items.length === 0 && <p className="sub" style={{ padding: '8px 12px' }}>Nothing yet.</p>}
          {items.slice(0, 15).map((n, i) => (
            <div key={i} className={`bell-item ${n.read ? '' : 'unread'}`}>
              <span className="sub">{n.type.replaceAll('_', ' ').toLowerCase()} · {new Date(n.createdAt).toLocaleString('en-IN')}</span>
              <div>{n.message}</div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
