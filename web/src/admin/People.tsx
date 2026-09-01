import { useState, type FormEvent } from 'react';
import { failureFrom, identity, type ApiFailure } from '../shared/api/client.ts';
import { ErrorState } from '../shared/state/States.tsx';

type Invitation = {
  userId: string;
  email: string;
  displayName: string;
  token: string;
  expiresAt: string;
};

/**
 * Inviting somebody, against the real endpoint (T-1.9).
 *
 * <p>The console's first screen is a write rather than a list on purpose: a read proves the
 * client can fetch, and a write proves the whole path — a body the generated types agree with, a
 * permission check, and a response that must be handled correctly rather than merely rendered.
 *
 * <p>The token is shown once and said to be shown once, because that is true: the service keeps
 * only its hash (ADR-0104's neighbour, T-1.9), so a screen that implies it can be looked up later
 * is a screen that will cause a support ticket nobody can answer.
 */
export function People() {
  const [email, setEmail] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [invitation, setInvitation] = useState<Invitation | null>(null);
  const [failure, setFailure] = useState<ApiFailure | null>(null);
  const [sending, setSending] = useState(false);

  async function invite(event: FormEvent) {
    event.preventDefault();
    setSending(true);
    setFailure(null);
    setInvitation(null);
    try {
      const { data, response, error } = await identity.POST('/api/v1/users/invitations', {
        body: { email, displayName },
      });
      if (data) {
        setInvitation(data as Invitation);
        setEmail('');
        setDisplayName('');
      } else {
        setFailure(failureFrom(response, error));
      }
    } catch (unreachable: unknown) {
      setFailure(failureFrom(undefined, unreachable));
    } finally {
      setSending(false);
    }
  }

  return (
    <>
      <h1>People</h1>
      <form onSubmit={invite}>
        <p>
          <label htmlFor="invite-email">Email address</label>
          <input
            id="invite-email"
            type="email"
            required
            value={email}
            onChange={(event) => setEmail(event.target.value)}
          />
        </p>
        <p>
          <label htmlFor="invite-name">Display name</label>
          <input
            id="invite-name"
            type="text"
            required
            value={displayName}
            onChange={(event) => setDisplayName(event.target.value)}
          />
        </p>
        <button type="submit" disabled={sending}>
          {sending ? 'Inviting…' : 'Invite'}
        </button>
      </form>

      {failure ? <ErrorState message={failure.message} /> : null}

      {invitation ? (
        <div className="state" role="status" aria-live="polite">
          <p>
            Invited <strong>{invitation.displayName}</strong> ({invitation.email}). The invitation
            expires {new Date(invitation.expiresAt).toLocaleString()}.
          </p>
          <p>
            <strong>This link is shown once.</strong> We keep only a hash of it, so it cannot be
            looked up again — send it now, or invite them again to issue a new one.
          </p>
          <code>{invitation.token}</code>
        </div>
      ) : null}
    </>
  );
}
