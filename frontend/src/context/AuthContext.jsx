import { createContext, useContext, useState, useEffect, useCallback, useRef } from 'react';

const AuthContext = createContext(null);

const STORAGE_KEYS = {
  ACCESS_TOKEN:  'ta_access_token',
  REFRESH_TOKEN: 'ta_refresh_token',
  USER:          'ta_user',
  ACCESS_EXP:    'ta_access_exp',
};

/**
 * AuthProvider manages:
 *  - Login / signup / logout state
 *  - Access token storage in memory (sessionStorage)
 *  - Refresh token storage (localStorage for persistence)
 *  - Automatic silent token refresh before access token expiry
 */
export function AuthProvider({ children }) {
  const [user,          setUser]          = useState(null);
  const [accessToken,   setAccessToken]   = useState(null);
  const [isLoading,     setIsLoading]     = useState(true);  // initial hydration
  const refreshTimerRef = useRef(null);

  // ── Helpers ─────────────────────────────────────────────────────────────

  const clearAuth = useCallback(() => {
    setUser(null);
    setAccessToken(null);
    localStorage.removeItem(STORAGE_KEYS.REFRESH_TOKEN);
    sessionStorage.removeItem(STORAGE_KEYS.ACCESS_TOKEN);
    sessionStorage.removeItem(STORAGE_KEYS.USER);
    sessionStorage.removeItem(STORAGE_KEYS.ACCESS_EXP);
    if (refreshTimerRef.current) clearTimeout(refreshTimerRef.current);
  }, []);

  const persistAuth = useCallback((authResponse) => {
    const { accessToken, refreshToken, accessExpiresIn, email, fullName } = authResponse;
    const userData = { email, fullName };

    setAccessToken(accessToken);
    setUser(userData);

    // Access token in sessionStorage (cleared when tab closes)
    sessionStorage.setItem(STORAGE_KEYS.ACCESS_TOKEN, accessToken);
    sessionStorage.setItem(STORAGE_KEYS.USER, JSON.stringify(userData));
    sessionStorage.setItem(STORAGE_KEYS.ACCESS_EXP, String(Date.now() + accessExpiresIn));

    // Refresh token in localStorage (persists across tabs/sessions)
    localStorage.setItem(STORAGE_KEYS.REFRESH_TOKEN, refreshToken);

    // Schedule silent refresh 1 minute before access token expires
    scheduleTokenRefresh(accessExpiresIn);
  }, []);

  // ── Silent Refresh ───────────────────────────────────────────────────────

  const doRefresh = useCallback(async () => {
    const storedRefresh = localStorage.getItem(STORAGE_KEYS.REFRESH_TOKEN);
    if (!storedRefresh) { clearAuth(); return; }

    try {
      const res = await fetch('/api/auth/refresh', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ refreshToken: storedRefresh }),
      });

      if (!res.ok) throw new Error('Refresh failed');

      const data = await res.json();
      persistAuth(data);
    } catch {
      clearAuth();   // force re-login if refresh fails
    }
  }, [clearAuth, persistAuth]);

  const scheduleTokenRefresh = useCallback((expiresInMs) => {
    if (refreshTimerRef.current) clearTimeout(refreshTimerRef.current);
    const delay = Math.max(expiresInMs - 60_000, 0);  // 1 minute before expiry
    refreshTimerRef.current = setTimeout(doRefresh, delay);
  }, [doRefresh]);

  // ── Hydration (page reload) ──────────────────────────────────────────────

  useEffect(() => {
    const hydrate = async () => {
      const storedToken = sessionStorage.getItem(STORAGE_KEYS.ACCESS_TOKEN);
      const storedUser  = sessionStorage.getItem(STORAGE_KEYS.USER);
      const storedExp   = sessionStorage.getItem(STORAGE_KEYS.ACCESS_EXP);

      if (storedToken && storedUser && storedExp) {
        const msLeft = Number(storedExp) - Date.now();
        if (msLeft > 5000) {
          // Token still valid — restore state
          setAccessToken(storedToken);
          setUser(JSON.parse(storedUser));
          scheduleTokenRefresh(msLeft);
        } else {
          // Token expired — try refresh
          await doRefresh();
        }
      } else {
        // No session — try refresh from localStorage refresh token
        const storedRefresh = localStorage.getItem(STORAGE_KEYS.REFRESH_TOKEN);
        if (storedRefresh) await doRefresh();
      }

      setIsLoading(false);
    };

    hydrate();
    return () => { if (refreshTimerRef.current) clearTimeout(refreshTimerRef.current); };
  }, [doRefresh, scheduleTokenRefresh]);

  // ── Public API ───────────────────────────────────────────────────────────

  const login = async (email, password) => {
    const res = await fetch('/api/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ email, password }),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || 'Login failed');
    persistAuth(data);
    return data;
  };

  const signup = async (fullName, email, password) => {
    const res = await fetch('/api/auth/signup', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ fullName, email, password }),
    });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || 'Signup failed');
    persistAuth(data);
    return data;
  };

  const logout = () => clearAuth();

  const value = {
    user,
    accessToken,
    isLoading,
    isAuthenticated: !!accessToken,
    login,
    signup,
    logout,
  };

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const ctx = useContext(AuthContext);
  if (!ctx) throw new Error('useAuth must be used inside <AuthProvider>');
  return ctx;
}