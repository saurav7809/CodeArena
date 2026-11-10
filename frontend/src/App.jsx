import { Code2 } from 'lucide-react';
import { lazy, Suspense } from 'react';
import { Link, Route, Routes } from 'react-router-dom';
import AccessGate from './components/AccessGate';
import Layout from './components/Layout';
import { Empty, Loading } from './components/ui';
import Auth from './pages/Auth';
import Contests, { ContestDetail } from './pages/Contests';
import Home from './pages/Home';
import Problems from './pages/Problems';
import Rankings from './pages/Rankings';
import Submissions, { SubmissionDetail } from './pages/Submissions';

const Workspace = lazy(() => import('./pages/Workspace'));
const Dashboard = lazy(() => import('./pages/Dashboard'));
const Profile = lazy(() => import('./pages/Profile'));
const PublicProfile = lazy(() => import('./pages/Profile').then((module) => ({ default: module.PublicProfile })));
const Admin = lazy(() => import('./pages/Admin'));

export default function App() {
  return <Suspense fallback={<div className="container"><Loading /></div>}>
    <Routes><Route element={<Layout />}>
      <Route index element={<Home />} />
      <Route path="login" element={<Auth key="login" mode="login" />} />
      <Route path="register" element={<Auth key="register" mode="register" />} />
      <Route path="problems" element={<Problems />} />
      <Route path="problems/:slug" element={<Workspace />} />
      <Route path="contests" element={<Contests />} />
      <Route path="contests/:id" element={<ContestDetail />} />
      <Route path="leaderboard" element={<Rankings />} />
      <Route path="users/:username" element={<PublicProfile />} />
      <Route element={<AccessGate />}>
        <Route path="dashboard" element={<Dashboard />} />
        <Route path="analytics" element={<Dashboard section="analytics" />} />
        <Route path="achievements" element={<Dashboard section="achievements" />} />
        <Route path="profile" element={<Profile />} />
        <Route path="profile/edit" element={<Profile />} />
        <Route path="submissions" element={<Submissions />} />
        <Route path="submissions/:id" element={<SubmissionDetail />} />
        <Route path="contests/:id/problems/:slug" element={<Workspace />} />
      </Route>
      <Route element={<AccessGate admin />}><Route path="admin/*" element={<Admin />} /></Route>
      <Route path="*" element={<div className="container page"><Empty icon={Code2} title="Page not found">
        <Link to="/" className="btn secondary">Return home</Link></Empty></div>} />
    </Route></Routes>
  </Suspense>;
}
