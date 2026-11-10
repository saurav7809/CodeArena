import { Shield } from 'lucide-react';
import { Link, Navigate, Outlet, useLocation } from 'react-router-dom';
import { useAuth } from '../context';
import { Empty, Loading } from './ui';

export default function AccessGate({ admin = false }) {
  const { user, authLoading } = useAuth();
  const location = useLocation();
  if (authLoading) return <div className="container"><Loading text="Checking your session…" /></div>;
  if (!user) return <Navigate to="/login" replace state={{ from: location.pathname + location.search }} />;
  if (admin && user.role !== 'ADMIN') return <div className="container"><Empty icon={Shield} title="Administrator access required">
    <Link className="btn secondary" to="/dashboard">Go to dashboard</Link></Empty></div>;
  return <Outlet />;
}
