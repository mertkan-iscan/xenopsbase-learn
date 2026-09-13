import { GraduationCap } from 'lucide-react';
import { Link, useLocation } from 'react-router';
import { rememberTheAttempt } from '../shared/auth/arrival.ts';
import { hasParkedWork } from '../shared/auth/recovery.ts';
import { signIn } from '../shared/auth/session.ts';
import { useSession } from '../shared/auth/useSession.ts';
import { Button, buttonClasses } from '../shared/design/Button.tsx';
import type { MessageKey } from '../shared/i18n/messages.en.ts';
import { useT } from '../shared/i18n/useLocale.ts';
import { Loading } from '../shared/state/States.tsx';
import { Appearance } from '../shared/theme/Appearance.tsx';

/**
 * The sign-in screen (T-10.9).
 *
 * <h2>What this is, given that there is no password field</h2>
 *
 * <p>Authentication happens at the company's identity provider, and the gateway holds the session
 * (T-10.2). Nothing on this page collects a credential, and nothing on it could: there is no token
 * in this browser by design, because this same application renders uploaded SCORM packages and
 * third-party embeds, and a token in a variable here is a token those can reach.
 *
 * <p>So the page is the door rather than the lock. It is the address a person can be sent, the
 * screen they land on after signing out, and the one place where somebody who cannot get in is
 * told what to do about it. It is also, deliberately, usable before anybody is signed in: the
 * appearance and language controls are here, because a person who cannot read the interface
 * cannot be asked to sign in first in order to change it.
 *
 * <h2>It does not bounce anybody to the issuer on its own</h2>
 *
 * <p>That decision stays exactly where it was, in `arrival.ts` and the shell: an ordinary
 * first-time visitor with no session is sent straight to the issuer and never sees this page,
 * because a panel telling somebody they are signed out — which they know — with one button doing
 * the only available thing is a click nobody chose to make.
 *
 * <p>Somebody who arrives HERE has either been sent by the shell with a reason worth saying, or
 * typed the address. Neither should be redirected away from a page they are looking at, so this
 * one never navigates by itself. The reason travels in the router's state, because
 * `arrivalFor` clears the "signed out on purpose" flag as it reads it — asking twice gets a
 * different answer, and the shell asked first.
 */
const reasons: Record<
  'parked-work' | 'signed-out' | 'came-back-signed-out',
  { title: MessageKey; body: MessageKey }
> = {
  'parked-work': { title: 'signed-out.parked.title', body: 'signed-out.parked.body' },
  'signed-out': { title: 'signed-out.deliberate.title', body: 'signed-out.deliberate.body' },
  'came-back-signed-out': { title: 'signed-out.failed.title', body: 'signed-out.failed.body' },
};

/** What the shell hands over when it sends somebody here. */
export type LoginReason = keyof typeof reasons;

export function Login() {
  const t = useT();
  const session = useSession();
  const { state } = useLocation();
  const handed = (state as { because?: LoginReason } | null)?.because;

  /*
   * THE REASON COMES FROM THE SHELL, AND THIS PAGE NEVER ASKS FOR IT ITSELF.
   *
   * `arrivalFor` is not a pure read: it consumes the "signed out on purpose" flag as it looks at
   * it, so a second caller gets a different answer from the first. The shell has already asked by
   * the time it sends anybody here, and asking again would find the flag gone and quietly
   * downgrade a deliberate sign-out into a generic prompt — a difference the whole of `arrival.ts`
   * exists to preserve.
   *
   * The one thing this page does check for itself is parked work, because `hasParkedWork` is a
   * pure read (that is why it is separate from `takeParkedWork`) and because it is the case that
   * survives a reload: a learner whose exam answers are held must be told they are safe every time
   * they look at this page, not only on the render that redirected them here.
   */
  const because: LoginReason | undefined = handed ?? (hasParkedWork() ? 'parked-work' : undefined);

  return (
    <main
      id="main"
      className="mx-auto flex min-h-dvh w-full max-w-lg flex-col justify-center gap-6 px-4 py-10 sm:px-6"
    >
      <div className="rise flex flex-col gap-6">
        <div className="flex items-center gap-2.5">
          <GraduationCap
            aria-hidden="true"
            className="size-6 shrink-0 text-brand"
            strokeWidth={2.25}
          />
          <span className="font-display text-base font-bold tracking-tight">
            {t('shell.product')}
          </span>
        </div>

        <section aria-labelledby="sign-in" className="card flex flex-col gap-4 p-6">
          <h1 id="sign-in" className="font-display text-xl font-bold">
            {/*
             * The reason takes the heading when there is one. "You are signed out" is what the
             * person needs to read first if that is what happened; "Sign in to XenOpsBase Learn"
             * is what they need if it is not.
             */}
            {t(because ? reasons[because].title : 'login.heading')}
          </h1>
          <p className="text-sm text-muted">{t(because ? reasons[because].body : 'login.body')}</p>

          {session === null ? (
            <Loading what="loading.session" />
          ) : session.signedIn ? (
            <>
              {/*
               * Somebody who is already signed in and has landed here -- a bookmarked /login, a
               * second tab. Told plainly and given the way onward, rather than redirected: a
               * redirect from a page they deliberately opened looks like the link is broken.
               */}
              <p className="text-sm text-muted">{t('login.already')}</p>
              <Link to="/" className={buttonClasses('primary')}>
                {t('login.continue')}
              </Link>
            </>
          ) : (
            <>
              <Button
                voice="primary"
                onClick={() => {
                  // The loop guard's memory, set immediately before leaving. Without it, a
                  // return from the issuer that is still signed out sends the browser back
                  // again, which reads to a person as the site being broken.
                  rememberTheAttempt();
                  signIn(session);
                }}
              >
                {t('login.button')}
              </Button>
              <p className="text-xs text-muted">{t('login.sso')}</p>
            </>
          )}
        </section>

        {/*
         * ON THE SIGNED-OUT PAGE ON PURPOSE. Somebody whose browser guessed a language they do not
         * read, or who needs the light theme to read anything at all, cannot be told to sign in
         * first and change it afterwards. Signed out these are kept in this browser; signed in
         * they are saved to the account -- the panel says which.
         */}
        <section aria-labelledby="appearance" className="card flex flex-col gap-4 p-6">
          <h2 id="appearance" className="font-display text-base font-bold">
            {t('prefs.title')}
          </h2>
          <Appearance signedIn={session?.signedIn === true} />
        </section>

        <p className="text-xs text-subtle">{t('login.trouble')}</p>
      </div>
    </main>
  );
}
