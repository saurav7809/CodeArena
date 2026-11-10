import { Braces, CheckCircle2, CircleAlert, LogOut, Menu, Moon, Sun, X } from 'lucide-react';
import { useEffect, useState } from 'react';
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom';
import { useApp, useAuth } from '../context';
import { Avatar } from './ui';

export function Brand() {
  return <Link to="/" className="brand" aria-label="CodeArena home">
    <span className="brand-mark"><Braces size={21} strokeWidth={2.4} /></span>
    <span>Code<span className="brand-accent">Arena</span></span>
  </Link>;
}

export default function Layout() {
  const { user, authLoading, signOut } = useAuth();
  const { theme, toggleTheme, toast, dismissToast, notify } = useApp();
  const [menu, setMenu] = useState(false);
  const location = useLocation();
  const navigate = useNavigate();
  useEffect(() => { window.scrollTo(0, 0); setMenu(false); }, [location.pathname]);

  async function logout() {
    try { await signOut(); }
    catch { notify('Signed out on this device. The server could not be reached.', 'warning'); }
    navigate('/');
  }

  return <>
    <a href="#main-content" className="skip-link">Skip to content</a>
    <header className="topbar"><div className="topbar-inner">
      <Brand />
      <button className="icon-button mobile-menu" type="button" onClick={() => setMenu(!menu)}
        aria-label={menu ? 'Close navigation' : 'Open navigation'} aria-expanded={menu}>{menu ? <X size={20} /> : <Menu size={20} />}</button>
      <nav className={'nav-links ' + (menu ? 'open' : '')} aria-label="Main navigation">
        <NavLink to="/" end>Home</NavLink><NavLink to="/problems">Problems</NavLink>
        <NavLink to="/contests">Contests</NavLink><NavLink to="/leaderboard">Leaderboard</NavLink>
        {user && <NavLink to="/dashboard">Dashboard</NavLink>}
        {user?.role === 'ADMIN' && <NavLink to="/admin">Admin</NavLink>}
      </nav>
      <div className="nav-actions">
        <button className="icon-button theme-switch" type="button" onClick={toggleTheme}
          aria-label={theme === 'dark' ? 'Switch to light theme' : 'Switch to dark theme'}>
          {theme === 'dark' ? <Sun size={17} /> : <Moon size={17} />}
        </button>
        {user ? <>
          <Link to="/profile" className="profile-shortcut" aria-label="My profile"><Avatar username={user.username} size="small" /><span>{user.username}</span></Link>
          <button type="button" className="icon-button" onClick={logout} aria-label="Sign out" title="Sign out"><LogOut size={17} /></button>
        </> : !authLoading && <>
          <Link to="/login" className="login-link">Log in</Link>
          <Link to="/register" className="btn primary small">Sign up</Link>
        </>}
      </div>
    </div></header>
    <main id="main-content"><Outlet /></main>
    <footer className="footer"><div className="footer-inner">
      <div><Brand /><p>Practice with purpose. Compete with confidence.</p></div>
      <nav aria-label="Footer navigation"><Link to="/problems">Problems</Link><Link to="/contests">Contests</Link>
        <Link to="/leaderboard">Leaderboard</Link><a href="https://github.com/topics/competitive-programming" target="_blank" rel="noreferrer">GitHub community</a></nav>
    </div></footer>
    {toast && <div className={'toast ' + toast.kind} role="status">
      {toast.kind === 'success' ? <CheckCircle2 size={19} /> : <CircleAlert size={19} />}
      <span>{toast.text}</span><button className="icon-button" type="button" aria-label="Dismiss notification" onClick={dismissToast}><X size={16} /></button>
    </div>}
  </>;
}
