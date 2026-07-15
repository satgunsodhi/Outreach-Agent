'use client';

import { useState, useEffect } from 'react';

export default function Home() {
  const [token, setToken] = useState(null);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [targets, setTargets] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  // Tab Navigation State: 'dashboard' | 'sandbox' | 'tools'
  const [activeTab, setActiveTab] = useState('dashboard');

  // Form states (Add Target)
  const [companyName, setCompanyName] = useState('');
  const [recipientEmail, setRecipientEmail] = useState('');
  const [jobUrl, setJobUrl] = useState('');
  const [jobDescription, setJobDescription] = useState('');

  // Selected Target details state (Slide-out Drawer)
  const [selectedTarget, setSelectedTarget] = useState(null);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [actionLoading, setActionLoading] = useState(false);
  const [copyFeedback, setCopyFeedback] = useState(null); // 'subject' | 'body' | null

  // Search and Filter States
  const [searchQuery, setSearchQuery] = useState('');
  const [statusFilter, setStatusFilter] = useState('ALL');

  // Resume Tailor Sandbox State
  const [sandboxJobDescription, setSandboxJobDescription] = useState('');
  const [sandboxResearch, setSandboxResearch] = useState('');
  const [sandboxLoading, setSandboxLoading] = useState(false);
  const [sandboxResult, setSandboxResult] = useState(null);
  const [sandboxError, setSandboxError] = useState(null);

  // Admin Tools State
  const [toolsKeyword, setToolsKeyword] = useState('');
  const [toolsEmail, setToolsEmail] = useState('');
  const [toolsLoading, setToolsLoading] = useState(false);
  const [toolsFeedback, setToolsFeedback] = useState(null); // { type: 'success' | 'error', message: string }

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

  // Periodically refresh targets to track active PROCESSING state
  useEffect(() => {
    if (!token) return;
    const interval = setInterval(() => {
      fetchTargets();
    }, 5000);
    return () => clearInterval(interval);
  }, [token]);

  // Keep drawer target in sync with latest list updates (e.g. if it processes)
  useEffect(() => {
    if (selectedTarget && targets.length > 0) {
      const updated = targets.find(t => t.id === selectedTarget.id);
      if (updated) {
        setSelectedTarget(updated);
      }
    }
  }, [targets, selectedTarget]);

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
    setSelectedTarget(null);
    setDrawerOpen(false);
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

  // Drawer Target Reset (Individual)
  const handleResetTarget = async (id) => {
    setActionLoading(true);
    try {
      const res = await fetch(`/api/targets/${id}/reset`, {
        method: 'POST',
        headers: { 'Authorization': `Basic ${token}` }
      });
      if (res.status === 401) {
        logout();
        return;
      }
      if (!res.ok) {
        const data = await res.json();
        throw new Error(data.error || 'Failed to reset target');
      }
      const updated = await res.json();
      setSelectedTarget(updated);
      fetchTargets();
    } catch (err) {
      alert(`Error resetting: ${err.message}`);
    } finally {
      setActionLoading(false);
    }
  };

  // Drawer Target Delete (Individual)
  const handleDeleteTarget = async (id) => {
    if (!confirm('Are you sure you want to delete this target? This will also remove the draft from Gmail.')) {
      return;
    }
    setActionLoading(true);
    try {
      const res = await fetch(`/api/targets/${id}`, {
        method: 'DELETE',
        headers: { 'Authorization': `Basic ${token}` }
      });
      if (res.status === 401) {
        logout();
        return;
      }
      if (!res.ok) {
        const data = await res.json();
        throw new Error(data.error || 'Failed to delete target');
      }
      closeDrawer();
      fetchTargets();
    } catch (err) {
      alert(`Error deleting: ${err.message}`);
    } finally {
      setActionLoading(false);
    }
  };

  // Sandbox Generate Resume
  const handleSandboxGenerate = async (e) => {
    e.preventDefault();
    setSandboxLoading(true);
    setSandboxResult(null);
    setSandboxError(null);

    try {
      const res = await fetch('/api/resume/generate', {
        method: 'POST',
        headers: {
          'Authorization': `Basic ${token}`,
          'Content-Type': 'application/json'
        },
        body: JSON.stringify({
          jobDescription: sandboxJobDescription,
          companyResearch: sandboxResearch
        })
      });

      if (res.status === 401) {
        logout();
        return;
      }

      const data = await res.json();
      if (!res.ok || !data.pdfPath) {
        throw new Error(data.message || 'Error generating tailored resume');
      }

      setSandboxResult(data);
    } catch (err) {
      setSandboxError(err.message);
    } finally {
      setSandboxLoading(false);
    }
  };

  // Admin Tools: Clean Targets by Keyword
  const handleBulkClean = async () => {
    if (!toolsKeyword) return;
    if (!confirm(`Delete all targets and Gmail drafts matching "${toolsKeyword}"?`)) return;

    setToolsLoading(true);
    setToolsFeedback(null);
    try {
      const res = await fetch(`/api/resume/clean-targets?keyword=${encodeURIComponent(toolsKeyword)}`, {
        method: 'DELETE',
        headers: { 'Authorization': `Basic ${token}` }
      });

      if (res.status === 401) {
        logout();
        return;
      }

      const text = await res.text();
      setToolsFeedback({ type: res.ok ? 'success' : 'error', message: text });
      setToolsKeyword('');
      fetchTargets();
    } catch (err) {
      setToolsFeedback({ type: 'error', message: err.message });
    } finally {
      setToolsLoading(false);
    }
  };

  // Admin Tools: Reset Targets by Keyword
  const handleBulkReset = async () => {
    if (!toolsKeyword) return;
    if (!confirm(`Reset targets matching "${toolsKeyword}" back to PENDING (and delete drafts)?`)) return;

    setToolsLoading(true);
    setToolsFeedback(null);
    try {
      const res = await fetch(`/api/resume/reset-targets?keyword=${encodeURIComponent(toolsKeyword)}`, {
        method: 'POST',
        headers: { 'Authorization': `Basic ${token}` }
      });

      if (res.status === 401) {
        logout();
        return;
      }

      const text = await res.text();
      setToolsFeedback({ type: res.ok ? 'success' : 'error', message: text });
      setToolsKeyword('');
      fetchTargets();
    } catch (err) {
      setToolsFeedback({ type: 'error', message: err.message });
    } finally {
      setToolsLoading(false);
    }
  };

  // Admin Tools: Reset Test Targets by Email
  const handleResetTests = async () => {
    if (!toolsEmail) return;
    setToolsLoading(true);
    setToolsFeedback(null);
    try {
      const res = await fetch(`/api/outreach/reset-tests?testEmail=${encodeURIComponent(toolsEmail)}`, {
        method: 'POST',
        headers: { 'Authorization': `Basic ${token}` }
      });

      if (res.status === 401) {
        logout();
        return;
      }

      const data = await res.json();
      setToolsFeedback({ type: res.ok ? 'success' : 'error', message: data.message || 'Operation complete.' });
      setToolsEmail('');
      fetchTargets();
    } catch (err) {
      setToolsFeedback({ type: 'error', message: err.message });
    } finally {
      setToolsLoading(false);
    }
  };

  const openDrawer = (target) => {
    setSelectedTarget(target);
    setDrawerOpen(true);
  };

  const closeDrawer = () => {
    setDrawerOpen(false);
    // Timeout matches transition to clear targets nicely
    setTimeout(() => setSelectedTarget(null), 300);
  };

  const copyToClipboard = (text, field) => {
    navigator.clipboard.writeText(text);
    setCopyFeedback(field);
    setTimeout(() => setCopyFeedback(null), 1500);
  };

  // KPI Calculations
  const stats = {
    total: targets.length,
    pending: targets.filter(t => t.status === 'PENDING').length,
    processing: targets.filter(t => t.status === 'PROCESSING').length,
    drafted: targets.filter(t => t.status === 'DRAFT_CREATED' || t.status === 'FOLLOW_UP_DRAFT_CREATED').length,
    failed: targets.filter(t => t.status === 'FAILED').length
  };

  // Filtered Targets list
  const filteredTargets = targets.filter(t => {
    const query = searchQuery.toLowerCase();
    const matchesSearch =
      t.companyName.toLowerCase().includes(query) ||
      t.recipientEmail.toLowerCase().includes(query);

    if (statusFilter === 'ALL') return matchesSearch;
    if (statusFilter === 'DRAFTED') {
      return matchesSearch && (t.status === 'DRAFT_CREATED' || t.status === 'FOLLOW_UP_DRAFT_CREATED');
    }
    return matchesSearch && t.status === statusFilter;
  });

  const formatDate = (dateStr) => {
    if (!dateStr) return '-';
    return new Date(dateStr).toLocaleString(undefined, {
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit'
    });
  };

  // Authentication screen
  if (!token) {
    return (
      <div className="auth-container glass animate-fade-in">
        <div style={{ textAlign: 'center', marginBottom: '2rem' }}>
          <div style={{
            width: '50px',
            height: '50px',
            borderRadius: '12px',
            background: 'linear-gradient(135deg, var(--primary), var(--accent))',
            margin: '0 auto 1.5rem',
            display: 'flex',
            alignItems: 'center',
            justifyContent: 'center',
            fontWeight: '700',
            fontSize: '1.5rem',
            boxShadow: '0 8px 24px rgba(99, 102, 241, 0.3)'
          }}>🚀</div>
          <h1 style={{ fontSize: '2rem', marginBottom: '0.5rem' }}>Outreach Console</h1>
          <p style={{ color: 'var(--text-muted)' }}>Enter your admin credentials to connect.</p>
        </div>
        <form onSubmit={handleLogin}>
          <div className="form-group">
            <label>Admin Username</label>
            <input
              type="text"
              value={username}
              onChange={e => setUsername(e.target.value)}
              required
              placeholder="Username"
            />
          </div>
          <div className="form-group">
            <label>Password</label>
            <input
              type="password"
              value={password}
              onChange={e => setPassword(e.target.value)}
              required
              placeholder="••••••••"
            />
          </div>
          <button type="submit" style={{ width: '100%', marginTop: '1rem' }}>
            Authenticate & Launch
          </button>
        </form>
      </div>
    );
  }

  // Dashboard / Sandbox / Admin Tools Console Layout
  return (
    <div className="app-wrapper animate-fade-in">
      {/* SIDEBAR NAVIGATION */}
      <aside className="sidebar">
        <div>
          <div className="sidebar-brand">
            <div className="sidebar-logo">🤖</div>
            <span className="sidebar-title">Outreach Agent</span>
          </div>

          <nav className="sidebar-nav">
            <div
              className={`nav-item ${activeTab === 'dashboard' ? 'active' : ''}`}
              onClick={() => setActiveTab('dashboard')}
            >
              <span>📊</span> Dashboard
            </div>
            <div
              className={`nav-item ${activeTab === 'sandbox' ? 'active' : ''}`}
              onClick={() => setActiveTab('sandbox')}
            >
              <span>🧪</span> Resume Sandbox
            </div>
            <div
              className={`nav-item ${activeTab === 'tools' ? 'active' : ''}`}
              onClick={() => setActiveTab('tools')}
            >
              <span>⚙️</span> System Tools
            </div>
          </nav>
        </div>

        <div className="sidebar-footer">
          <div className="user-badge">
            <div className="user-avatar">A</div>
            <div>
              <div style={{ fontSize: '0.9rem', fontWeight: 600 }}>Administrator</div>
              <div style={{ fontSize: '0.75rem', color: 'var(--text-muted)' }}>Session active</div>
            </div>
          </div>
          <button onClick={logout} className="secondary" style={{ width: '100%', fontSize: '0.85rem', padding: '0.6rem' }}>
            Logout Session
          </button>
        </div>
      </aside>

      {/* MAIN VIEW AREA */}
      <main className="main-content">

        {/* TAB 1: DASHBOARD */}
        {activeTab === 'dashboard' && (
          <div className="animate-fade-in" style={{ display: 'flex', flexDirection: 'column', gap: '2rem' }}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
              <div>
                <h1>Campaign Control Center</h1>
                <p style={{ color: 'var(--text-muted)', marginTop: '0.25rem' }}>Monitor and schedule tailored professional outreaches.</p>
              </div>
            </div>

            {/* KPI METRICS ROW */}
            <div className="stats-grid">
              <div className="glass stat-card">
                <div className="stat-icon-wrapper" style={{ background: 'rgba(99, 102, 241, 0.1)', color: 'var(--primary)' }}>💼</div>
                <div className="stat-details">
                  <p className="stat-val">{stats.total}</p>
                  <p className="stat-label">Total Targets</p>
                </div>
              </div>
              <div className="glass stat-card">
                <div className="stat-icon-wrapper" style={{ background: 'rgba(59, 130, 246, 0.1)', color: 'var(--processing)' }}>⚡</div>
                <div className="stat-details">
                  <p className="stat-val">{stats.processing}</p>
                  <p className="stat-label">Active Processing</p>
                </div>
              </div>
              <div className="glass stat-card">
                <div className="stat-icon-wrapper" style={{ background: 'rgba(16, 185, 129, 0.1)', color: 'var(--success)' }}>📨</div>
                <div className="stat-details">
                  <p className="stat-val">{stats.drafted}</p>
                  <p className="stat-label">Drafts Generated</p>
                </div>
              </div>
              <div className="glass stat-card">
                <div className="stat-icon-wrapper" style={{ background: 'rgba(244, 63, 94, 0.1)', color: 'var(--failed)' }}>⚠️</div>
                <div className="stat-details">
                  <p className="stat-val">{stats.failed}</p>
                  <p className="stat-label">Pipeline Failures</p>
                </div>
              </div>
            </div>

            {/* MAIN DASHBOARD CONTENT GRID */}
            <div className="dashboard-grid">

              {/* TARGETS LIST */}
              <section className="glass data-table-card">
                <h2 style={{ marginBottom: '1.25rem' }}>Outreach Queue</h2>

                <div className="search-controls">
                  <input
                    type="text"
                    placeholder="Search company or email..."
                    value={searchQuery}
                    onChange={e => setSearchQuery(e.target.value)}
                    style={{ flex: 1 }}
                  />
                  <select
                    value={statusFilter}
                    onChange={e => setStatusFilter(e.target.value)}
                    style={{ width: '180px' }}
                  >
                    <option value="ALL">All Statuses</option>
                    <option value="PENDING">Pending</option>
                    <option value="PROCESSING">Processing</option>
                    <option value="DRAFTED">Draft Created</option>
                    <option value="FAILED">Failed</option>
                  </select>
                </div>

                <div className="table-scroll">
                  <table>
                    <thead>
                      <tr>
                        <th>Company</th>
                        <th>Recipient</th>
                        <th>Status</th>
                        <th>Retries</th>
                        <th>Scheduled Date</th>
                      </tr>
                    </thead>
                    <tbody>
                      {filteredTargets.map(t => (
                        <tr
                          key={t.id}
                          onClick={() => openDrawer(t)}
                          className={`table-row ${selectedTarget?.id === t.id ? 'selected' : ''}`}
                        >
                          <td style={{ fontWeight: 600 }}>{t.companyName}</td>
                          <td style={{ color: 'var(--text-muted)' }}>{t.recipientEmail}</td>
                          <td>
                            <span className={`badge ${t.status?.toLowerCase() || 'pending'}`}>
                              {t.status}
                            </span>
                          </td>
                          <td style={{ textAlign: 'center' }}>{t.retryCount}</td>
                          <td style={{ color: 'var(--text-muted)', fontSize: '0.85rem' }}>
                            {formatDate(t.createdAt)}
                          </td>
                        </tr>
                      ))}
                      {filteredTargets.length === 0 && (
                        <tr>
                          <td colSpan="5" style={{ textAlign: 'center', color: 'var(--text-muted)', padding: '3rem 1rem' }}>
                            No matching targets found. Add one on the right to start!
                          </td>
                        </tr>
                      )}
                    </tbody>
                  </table>
                </div>
              </section>

              {/* ADD TARGET FORM */}
              <section className="glass" style={{ padding: '2rem' }}>
                <h2 style={{ marginBottom: '1.25rem' }}>Add New Target</h2>
                {error && (
                  <div className="feedback-alert error" style={{ margin: '0 0 1rem 0' }}>
                    <span>⚠️</span> {error}
                  </div>
                )}

                <form onSubmit={handleSubmit}>
                  <div className="form-group">
                    <label>Company Name</label>
                    <input
                      type="text"
                      value={companyName}
                      onChange={e => setCompanyName(e.target.value)}
                      required
                      placeholder="e.g. Google"
                    />
                  </div>
                  <div className="form-group">
                    <label>Recipient Email</label>
                    <input
                      type="email"
                      value={recipientEmail}
                      onChange={e => setRecipientEmail(e.target.value)}
                      required
                      placeholder="manager@google.com"
                    />
                  </div>
                  <div className="form-group">
                    <label>Job URL (Optional)</label>
                    <input
                      type="url"
                      value={jobUrl}
                      onChange={e => setJobUrl(e.target.value)}
                      placeholder="https://careers.google.com/..."
                    />
                  </div>
                  <div className="form-group">
                    <label>Job Description</label>
                    <textarea
                      value={jobDescription}
                      onChange={e => setJobDescription(e.target.value)}
                      rows="6"
                      required
                      placeholder="Paste the full job posting description here..."
                    ></textarea>
                  </div>
                  <button type="submit" disabled={loading} style={{ width: '100%', marginTop: '0.5rem' }}>
                    {loading ? 'Scheduling...' : 'Queue Target Target +'}
                  </button>
                </form>
              </section>

            </div>
          </div>
        )}

        {/* TAB 2: RESUME SANDBOX */}
        {activeTab === 'sandbox' && (
          <div className="animate-fade-in" style={{ maxWidth: '800px', margin: '0 auto', width: '100%' }}>
            <h1 style={{ marginBottom: '0.5rem' }}>Tailor Resume Sandbox</h1>
            <p style={{ color: 'var(--text-muted)', marginBottom: '2rem' }}>
              Manually run the resume orchestration engine for any target. Input the job description and your company research to generate a customized PDF.
            </p>

            <div className="glass" style={{ padding: '2.5rem' }}>
              <form onSubmit={handleSandboxGenerate}>
                <div className="form-group">
                  <label>Job Description</label>
                  <textarea
                    value={sandboxJobDescription}
                    onChange={e => setSandboxJobDescription(e.target.value)}
                    rows="8"
                    required
                    placeholder="Paste target job requirements and duties..."
                  ></textarea>
                </div>
                <div className="form-group">
                  <label>Company Research</label>
                  <textarea
                    value={sandboxResearch}
                    onChange={e => setSandboxResearch(e.target.value)}
                    rows="4"
                    required
                    placeholder="Provide culture keywords, current news, or projects to weave into the cover letter..."
                  ></textarea>
                </div>
                <button type="submit" disabled={sandboxLoading} style={{ width: '100%', marginTop: '1rem' }}>
                  {sandboxLoading ? 'Tailoring Resume (Could take 1-2 mins)...' : 'Run Resumè Tailoring Engine ⚡'}
                </button>
              </form>

              {sandboxError && (
                <div className="feedback-alert error">
                  <span>⚠️</span> Failed to tailor resume: {sandboxError}
                </div>
              )}

              {sandboxResult && (
                <div className="feedback-alert success">
                  <div>
                    <h3 style={{ fontSize: '1rem', color: '#34d399', marginBottom: '0.25rem' }}>✨ Generation Successful!</h3>
                    <p style={{ fontSize: '0.85rem', color: 'var(--text-muted)' }}>
                      <strong>Output Path:</strong> {sandboxResult.pdfPath}
                    </p>
                    <p style={{ fontSize: '0.85rem', color: 'var(--text-muted)', marginTop: '0.25rem' }}>
                      {sandboxResult.message}
                    </p>
                  </div>
                </div>
              )}
            </div>
          </div>
        )}

        {/* TAB 3: ADMIN TOOLS */}
        {activeTab === 'tools' && (
          <div className="animate-fade-in" style={{ width: '100%' }}>
            <h1 style={{ marginBottom: '0.5rem' }}>Outreach Control Panel</h1>
            <p style={{ color: 'var(--text-muted)', marginBottom: '2.5rem' }}>Execute bulk cleaner commands and manage test target lists.</p>

            <div className="tools-grid">
              {/* TOOL A: RESET KEYWORDS */}
              <div className="glass tool-card">
                <div>
                  <h3 style={{ fontSize: '1.1rem', fontWeight: 600, marginBottom: '0.5rem' }}>Reset by Keyword</h3>
                  <p className="tool-info">Search all generated cover letters and PDFs for a keyword, delete their Gmail drafts/PDFs, and reset the status back to PENDING.</p>
                </div>
                <div className="form-group" style={{ marginBottom: 0 }}>
                  <label>Match Keyword</label>
                  <input
                    type="text"
                    value={toolsKeyword}
                    onChange={e => setToolsKeyword(e.target.value)}
                    placeholder="e.g. Python"
                  />
                </div>
                <button
                  onClick={handleBulkReset}
                  disabled={toolsLoading || !toolsKeyword}
                  style={{ width: '100%' }}
                >
                  {toolsLoading ? 'Resetting...' : 'Search & Reset Status'}
                </button>
              </div>

              {/* TOOL B: CLEAN KEYWORDS */}
              <div className="glass tool-card">
                <div>
                  <h3 style={{ fontSize: '1.1rem', fontWeight: 600, marginBottom: '0.5rem' }}>Clean (Delete) by Keyword</h3>
                  <p className="tool-info">Search all records for a matching keyword. Permanently deletes targets from database, plus their associated Gmail drafts and files.</p>
                </div>
                <div className="form-group" style={{ marginBottom: 0 }}>
                  <label>Match Keyword</label>
                  <input
                    type="text"
                    value={toolsKeyword}
                    onChange={e => setToolsKeyword(e.target.value)}
                    placeholder="e.g. Python"
                  />
                </div>
                <button
                  onClick={handleBulkClean}
                  disabled={toolsLoading || !toolsKeyword}
                  className="danger"
                  style={{ width: '100%' }}
                >
                  {toolsLoading ? 'Cleaning...' : 'Search & Delete Targets'}
                </button>
              </div>

              {/* TOOL C: RESET TEST EMAILS */}
              <div className="glass tool-card">
                <div>
                  <h3 style={{ fontSize: '1.1rem', fontWeight: 600, marginBottom: '0.5rem' }}>Reset Test Emails</h3>
                  <p className="tool-info">Reset all target campaigns targeting a specific test recipient email back to PENDING. Clears tokens, draft IDs, and retry counts.</p>
                </div>
                <div className="form-group" style={{ marginBottom: 0 }}>
                  <label>Test Recipient Email</label>
                  <input
                    type="email"
                    value={toolsEmail}
                    onChange={e => setToolsEmail(e.target.value)}
                    placeholder="e.g. test@domain.com"
                  />
                </div>
                <button
                  onClick={handleResetTests}
                  disabled={toolsLoading || !toolsEmail}
                  className="secondary"
                  style={{ width: '100%' }}
                >
                  {toolsLoading ? 'Resetting Tests...' : 'Reset Test Targets'}
                </button>
              </div>
            </div>

            {toolsFeedback && (
              <div className={`feedback-alert ${toolsFeedback.type}`}>
                <span>{toolsFeedback.type === 'success' ? '✓' : '⚠️'}</span> {toolsFeedback.message}
              </div>
            )}
          </div>
        )}
      </main>

      {/* DETAIL SIDE SLIDER DRAWER */}
      <div
        className={`drawer-backdrop ${drawerOpen ? 'open' : ''}`}
        onClick={closeDrawer}
      />
      <div className={`drawer ${drawerOpen ? 'open' : ''}`}>
        {selectedTarget && (
          <>
            <div className="drawer-header">
              <div>
                <h3 style={{ fontSize: '1.25rem', fontWeight: 700 }}>Target Specification</h3>
                <p style={{ fontSize: '0.8rem', color: 'var(--text-muted)', marginTop: '0.15rem' }}>ID: #{selectedTarget.id}</p>
              </div>
              <button
                onClick={closeDrawer}
                className="secondary"
                style={{ borderRadius: '50%', padding: '0.5rem', width: '32px', height: '32px', lineHeight: 1 }}
              >
                ✕
              </button>
            </div>

            <div className="drawer-body">
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                <span style={{ fontSize: '0.9rem', fontWeight: 600, color: 'var(--text-muted)' }}>Status Details</span>
                <span className={`badge ${selectedTarget.status?.toLowerCase() || 'pending'}`}>
                  {selectedTarget.status}
                </span>
              </div>

              {/* CORE INFO */}
              <div className="glass" style={{ padding: '1.25rem', display: 'flex', flexDirection: 'column', gap: '0.75rem' }}>
                <div style={{ display: 'grid', gridTemplateColumns: '120px 1fr', fontSize: '0.875rem' }}>
                  <span style={{ color: 'var(--text-muted)' }}>Company:</span>
                  <span style={{ fontWeight: 600 }}>{selectedTarget.companyName}</span>
                </div>
                <div style={{ display: 'grid', gridTemplateColumns: '120px 1fr', fontSize: '0.875rem' }}>
                  <span style={{ color: 'var(--text-muted)' }}>Recipient:</span>
                  <span style={{ color: 'var(--primary)' }}>{selectedTarget.recipientEmail}</span>
                </div>
                {selectedTarget.jobUrl && (
                  <div style={{ display: 'grid', gridTemplateColumns: '120px 1fr', fontSize: '0.875rem' }}>
                    <span style={{ color: 'var(--text-muted)' }}>Job URL:</span>
                    <a href={selectedTarget.jobUrl} target="_blank" rel="noreferrer" style={{ color: 'var(--accent)', textDecoration: 'none', wordBreak: 'break-all' }}>
                      Open Career Site ↗
                    </a>
                  </div>
                )}
                <div style={{ display: 'grid', gridTemplateColumns: '120px 1fr', fontSize: '0.875rem' }}>
                  <span style={{ color: 'var(--text-muted)' }}>Scheduled At:</span>
                  <span>{formatDate(selectedTarget.createdAt)}</span>
                </div>
                {selectedTarget.processingStartedAt && (
                  <div style={{ display: 'grid', gridTemplateColumns: '120px 1fr', fontSize: '0.875rem' }}>
                    <span style={{ color: 'var(--text-muted)' }}>Processing At:</span>
                    <span>{formatDate(selectedTarget.processingStartedAt)}</span>
                  </div>
                )}
                {selectedTarget.draftCreatedAt && (
                  <div style={{ display: 'grid', gridTemplateColumns: '120px 1fr', fontSize: '0.875rem' }}>
                    <span style={{ color: 'var(--text-muted)' }}>Draft Created:</span>
                    <span>{formatDate(selectedTarget.draftCreatedAt)}</span>
                  </div>
                )}
                {selectedTarget.emailSentAt && (
                  <div style={{ display: 'grid', gridTemplateColumns: '120px 1fr', fontSize: '0.875rem' }}>
                    <span style={{ color: 'var(--text-muted)' }}>Sent At:</span>
                    <span>{formatDate(selectedTarget.emailSentAt)}</span>
                  </div>
                )}
                <div style={{ display: 'grid', gridTemplateColumns: '120px 1fr', fontSize: '0.875rem' }}>
                  <span style={{ color: 'var(--text-muted)' }}>Retry Count:</span>
                  <span>{selectedTarget.retryCount} times</span>
                </div>
                {selectedTarget.generatedPdfPath && (
                  <div style={{ display: 'grid', gridTemplateColumns: '120px 1fr', fontSize: '0.875rem' }}>
                    <span style={{ color: 'var(--text-muted)' }}>PDF Asset:</span>
                    <span style={{ fontSize: '0.8rem', fontFamily: 'monospace', color: 'var(--text-muted)', wordBreak: 'break-all' }}>{selectedTarget.generatedPdfPath}</span>
                  </div>
                )}
              </div>

              {/* FAILURES OR ERRORS */}
              {selectedTarget.status === 'FAILED' && selectedTarget.errorReason && (
                <div className="error-callout">
                  <h4 style={{ fontWeight: 600, marginBottom: '0.25rem' }}>Process Interrupted:</h4>
                  <p>{selectedTarget.errorReason}</p>
                </div>
              )}

              {/* SUBJECT AND DRAFT LETTER PREVIEW */}
              {selectedTarget.subject && (
                <div>
                  <div className="preview-header">
                    <label style={{ margin: 0 }}>Generated Subject Line</label>
                    <button
                      className="secondary"
                      style={{ fontSize: '0.75rem', padding: '0.25rem 0.5rem' }}
                      onClick={() => copyToClipboard(selectedTarget.subject, 'subject')}
                    >
                      {copyFeedback === 'subject' ? '✓ Copied!' : 'Copy'}
                    </button>
                  </div>
                  <div className="preview-box" style={{ padding: '0.75rem 1rem', whiteSpace: 'normal' }}>
                    {selectedTarget.subject}
                  </div>
                </div>
              )}

              {selectedTarget.draftedCoverLetter && (
                <div>
                  <div className="preview-header">
                    <label style={{ margin: 0 }}>Cover Letter Text Draft</label>
                    <button
                      className="secondary"
                      style={{ fontSize: '0.75rem', padding: '0.25rem 0.5rem' }}
                      onClick={() => copyToClipboard(selectedTarget.draftedCoverLetter, 'body')}
                    >
                      {copyFeedback === 'body' ? '✓ Copied!' : 'Copy Cover Letter'}
                    </button>
                  </div>
                  <div className="preview-box">
                    {selectedTarget.draftedCoverLetter}
                  </div>
                </div>
              )}
            </div>

            <div className="drawer-footer">
              <button
                onClick={() => handleDeleteTarget(selectedTarget.id)}
                className="danger"
                disabled={actionLoading}
              >
                Delete Target
              </button>

              <div style={{ display: 'flex', gap: '0.5rem' }}>
                <button
                  onClick={() => handleResetTarget(selectedTarget.id)}
                  disabled={actionLoading || selectedTarget.status === 'PENDING'}
                  className="secondary"
                >
                  {actionLoading ? 'Resetting...' : 'Reset Status'}
                </button>
                <button onClick={closeDrawer} className="secondary">
                  Close details
                </button>
              </div>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
