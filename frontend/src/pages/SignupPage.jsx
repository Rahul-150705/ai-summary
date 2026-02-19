import { useState } from 'react';
import { useAuth } from '../context/AuthContext';

/**
 * SignupPage — Handles new user registration.
 * Auto-logs in after successful signup via AuthContext.
 */
function SignupPage({ onNavigateLogin }) {
  const { signup } = useAuth();
  const [form, setForm] = useState({
    fullName: '',
    email:    '',
    password: '',
    confirm:  '',
  });
  const [errors,  setErrors]  = useState({});
  const [loading, setLoading] = useState(false);
  const [apiError, setApiError] = useState('');

  const handleChange = (e) => {
    setForm(prev => ({ ...prev, [e.target.name]: e.target.value }));
    setErrors(prev => ({ ...prev, [e.target.name]: '' }));
    setApiError('');
  };

  const validate = () => {
    const errs = {};
    if (!form.fullName.trim())            errs.fullName = 'Full name is required.';
    if (!form.email.trim())               errs.email    = 'Email is required.';
    else if (!/\S+@\S+\.\S+/.test(form.email)) errs.email = 'Enter a valid email.';
    if (form.password.length < 8)         errs.password = 'Password must be at least 8 characters.';
    if (form.password !== form.confirm)   errs.confirm  = 'Passwords do not match.';
    return errs;
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    const errs = validate();
    if (Object.keys(errs).length > 0) { setErrors(errs); return; }

    setLoading(true);
    try {
      await signup(form.fullName, form.email, form.password);
      // AuthContext updates → App re-renders to main view
    } catch (err) {
      setApiError(err.message || 'Signup failed. Please try again.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <div className="auth-page">
      {/* Left brand panel */}
      <div className="auth-brand-panel">
        <div className="auth-brand-content">
          <div className="auth-brand-icon">🎓</div>
          <h1 className="auth-brand-title">AI Teaching<br />Assistant</h1>
          <p className="auth-brand-subtitle">
            Join thousands of students studying smarter with AI-powered lecture summaries.
          </p>
          <div className="auth-features">
            <div className="auth-feature-item">✅ Free to get started</div>
            <div className="auth-feature-item">📄 Upload any PDF lecture</div>
            <div className="auth-feature-item">🧠 AI extracts what matters</div>
            <div className="auth-feature-item">🔒 Your data stays private</div>
          </div>
        </div>
        <div className="auth-brand-blob blob-1" />
        <div className="auth-brand-blob blob-2" />
      </div>

      {/* Right form panel */}
      <div className="auth-form-panel">
        <div className="auth-form-card">
          <div className="auth-form-header">
            <h2>Create your account</h2>
            <p>Get started — it only takes a minute</p>
          </div>

          <form className="auth-form" onSubmit={handleSubmit}>

            <div className="auth-field">
              <label htmlFor="fullName">Full name</label>
              <input
                id="fullName"
                name="fullName"
                type="text"
                autoComplete="name"
                placeholder="Jane Smith"
                value={form.fullName}
                onChange={handleChange}
                disabled={loading}
                className={errors.fullName ? 'input-error' : ''}
              />
              {errors.fullName && <span className="field-error">{errors.fullName}</span>}
            </div>

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
                className={errors.email ? 'input-error' : ''}
              />
              {errors.email && <span className="field-error">{errors.email}</span>}
            </div>

            <div className="auth-field">
              <label htmlFor="password">Password</label>
              <input
                id="password"
                name="password"
                type="password"
                autoComplete="new-password"
                placeholder="Min. 8 characters"
                value={form.password}
                onChange={handleChange}
                disabled={loading}
                className={errors.password ? 'input-error' : ''}
              />
              {errors.password && <span className="field-error">{errors.password}</span>}
              {/* Password strength bar */}
              {form.password && (
                <PasswordStrength password={form.password} />
              )}
            </div>

            <div className="auth-field">
              <label htmlFor="confirm">Confirm password</label>
              <input
                id="confirm"
                name="confirm"
                type="password"
                autoComplete="new-password"
                placeholder="Repeat your password"
                value={form.confirm}
                onChange={handleChange}
                disabled={loading}
                className={errors.confirm ? 'input-error' : ''}
              />
              {errors.confirm && <span className="field-error">{errors.confirm}</span>}
            </div>

            {apiError && (
              <div className="auth-error">
                <span>⚠️</span> {apiError}
              </div>
            )}

            <button
              type="submit"
              className="auth-submit-btn"
              disabled={loading}
            >
              {loading ? (
                <><span className="btn-spinner" /> Creating account…</>
              ) : (
                'Create account →'
              )}
            </button>
          </form>

          <div className="auth-switch">
            Already have an account?{' '}
            <button onClick={onNavigateLogin} className="auth-link-btn">
              Sign in
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

// ── Password Strength Indicator ───────────────────────────────────────────

function PasswordStrength({ password }) {
  const getStrength = (p) => {
    let score = 0;
    if (p.length >= 8)  score++;
    if (p.length >= 12) score++;
    if (/[A-Z]/.test(p)) score++;
    if (/[0-9]/.test(p)) score++;
    if (/[^A-Za-z0-9]/.test(p)) score++;
    return score;
  };

  const score  = getStrength(password);
  const labels = ['', 'Weak', 'Fair', 'Good', 'Strong', 'Very strong'];
  const colors = ['', '#ef4444', '#f59e0b', '#3b82f6', '#10b981', '#059669'];

  return (
    <div className="password-strength">
      <div className="strength-bars">
        {[1,2,3,4,5].map(i => (
          <div
            key={i}
            className="strength-bar"
            style={{ background: i <= score ? colors[score] : '#e5e7eb' }}
          />
        ))}
      </div>
      <span className="strength-label" style={{ color: colors[score] }}>
        {labels[score]}
      </span>
    </div>
  );
}

export default SignupPage;