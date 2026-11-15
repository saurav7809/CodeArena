import { createContext, useContext, useEffect, useState } from 'react';
import { api, hasToken, setToken } from './api';
import { storage } from './storage';

const AuthContext = createContext(null);
const AppContext = createContext(null);

export function Providers({ children }) {
  const [user, setUser] = useState(null);
  const [authLoading, setAuthLoading] = useState(hasToken);
  const [theme, setTheme] = useState(() => storage.local.get('arena-theme') === 'light' ? 'light' : 'dark');
  const [toast, setToast] = useState(null);

  useEffect(() => {
    function clearSession() { setUser(null); }
    window.addEventListener('arena:session-expired', clearSession);
    return () => window.removeEventListener('arena:session-expired', clearSession);
  }, []);

  useEffect(() => {
    if (!hasToken()) return;
    const controller = new AbortController();
    api.get('/auth/me', { signal: controller.signal })
      .then(({ data }) => setUser(data))
      .catch((error) => { if (error.code !== 'ERR_CANCELED') setUser(null); })
      .finally(() => { if (!controller.signal.aborted) setAuthLoading(false); });
    return () => controller.abort();
  }, []);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    storage.local.set('arena-theme', theme);
  }, [theme]);

  useEffect(() => {
    if (!toast) return;
    const timer = setTimeout(() => setToast(null), 4500);
    return () => clearTimeout(timer);
  }, [toast]);

  async function authenticate(mode, fields) {
    const { data } = await api.post('/auth/' + mode, fields);
    setToken(data.token);
    setUser(data.user);
    return data.user;
  }

  function endSession() {
    setToken(null);
    setUser(null);
  }

  async function signOut() {
    try {
      await api.post('/auth/logout');
    } finally {
      endSession();
    }
  }

  return <AuthContext.Provider value={{ user, setUser, authLoading, authenticate, endSession, signOut }}>
    <AppContext.Provider value={{
      theme, toggleTheme: () => setTheme(theme === 'dark' ? 'light' : 'dark'),
      toast, dismissToast: () => setToast(null), notify: (text, kind = 'success') => setToast({ text, kind }),
    }}>{children}</AppContext.Provider>
  </AuthContext.Provider>;
}

export function useAuth() { return useContext(AuthContext); }
export function useApp() { return useContext(AppContext); }
