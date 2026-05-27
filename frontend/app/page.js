'use client';

import { useState, useEffect } from 'react';

export default function Home() {
  const [token, setToken] = useState(null);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [targets, setTargets] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);
  
  // Form states
  const [companyName, setCompanyName] = useState('');
  const [recipientEmail, setRecipientEmail] = useState('');
  const [jobUrl, setJobUrl] = useState('');
  const [jobDescription, setJobDescription] = useState('');

  // Initial load
  useEffect(() => {
    const storedToken = localStorage.getItem('auth_token');
    if (storedToken) {
      setToken(storedToken);
    }
  }, []);

  useEffect(() => {
    if (token) {
      fetchTargets();
    }
  }, [token]);

  const fetchTargets = async () => {
    try {
      const res = await fetch('/api/targets', {
        headers: { 'Authorization': `Basic ${token}` }
      });
      if (res.status === 401) {
        logout();
        return;
      }
      if (!res.ok) throw new Error('Failed to fetch targets');
      const data = await res.json();
      setTargets(data);
    } catch (err) {
      setError(err.message);
    }
  };

  const handleLogin = (e) => {
    e.preventDefault();
    const encoded = btoa(`${username}:${password}`);
    localStorage.setItem('auth_token', encoded);
    setToken(encoded);
  };

  const logout = () => {
    localStorage.removeItem('auth_token');
    setToken(null);
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setLoading(true);
    setError(null);

    const payload = { companyName, recipientEmail, jobUrl, jobDescription };

    try {
      const res = await fetch('/api/targets', {
        method: 'POST',
        headers: {
          'Authorization': `Basic ${token}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify(payload)
      });

      if (res.status === 401) {
        logout();
        return;
      }

      if (!res.ok) {
        const data = await res.json();
        throw new Error(data.error || 'Failed to add target');
      }

      // Reset form
      setCompanyName('');
      setRecipientEmail('');
      setJobUrl('');
      setJobDescription('');
      
      fetchTargets();
    } catch (err) {
      setError(err.message);
    } finally {
      setLoading(false);
    }
  };

  if (!token) {
    return (
      <div className="container">
        <div className="glass auth-container animate-fade-in">
          <h1>Welcome Back</h1>
          <p style={{ color: 'var(--text-muted)', marginBottom: '2rem' }}>Sign in to manage outreach targets.</p>
          <form onSubmit={handleLogin}>
            <div className="form-group">
              <label>Username (Admin)</label>
              <input type="text" value={username} onChange={e => setUsername(e.target.value)} required />
            </div>
            <div className="form-group">
              <label>Password</label>
              <input type="password" value={password} onChange={e => setPassword(e.target.value)} required />
            </div>
            <button type="submit" style={{ width: '100%' }}>Authenticate</button>
          </form>
        </div>
      </div>
    );
  }

  return (
    <div className="container animate-fade-in">
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
        <h1>Outreach Command Center</h1>
        <button onClick={logout} style={{ background: 'transparent', border: '1px solid var(--glass-border)' }}>Logout</button>
      </div>

      <div className="glass" style={{ padding: '2rem', marginBottom: '2rem' }}>
        <h2>Add New Target</h2>
        {error && <div style={{ color: 'var(--failed)', marginBottom: '1rem' }}>{error}</div>}
        <form onSubmit={handleSubmit} style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '1.5rem' }}>
          <div className="form-group" style={{ marginBottom: 0 }}>
            <label>Company Name</label>
            <input type="text" value={companyName} onChange={e => setCompanyName(e.target.value)} required placeholder="e.g. OpenAI" />
          </div>
          <div className="form-group" style={{ marginBottom: 0 }}>
            <label>Recipient Email</label>
            <input type="email" value={recipientEmail} onChange={e => setRecipientEmail(e.target.value)} required placeholder="hr@openai.com" />
          </div>
          <div className="form-group" style={{ marginBottom: 0 }}>
            <label>Job URL (Optional)</label>
            <input type="url" value={jobUrl} onChange={e => setJobUrl(e.target.value)} placeholder="https://..." />
          </div>
          <div className="form-group" style={{ marginBottom: 0, gridColumn: '1 / -1' }}>
            <label>Job Description</label>
            <textarea value={jobDescription} onChange={e => setJobDescription(e.target.value)} rows="3" required placeholder="Copy-paste job description here..."></textarea>
          </div>
          <div style={{ gridColumn: '1 / -1', display: 'flex', justifyContent: 'flex-end' }}>
            <button type="submit" disabled={loading}>
              {loading ? 'Adding...' : 'Add Target +'}
            </button>
          </div>
        </form>
      </div>

      <div className="glass table-container">
        <table>
          <thead>
            <tr>
              <th>Company</th>
              <th>Recipient</th>
              <th>Status</th>
              <th>Retry Count</th>
              <th>Created At</th>
            </tr>
          </thead>
          <tbody>
            {targets.map(t => (
              <tr key={t.id}>
                <td style={{ fontWeight: 500 }}>{t.companyName}</td>
                <td style={{ color: 'var(--text-muted)' }}>{t.recipientEmail}</td>
                <td>
                  <span className={`badge ${t.status ? t.status.toLowerCase() : 'unknown'}`}>{t.status}</span>
                </td>
                <td>{t.retryCount}</td>
                <td style={{ color: 'var(--text-muted)' }}>{new Date(t.createdAt).toLocaleDateString()}</td>
              </tr>
            ))}
            {targets.length === 0 && (
              <tr>
                <td colSpan="5" style={{ textAlign: 'center', color: 'var(--text-muted)' }}>No targets found. Add one above!</td>
              </tr>
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
