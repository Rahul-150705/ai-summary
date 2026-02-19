import { useState } from 'react';
import { useAuth } from '../context/AuthContext';

/**
 * LoginPage — Handles user authentication.
 * On success, AuthContext updates global state and App re-renders to the main view.
 */
function LoginPage({ onNavigateSignup }) {
  const { login } = useAuth();
  const [form,    setForm]    = useState({ email: '', password: '' });
  const [error,   setError]   = useState('');
  const [loading, setLoading] = useState(false);

  const handleChange = (e) => {
    setForm(prev => ({ ...prev, [e.target.name]: e.target.value }));
    setError('');
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (!form.email || !form.password) {
      setError('Please fill in all fields.');
      return;
    }
    setLoading(true);
    try {
      await login(form.email, form.password);
      // AuthContext updates → App re-renders automatically
    } catch (err) {
      setError(err.message || 'Login failed. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="auth-page">
      {/* Left decorative panel */}
      <div className="auth-brand-panel">
        <div className="auth-brand-content">
          <div className="auth-brand-icon">🎓</div>
          <h1 className="auth-brand-title">AI Teaching<br />Assistant</h1>
          <p className="auth-brand-subtitle">
            Transform your lecture PDFs into structured, exam-ready summaries powered by AI.
          </p>
          <div className="auth-features">
            <div className="auth-feature-item">⚡ Instant summaries</div>
            <div className="auth-feature-item">📖 Key concepts & definitions</div>
            <div className="auth-feature-item">🎯 Exam-focused takeaways</div>
            <div className="auth-feature-item">🤖 GPT-4 / Claude / Gemini</div>
          </div>
        </div>
        <div className="auth-brand-blob blob-1" />
        <div className="auth-brand-blob blob-2" />
      </div>

      {/* Right form panel */}
      <div className="auth-form-panel">
        <div className="auth-form-card">
          <div className="auth-form-header">
            <h2>Welcome back</h2>
            <p>Sign in to your account to continue</p>
          </div>

          <form className="auth-form" onSubmit={handleSubmit}>
            <div className="auth-field">
              <label htmlFor="email">Email address</label>
              <input
                id="email"
                name="email"
                type="email"
                autoComplete="email"
                placeholder="you@example.com"
                value={form.email}
                onChange={handleChange}
                disabled={loading}
                className={error ? 'input-error' : ''}
              />
            </div>

            <div className="auth-field">
              <label htmlFor="password">Password</label>
              <input
                id="password"
                name="password"
                type="password"
                autoComplete="current-password"
                placeholder="••••••••"
                value={form.password}
                onChange={handleChange}
                disabled={loading}
                className={error ? 'input-error' : ''}
              />
            </div>

            {error && (
              <div className="auth-error">
                <span>⚠️</span> {error}
              </div>
            )}

            <button
              type="submit"
              className="auth-submit-btn"
              disabled={loading}
            >
              {loading ? (
                <><span className="btn-spinner" /> Signing in…</>
              ) : (
                'Sign in →'
              )}
            </button>
          </form>

          <div className="auth-switch">
            Don't have an account?{' '}
            <button onClick={onNavigateSignup} className="auth-link-btn">
              Create one free
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

export default LoginPage;