import { useState, useEffect } from 'react';
import UploadLecture from './components/UploadLecture.jsx';
import SummaryView   from './components/SummaryView.jsx';
import { checkHealth } from './services/api.js';

/**
 * App — Root component managing global state.
 *
 * States:
 *  - 'idle'    → Show upload form
 *  - 'loading' → Show spinner while backend processes PDF
 *  - 'done'    → Show SummaryView with results
 */
function App() {
  const [appState, setAppState]   = useState('idle');   // 'idle' | 'loading' | 'done'
  const [summary,  setSummary]    = useState(null);
  const [provider, setProvider]   = useState(null);

  // Fetch the active LLM provider from the health endpoint on mount
  useEffect(() => {
    checkHealth()
      .then((data) => setProvider(data.provider))
      .catch(() => setProvider('openai')); // fallback silently
  }, []);

  const handleLoading = (isLoading) => {
    setAppState(isLoading ? 'loading' : 'idle');
  };

  const handleSummaryReady = (data) => {
    setSummary(data);
    setAppState('done');
  };

  const handleReset = () => {
    setSummary(null);
    setAppState('idle');
  };

  const providerLabel = (p) => {
    if (!p) return '';
    return { openai: 'OpenAI GPT-4', claude: 'Anthropic Claude', gemini: 'Google Gemini' }[p.toLowerCase()]
      || p;
  };

  return (
    <>
      <div className="container">
        <div className="card">

          {/* ── Header ─────────────────────────────────── */}
          <header className="app-header">
            <h1>🎓 AI Teaching Assistant</h1>
            <p>Upload your lecture PDF and get an AI-powered structured summary instantly</p>
            {provider && (
              <div className="provider-badge">
                🤖 Powered by {providerLabel(provider)}
              </div>
            )}
          </header>

          {/* ── Body: Upload → Loading → Summary ───────── */}
          {appState === 'idle' && (
            <UploadLecture
              onSummaryReady={handleSummaryReady}
              onLoading={handleLoading}
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
    </>
  );
}

export default App;