import { useEffect } from 'react';
import { NavLink, Outlet, useLocation } from 'react-router';
import { arrivalFor, forgetTheAttempt, rememberTheAttempt } from '../shared/auth/arrival.ts';
import { signIn, signOut } from '../shared/auth/session.ts';
import { useMe } from '../shared/auth/useMe.ts';
import { useSession } from '../shared/auth/useSession.ts';
import { localeFrom } from '../shared/i18n/locales.ts';
import type { MessageKey } from '../shared/i18n/messages.en.ts';
import { adoptServerLocale } from '../shared/i18n/locale.ts';
import { useT } from '../shared/i18n/useLocale.ts';
import { Loading } from '../shared/state/States.tsx';

/**
 * The two sentences a signed-out arrival can be met with, keyed by why they are here.
 *
 * <p>A lookup rather than three ternaries in the markup: `arrival.because` is a closed set, and
 * this is where a fourth reason would have to be given its words before it could be rendered.
 */
const arrivalWords: Record<
  'parked-work' | 'signed-out' | 'came-back-signed-out',
  { title: MessageKey; body: MessageKey }
> = {
  'parked-work': { title: 'signed-out.parked.title', body: 'signed-out.parked.body' },
  // A deliberate sign-out. Said plainly, because the alternative -- bouncing straight back to the
  // issuer -- signs the person in again without a form and makes the button they just pressed
  // look broken.
  'signed-out': { title: 'signed-out.deliberate.title', body: 'signed-out.deliberate.body' },
  'came-back-signed-out': { title: 'signed-out.failed.title', body: 'signed-out.failed.body' },
};

/**
 * The frame both route trees sit in (T-10.1), and the one place that knows whether anybody is
 * signed in (T-10.2).
 *
 * One application, two trees: `/` is the learner app and `/admin` is the console. What keeps that
 * from becoming one bundle is the router splitting them and a lint rule refusing imports across
 * the boundary (docs/frontend.md).
 *
 * The skip link and the landmarks are here rather than per screen, because they are the parts of
 * accessibility that are structural: a page with no `main` cannot be fixed by a component inside
 * it.
 *
 * ## Two densities, one frame
 *
 * The design gives the two trees different chrome, and the difference is the product's central
 * fact rather than a preference: a learner on a phone gets a two-item bar at the bottom, in reach
 * of a thumb; an administrator on a desktop gets a text nav in a 40px strip that does not spend
 * six hours' worth of screen on navigation. Both are the same shell because the session, the
 * landmarks and the sign-out are the same question in both.
 *
 * ## Why the gate is here and not in each screen
 *
 * A signed-out person meeting a screen full of failed calls learns nothing from it. The gate
 * answers the question once, in the only component every route already passes through. What it is
 * NOT is a security boundary -- that is the gateway's session and the services' permissions. A
 * screen rendered by a browser with no session simply cannot load anything, and this is about
 * saying so plainly rather than about preventing it.
 */
export function Shell() {
  const t = useT();
  const session = useSession();
  const { pathname } = useLocation();
  const inTheConsole = pathname.startsWith('/admin');
  // Asked once a session exists, and not before: an unauthenticated /api/v1/me is a 401 the
  // relay would answer with SESSION_ENDED, which is not what is happening.
  const who = useMe(session?.signedIn === true);

  // WHERE THE LANGUAGE ACTUALLY ARRIVES. Everything above renders in whatever this browser
  // guessed; this is the point the person's own answer reaches the page. `adopt` ignores null,
  // which is the "they have not told us" case and is deliberately not the same as English.
  const said = who.state === 'tenant' ? localeFrom(who.me.language) : null;
  useEffect(() => {
    adoptServerLocale(said);
  }, [said]);

  // The front door opens the issuer's login page rather than a panel saying what the person
  // already knows. `arrivalFor` is where the exception lives -- see arrival.ts for why bouncing
  // every signed-out render would be the more expensive bug.
  // Held as one value rather than re-narrowed at each use: `arrival` is non-null exactly when
  // this is, and TypeScript cannot see that relationship across a JSX closure.
  const signedOut = session !== null && !session.signedIn ? session : null;
  const arrival = signedOut !== null ? arrivalFor(signedOut) : null;

  useEffect(() => {
    if (session?.signedIn) {
      forgetTheAttempt();
      return;
    }
    if (signedOut !== null && arrival?.kind === 'sign-in-now') {
      rememberTheAttempt();
      signIn(signedOut);
    }
  }, [session?.signedIn, signedOut, arrival?.kind]);

  return (
    <div className={inTheConsole ? 'shell shell--console' : 'shell shell--learner'}>
      <a className="skip" href="#main">
        {t('shell.skip')}
      </a>

      <header className="shell__bar">
        {/*
         * The company's name, from `/api/v1/me` — `/auth/session` does not carry it and the
         * gateway keeps it that small on purpose (T-10.2).
         *
         * NOT A SWITCHER, and there will never be one beside it: tenant is never a parameter
         * (T-8.2). You are in a company because of who you signed in as.
         */}
        <span className="shell__tenant u-caps">
          {who.state === 'tenant'
            ? who.me.tenant
            : t(inTheConsole ? 'shell.tenant.console' : 'shell.tenant.learner')}
        </span>
        {inTheConsole ? (
          <nav aria-label={t('shell.nav')} className="shell__nav">
            <NavLink to="/admin/authoring">{t('shell.nav.authoring')}</NavLink>
            <NavLink to="/admin/assign">{t('shell.nav.assign')}</NavLink>
            <NavLink to="/admin/grading">{t('shell.nav.grading')}</NavLink>
            <NavLink to="/admin/people">{t('shell.nav.people')}</NavLink>
            <NavLink to="/admin/roles">{t('shell.nav.roles')}</NavLink>
            <NavLink to="/admin/compliance">{t('shell.nav.compliance')}</NavLink>
          </nav>
        ) : null}
        {session?.signedIn ? (
          <span className="shell__person">
            <span className="u-caps">{session.name}</span>
            {/*
             * THE CONSOLE IS OFFERED TO EVERYONE, and that is honest rather than lax. Catalog and
             * assessment carry no `@PreAuthorize` at all yet (ADR-0109 / T-9.11), so a link shown
             * only to some people would hide the console without securing anything — and the
             * person it hid it from could still type the address. The screens themselves say the
             * API is open; see `NotEnforcedYet`.
             */}
            {inTheConsole ? (
              <NavLink to="/" end className="shell__console">
                {t('shell.to-learner')}
              </NavLink>
            ) : (
              <NavLink to="/admin/authoring" className="shell__console">
                {t('shell.to-console')}
              </NavLink>
            )}
            <button type="button" className="btn btn-ghost btn-dense" onClick={() => void signOut()}>
              {t('shell.sign-out')}
            </button>
          </span>
        ) : null}
      </header>

      <main id="main" tabIndex={-1} className="shell__main">
        {session === null ? <Loading what="loading.session" /> : null}
        {session?.signedIn ? <Outlet /> : null}
        {/*
          * Only ever shown for the two cases arrival.ts singles out. An ordinary first visit does
          * not reach here: the effect above has already sent that person to the issuer, and what
          * they see meanwhile is the loading state, not a panel they have to dismiss.
          */}
        {arrival?.kind === 'explain' && signedOut !== null ? (
          <section aria-labelledby="signed-out" className="panel signed-out">
            <h1 id="signed-out" className="u-display">
              {t(arrivalWords[arrival.because].title)}
            </h1>
            <p>{t(arrivalWords[arrival.because].body)}</p>
            <button
              type="button"
              className="btn btn-primary"
              onClick={() => {
                rememberTheAttempt();
                signIn(signedOut);
              }}
            >
              {t('shell.sign-in')}
            </button>
          </section>
        ) : null}
        {arrival?.kind === 'sign-in-now' ? (
          <Loading what="loading.sign-in" />
        ) : null}
      </main>

      {/*
       * The learner's navigation, at the bottom, where a thumb is. Two destinations and no more:
       * a person who opens this a few times a year under obligation is not served by a menu, and
       * the console's four sections are not theirs to carry.
       */}
      {!inTheConsole && session?.signedIn ? (
        <nav aria-label={t('shell.nav')} className="tabbar">
          <NavLink to="/" end className="tabbar__tab">
            {t('shell.tab.training')}
          </NavLink>
          <NavLink to="/progress" className="tabbar__tab">
            {t('shell.tab.progress')}
          </NavLink>
        </nav>
      ) : null}
    </div>
  );
}
