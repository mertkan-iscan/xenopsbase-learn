import { useState, type FormEvent } from 'react';
import { failureFrom, identity, type ApiFailure } from '../shared/api/client.ts';
import { formatMoment } from '../shared/i18n/format.ts';
import { useLocale } from '../shared/i18n/useLocale.ts';
import { Button } from '../shared/design/Button.tsx';
import { Input } from '../shared/design/Field.tsx';
import { Card } from '../shared/design/Surface.tsx';
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
  const { locale, t } = useLocale();
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
    /*
     * THIS SCREEN HAD NO STYLING AT ALL before the redesign -- bare `h1`, `p`, `label`, `input`,
     * `button`, with not one class on any of them. It worked, and it looked like a form somebody
     * had not finished. Worth recording, because it is the reason `Input` requires its label: an
     * unlabelled field is invisible in a screenshot and this screen proves how long that survives.
     */
    <div className="mx-auto flex max-w-2xl flex-col gap-6">
      <h1 className="font-display text-2xl font-bold">{t('people.title')}</h1>

      <Card className="p-5">
        <form onSubmit={invite} className="flex flex-col gap-4">
          <Input
            label={t('people.email')}
            type="email"
            required
            value={email}
            onChange={(event) => setEmail(event.target.value)}
          />
          <Input
            label={t('people.name')}
            type="text"
            required
            value={displayName}
            onChange={(event) => setDisplayName(event.target.value)}
          />
          <Button type="submit" voice="primary" size="sm" disabled={sending} className="self-start">
            {sending ? t('people.inviting') : t('people.invite')}
          </Button>
        </form>
      </Card>

      {failure ? <ErrorState message={failure.message} /> : null}

      {invitation ? (
        /*
         * `role="status"` with a polite live region: the token appears without a navigation, and
         * an administrator using a screen reader would otherwise have no idea the invitation had
         * succeeded -- let alone that the one thing they must copy is now on screen.
         */
        <div
          role="status"
          aria-live="polite"
          className="flex flex-col gap-3 rounded-xl border border-passed-edge bg-passed-bg p-5"
        >
          <p className="text-sm">
            {t('people.invited', {
              name: invitation.displayName,
              email: invitation.email,
              at: formatMoment(locale, invitation.expiresAt),
            })}
          </p>
          {/*
           * SHOWN ONCE, AND SAID TO BE SHOWN ONCE, because that is true: the service keeps only
           * the hash. The sentence is weighted rather than a footnote -- an administrator who
           * navigates away assuming they can look it up later has to re-invite the person.
           */}
          <p className="text-sm font-semibold">
            {t('people.once.title')} <span className="font-normal">{t('people.once.body')}</span>
          </p>
          <code className="scroll-x block rounded-lg border border-hairline bg-surface p-3 font-mono text-xs">
            {invitation.token}
          </code>
        </div>
      ) : null}
    </div>
  );
}
