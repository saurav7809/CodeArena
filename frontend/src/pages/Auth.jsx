import { ArrowRight, Eye, EyeOff, LockKeyhole } from 'lucide-react';
import { useState } from 'react';
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom';
import { message } from '../api';
import { useApp, useAuth } from '../context';
import { ErrorNotice, Loading } from '../components/ui';

export default function Auth({ mode }) {
  const registering = mode === 'register';
  const { user, authLoading, authenticate } = useAuth();
  const { notify } = useApp();
  const location = useLocation();
  const navigate = useNavigate();
  const [visible, setVisible] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const target = location.state?.from;
  const destination = typeof target === 'string' && target.startsWith('/') && !target.startsWith('//') ? target : '/dashboard';
  if (authLoading) return <div className="container"><Loading /></div>;
  if (user) return <Navigate to={destination} replace />;

  async function submit(event) {
    event.preventDefault();
    const fields = Object.fromEntries(new FormData(event.currentTarget));
    if (registering && fields.password !== fields.confirmPassword) {
      setError('The passwords do not match.');
      return;
    }
    setBusy(true);
    setError('');
    try {
      await authenticate(mode, fields);
      notify(registering ? 'Your account is ready.' : 'Signed in.');
      navigate(destination, { replace: true });
    } catch (failure) {
      setError(message(failure));
    } finally {
      setBusy(false);
    }
  }

  return <div className="container auth-shell"><section className="panel auth-card">
    <div className="feature-icon"><LockKeyhole size={22} /></div>
    <p className="eyebrow">CODEARENA ACCOUNT</p>
    <h1>{registering ? 'Create your account' : 'Welcome back'}</h1>
    <p>{registering ? 'Save submissions, join contests, and track your progress.' : 'Pick up where you left off.'}</p>
    <form className="form-stack" onSubmit={submit}>
      {registering ? <>
        <label>Full name<input name="fullName" autoComplete="name" maxLength={80} required /></label>
        <label>Username<input name="username" autoComplete="username" pattern="[A-Za-z0-9_]{3,24}" minLength={3} maxLength={24} required /></label>
        <label>Email<input name="email" type="email" autoComplete="email" maxLength={254} required /></label>
      </> : <label>Email or username<input name="identifier" autoComplete="username" maxLength={254} required /></label>}
      <label>Password<span className="password-field">
        <input name="password" type={visible ? 'text' : 'password'} autoComplete={registering ? 'new-password' : 'current-password'}
          minLength={registering ? 8 : 1} maxLength={72} required />
        <button className="icon-button" type="button" aria-label={visible ? 'Hide password' : 'Show password'} onClick={() => setVisible(!visible)}>
          {visible ? <EyeOff size={17} /> : <Eye size={17} />}
        </button>
      </span></label>
      {registering && <label>Confirm password<input name="confirmPassword" type="password" autoComplete="new-password" minLength={8} maxLength={72} required /></label>}
      {error && <ErrorNotice text={error} />}
      <button type="submit" className="btn primary" disabled={busy}>{busy ? 'Please wait…' : registering ? 'Create account' : 'Log in'} <ArrowRight size={16} /></button>
    </form>
    <p className="auth-switch">{registering ? 'Already have an account?' : 'New to CodeArena?'}{' '}
      <Link to={registering ? '/login' : '/register'} state={location.state}>{registering ? 'Log in' : 'Sign up'}</Link></p>
  </section></div>;
}
