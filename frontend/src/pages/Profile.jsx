import { CalendarDays, Shield, Target, UserRound } from 'lucide-react';
import { useState } from 'react';
import { useNavigate, useParams } from 'react-router-dom';
import { api, message } from '../api';
import { Avatar, dateTime, ErrorNotice, Metric, PageTitle, Resource, SectionTitle } from '../components/ui';
import { useApp, useAuth } from '../context';
import { useResource } from '../hooks';
import { UserTabs } from './Dashboard';

export function PublicProfile() {
  const { username } = useParams();
  const state = useResource('/users/' + encodeURIComponent(username));
  return <div className="container page"><Resource state={state}>{(profile) =>
    <article className="panel padded profile-card">
      <Avatar username={profile.username} size="large" />
      <PageTitle eyebrow="COMPETITOR PROFILE" title={profile.fullName} description={'@' + profile.username} />
      {profile.bio && <p className="profile-bio">{profile.bio}</p>}
      <div className="card-grid two">
        <Metric label="Problems solved" value={profile.solved} icon={Target} />
        <Metric label="Member since" value={new Date(profile.joinedAt).toLocaleDateString()} icon={CalendarDays} />
      </div>
    </article>}</Resource></div>;
}

export default function Profile() {
  const { user, setUser, endSession } = useAuth();
  const { notify } = useApp();
  const navigate = useNavigate();
  const [pending, setPending] = useState('');
  const [error, setError] = useState(null);

  async function update(event, type) {
    event.preventDefault();
    const fields = Object.fromEntries(new FormData(event.currentTarget));
    setError(null);
    setPending(type);
    try {
      if (type === 'profile') {
        const { data } = await api.put('/users/me', fields);
        setUser(data);
        notify('Profile updated.');
      } else {
        if (fields.newPassword !== fields.confirmPassword) throw new Error('The passwords do not match.');
        await api.put('/users/me/password', { currentPassword: fields.currentPassword, newPassword: fields.newPassword });
        endSession();
        notify('Password changed. Log in again.');
        navigate('/login', { replace: true });
      }
    } catch (failure) { setError({ type, text: message(failure) }); }
    finally { setPending(''); }
  }

  return <div className="container page">
    <PageTitle eyebrow="MY ARENA" title="My profile" description={'@' + user.username + ' · Joined ' + dateTime(user.createdAt)} />
    <UserTabs />
    <div className="card-grid two">
      <section className="panel padded"><div className="feature-icon"><UserRound size={20} /></div><SectionTitle title="Profile details" />
        <form className="form-stack" onSubmit={(event) => update(event, 'profile')}>
          <label>Full name<input name="fullName" autoComplete="name" defaultValue={user.fullName} required maxLength={80} /></label>
          <label>Bio<textarea name="bio" rows={4} defaultValue={user.bio} maxLength={280} /></label>
          <label>Email<input name="email" value={user.email} readOnly /></label>
          {error?.type === 'profile' && <ErrorNotice text={error.text} />}
          <button type="submit" className="btn primary" disabled={Boolean(pending)}>{pending === 'profile' ? 'Saving…' : 'Save profile'}</button>
        </form>
      </section>
      <section className="panel padded"><div className="feature-icon"><Shield size={20} /></div><SectionTitle title="Change password" />
        <form className="form-stack" onSubmit={(event) => update(event, 'password')}>
          <label>Current password<input name="currentPassword" type="password" autoComplete="current-password" required maxLength={72} /></label>
          <label>New password<input name="newPassword" type="password" autoComplete="new-password" required minLength={8} maxLength={72} /></label>
          <label>Confirm new password<input name="confirmPassword" type="password" autoComplete="new-password" required minLength={8} maxLength={72} /></label>
          {error?.type === 'password' && <ErrorNotice text={error.text} />}
          <button type="submit" className="btn secondary" disabled={Boolean(pending)}>{pending === 'password' ? 'Saving…' : 'Change password'}</button>
        </form>
      </section>
    </div>
  </div>;
}
