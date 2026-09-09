import { NavLink, Outlet } from 'react-router';
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

  return (
    <>
      <a className="skip" href="#main">
        Skip to content
      </a>
      <header>
        <nav aria-label="Main">
          <ul>
            <li>
              <NavLink to="/" end>
                My learning
              </NavLink>
            </li>
            <li>
              <NavLink to="/admin/people">People</NavLink>
            </li>
          </ul>
        </nav>
        {session?.signedIn ? (
          <p className="session">
            <span>{session.name}</span>{' '}
            <button type="button" onClick={() => void signOut()}>
              Sign out
            </button>
          </p>
        ) : null}
      </header>
      <main id="main" tabIndex={-1}>
        {session === null ? <Loading what="your session" /> : null}
        {session?.signedIn ? <Outlet /> : null}
        {session !== null && !session.signedIn ? (
          <section aria-labelledby="signed-out">
            <h1 id="signed-out">You are signed out</h1>
            <p>
              Sign in to see your learning. Anything you were part-way through is still here when
              you come back.
            </p>
            <button type="button" onClick={() => signIn(session)}>
              Sign in
            </button>
          </section>
        ) : null}
      </main>
    </>
  );
}
