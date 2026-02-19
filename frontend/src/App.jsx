import { useState }     from 'react';
import { useAuth }      from './context/AuthContext';
import LoginPage        from './pages/LoginPage';
import SignupPage       from './pages/SignupPage';
import UploadLecture    from './components/UploadLecture.jsx';
import SummaryView      from './components/SummaryView.jsx';
import { checkHealth }  from './services/api.js';
import { useEffect }    from 'react';

/**
 * App — Root component.
 *
 * Auth states:  'login' | 'signup'
 * App states:   'idle'  | 'loading' | 'done'
 */
function App() {
  const { isAuthenticated, isLoading, user, logout, accessToken } = useAuth();

  const [authView,  setAuthView]  = useState('login');   // 'login' | 'signup'
  const [appState,  setAppState]  = useState('idle');     // 'idle' | 'loading' | 'done'
  const [summary,   setSummary]   = useState(null);
  const [provider,  setProvider]  = useState(null);

  useEffect(() => {
    if (isAuthenticated) {
      checkHealth()
        .then(d => setProvider(d.provider))
        .catch(() => setProvider('openai'));
    }
  }, [isAuthenticated]);

  const handleLoading      = (v) => setAppState(v ? 'loading' : 'idle');
  const handleSummaryReady = (d) => { setSummary(d); setAppState('done'); };
  const handleReset        = ()  => { setSummary(null); setAppState('idle'); };

  const providerLabel = (p) =>
    ({ openai: 'OpenAI GPT-4', claude: 'Anthropic Claude', gemini: 'Google Gemini' })[p?.toLowerCase()] || p;

  // ── 1. Initial hydration spinner ─────────────────────────────────────────
  if (isLoading) {
    return (
      <div className="hydration-screen">
        <div className="spinner large-spinner" />
        <p>Loading…</p>
      </div>
    );
  }

  // ── 2. Not authenticated → show login or signup ──────────────────────────
  if (!isAuthenticated) {
    return authView === 'login'
      ? <LoginPage  onNavigateSignup={() => setAuthView('signup')} />
      : <SignupPage onNavigateLogin={()  => setAuthView('login')}  />;
  }

  // ── 3. Authenticated → main app ──────────────────────────────────────────
  return (
    <div className="container">
      <div className="card">

        {/* Header */}
        <header className="app-header">
          <div className="header-top-row">
            <div className="header-user-info">
              <span className="user-avatar">{user?.fullName?.[0]?.toUpperCase() || '?'}</span>
              <span className="user-name">{user?.fullName}</span>
            </div>
            <button className="logout-btn" onClick={logout}>
              Sign out
            </button>
          </div>
          <h1>🎓 AI Teaching Assistant</h1>
          <p>Upload your lecture PDF and get an AI-powered structured summary instantly</p>
          {provider && (
            <div className="provider-badge">
              🤖 Powered by {providerLabel(provider)}
            </div>
          )}
        </header>

        {/* Body */}
        {appState === 'idle' && (
          <UploadLecture
            onSummaryReady={handleSummaryReady}
            onLoading={handleLoading}
            accessToken={accessToken}
          />
        )}

        {appState === 'loading' && (
          <div className="loading-section">
            <div className="spinner" />
            <h3>Generating your summary…</h3>
            <p>Our AI is reading your lecture and extracting key insights</p>
            <div className="progress-dots">
              <span /><span /><span />
            </div>
          </div>
        )}

        {appState === 'done' && summary && (
          <SummaryView summary={summary} onReset={handleReset} />
        )}

      </div>

      <footer className="app-footer">
        AI Teaching Assistant v1.0 · Spring Boot + React · {new Date().getFullYear()}
      </footer>
    </div>
  );
}

export default App;