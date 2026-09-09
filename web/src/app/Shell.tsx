import { NavLink, Outlet, useLocation } from 'react-router';
import { signIn, signOut } from '../shared/auth/session.ts';
import { useSession } from '../shared/auth/useSession.ts';
import { Loading } from '../shared/state/States.tsx';

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
  const session = useSession();
  const { pathname } = useLocation();
  const inTheConsole = pathname.startsWith('/admin');

  return (
    <div className={inTheConsole ? 'shell shell--console' : 'shell shell--learner'}>
      <a className="skip" href="#main">
        Skip to content
      </a>

      <header className="shell__bar">
        {/*
         * The design puts the company's name here, and it is NOT DRAWN YET, deliberately.
         *
         * `/auth/session` answers with one boolean, a name and a sign-in URL — the gateway keeps
         * it that small on purpose (T-10.2). The company's name lives in `identity`, and the
         * screens that already load `/api/v1/me` can show it. Reading it here would mean a second
         * request on every route or a copy of the tenant in the browser, and a copy is the thing
         * that goes stale silently.
         *
         * Whichever way it lands, it is not a switcher: tenant is never a parameter (T-8.2), so
         * there is no dropdown beside it and never will be.
         */}
        <span className="shell__tenant u-caps">{inTheConsole ? 'Console' : 'Your training'}</span>
        {inTheConsole ? (
          <nav aria-label="Main" className="shell__nav">
            <NavLink to="/admin/people">Users</NavLink>
            <NavLink to="/admin/roles">Roles</NavLink>
            <NavLink to="/admin/authoring">Authoring</NavLink>
            <NavLink to="/admin/compliance">Reports</NavLink>
          </nav>
        ) : null}
        {session?.signedIn ? (
          <span className="shell__person">
            <span className="u-caps">{session.name}</span>
            <button type="button" className="btn btn-ghost btn-dense" onClick={() => void signOut()}>
              Sign out
            </button>
          </span>
        ) : null}
      </header>

      <main id="main" tabIndex={-1} className="shell__main">
        {session === null ? <Loading what="your session" /> : null}
        {session?.signedIn ? <Outlet /> : null}
        {session !== null && !session.signedIn ? (
          <section aria-labelledby="signed-out" className="panel signed-out">
            <h1 id="signed-out" className="u-display">
              You are signed out
            </h1>
            <p>
              Sign in to see your learning. Anything you were part-way through is still here when
              you come back.
            </p>
            <button type="button" className="btn btn-primary" onClick={() => signIn(session)}>
              Sign in
            </button>
          </section>
        ) : null}
      </main>

      {/*
       * The learner's navigation, at the bottom, where a thumb is. Two destinations and no more:
       * a person who opens this a few times a year under obligation is not served by a menu, and
       * the console's four sections are not theirs to carry.
       */}
      {!inTheConsole && session?.signedIn ? (
        <nav aria-label="Main" className="tabbar">
          <NavLink to="/" end className="tabbar__tab">
            Training
          </NavLink>
          <NavLink to="/progress" className="tabbar__tab">
            Progress
          </NavLink>
        </nav>
      ) : null}
    </div>
  );
}
