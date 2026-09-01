import { NavLink, Outlet } from 'react-router';

/**
 * The frame both route trees sit in (T-10.1).
 *
 * <p>One application, two trees: `/` is the learner app and `/admin` is the console. What keeps
 * that from becoming one bundle is the router splitting them and a lint rule refusing imports
 * across the boundary (docs/frontend.md).
 *
 * <p>The skip link and the landmarks are here rather than per screen, because they are the parts
 * of accessibility that are structural: a page with no `main` cannot be fixed by a component
 * inside it.
 */
export function Shell() {
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
      </header>
      <main id="main" tabIndex={-1}>
        <Outlet />
      </main>
    </>
  );
}
