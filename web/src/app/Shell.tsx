import { Bell, GraduationCap, LogOut, Menu, Palette } from 'lucide-react';
import { useCallback, useEffect, useRef, useState } from 'react';
import { Navigate, NavLink, Outlet, useLocation } from 'react-router';
import { arrivalFor, forgetTheAttempt, rememberTheAttempt } from '../shared/auth/arrival.ts';
import { signIn, signOut } from '../shared/auth/session.ts';
import { useMe } from '../shared/auth/useMe.ts';
import { useSession } from '../shared/auth/useSession.ts';
import { Button, buttonClasses } from '../shared/design/Button.tsx';
import { Drawer } from '../shared/design/Drawer.tsx';
import { adoptServerLocale } from '../shared/i18n/locale.ts';
import { adoptServerTheme, themeFrom } from '../shared/theme/theme.ts';
import { Appearance } from '../shared/theme/Appearance.tsx';
import { localeFrom } from '../shared/i18n/locales.ts';
import { useLocale, useT } from '../shared/i18n/useLocale.ts';
import { forgetHome } from '../learner/useHome.ts';
import { Loading } from '../shared/state/States.tsx';
import type { ShellContext } from './shellContext.ts';
import { Sidebar } from './Sidebar.tsx';

/** Below this the side panel is a drawer. Stated once here, and matched by `--breakpoint-desk`. */
const WIDE = '(min-width: 60rem)';

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
 * ## One side panel, two densities
 *
 * Navigation lives in a panel down the side for both trees — three destinations for a learner, six
 * for an administrator, in the same place with the same shape. Below 60rem the panel is a drawer
 * behind a menu button, so on the phone a learner is assumed to be on, navigation is one tap away
 * and takes none of the screen until it is asked for. {@link Drawer} owns what that owes a
 * keyboard.
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
  // Handed to the drawer so it can give focus back. See Drawer.tsx: it cannot find this itself,
  // because `inert` below has already blurred it by the time the drawer's effects run.
  const menuButton = useRef<HTMLButtonElement>(null);

  /*
   * THE DRAWER REMEMBERS WHICH SCREEN IT WAS OPENED ON, rather than being a boolean somebody has
   * to remember to clear.
   *
   * Navigating is the entire point of a navigation drawer, so it has to close when the route
   * changes. Written as an effect that sets state on `pathname`, that is a second render on every
   * navigation and the pattern `react-hooks/set-state-in-effect` refuses -- it fires whether or
   * not the drawer was ever open. Holding the route it was opened FOR makes closing a consequence
   * of arriving somewhere rather than a reaction to it, with no effect and no extra render.
   */
  const [openAt, setOpenAt] = useState<string | null>(null);
  const drawerOpen = openAt === pathname;
  const closeDrawer = useCallback(() => setOpenAt(null), []);

  /*
   * And it is only ever open on a narrow screen. Opening it on a phone and then widening the
   * window -- a desktop browser dragged, a tablet turned -- would otherwise leave the page behind
   * it `inert` under a backdrop CSS had already stopped drawing.
   *
   * No initial check: the only way to be open is to have pressed a button that exists only below
   * this width, so there is nothing to settle on mount.
   */
  useEffect(() => {
    const wide = window.matchMedia(WIDE);
    const settle = () => {
      if (wide.matches) {
        setOpenAt(null);
      }
    };
    wide.addEventListener('change', settle);
    return () => wide.removeEventListener('change', settle);
  }, []);

  // Asked once a session exists, and not before: an unauthenticated /api/v1/me is a 401 the
  // relay would answer with SESSION_ENDED, which is not what is happening.
  const who = useMe(session?.signedIn === true);

  // WHERE THE LANGUAGE AND THE PALETTE ACTUALLY ARRIVE. Everything above renders in whatever this
  // browser guessed or last remembered; this is the point the person's own answers reach the page.
  // Both `adopt` calls ignore null, which is the "they have not told us" case and is deliberately
  // not the same as a choice of English or of following the device (T-10.9).
  const said = who.state === 'tenant' ? localeFrom(who.me.language) : null;
  const looks = who.state === 'tenant' ? themeFrom(who.me.theme) : null;
  useEffect(() => {
    adoptServerLocale(said);
  }, [said]);
  useEffect(() => {
    adoptServerTheme(looks);
  }, [looks]);

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

  /*
   * A REASON WORTH SAYING GOES TO THE SIGN-IN SCREEN, and everything else is unchanged.
   *
   * `arrivalFor` still decides. An ordinary first-time visitor is still sent straight to the
   * issuer by the effect above and never meets a panel — that argument is `arrival.ts`'s and it
   * has not changed. What HAS changed is where the other three cases are shown: they used to be a
   * card rendered inside the signed-in frame, next to a side panel of six destinations nobody
   * could go to and a menu for an account nobody was in. They are now a screen of their own.
   *
   * `replace`, so the browser's back button does not return to a route that will only redirect
   * here again. The reason travels in the router's state because `arrivalFor` consumed the flag it
   * came from — see Login.tsx.
   */
  if (arrival?.kind === 'explain') {
    return <Navigate to="/login" replace state={{ because: arrival.because }} />;
  }

  const brand = (
    <div className="flex min-h-16 items-center gap-2.5 border-b border-hairline px-4">
      <GraduationCap aria-hidden="true" className="size-5 shrink-0 text-brand" strokeWidth={2.25} />
      <span className="font-display text-sm font-bold tracking-tight">{t('shell.product')}</span>
    </div>
  );

  return (
    <div className="min-h-dvh">
      {/*
       * FIRST IN THE DOM, and that is the only place it helps: a skip link after the navigation
       * skips nothing. Visually hidden until focused, which is why it is `sr-only` rather than
       * off-screen positioning -- the latter is what makes a skip link that never becomes visible.
       */}
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:fixed focus:start-3 focus:top-3 focus:z-60 focus:rounded-lg focus:bg-brand focus:px-4 focus:py-2 focus:text-sm focus:font-semibold focus:text-brand-on"
      >
        {t('shell.skip')}
      </a>

      {/*
       * `inert` while the drawer is open. This is what keeps Tab inside the drawer, and it is the
       * browser doing it rather than a hand-rolled focus cycle -- see Drawer.tsx. jsdom implements
       * neither, so this is a no-op in the suite and correct in a browser.
       */}
      <div inert={drawerOpen}>
        <aside className="fixed inset-y-0 start-0 hidden w-64 flex-col border-e border-hairline bg-surface desk:flex">
          {brand}
          <Sidebar inTheConsole={inTheConsole} />
        </aside>

        <div className="flex min-h-dvh flex-col desk:ps-64">
          <header className="glass sticky top-0 z-30 flex min-h-16 items-center gap-3 border-b px-3 sm:px-5">
            <Button
              ref={menuButton}
              voice="ghost"
              aria-label={t('shell.menu')}
              aria-expanded={drawerOpen}
              onClick={() => setOpenAt(pathname)}
              className="px-2 desk:hidden"
            >
              <Menu aria-hidden="true" className="size-5" />
            </Button>

            {/*
             * The company's name, from `/api/v1/me` — `/auth/session` does not carry it and the
             * gateway keeps it that small on purpose (T-10.2).
             *
             * NOT A SWITCHER, and there will never be one beside it: tenant is never a parameter
             * (T-8.2). You are in a company because of who you signed in as.
             */}
            <span className="label-caps min-w-0 truncate">
              {who.state === 'tenant'
                ? who.me.tenant
                : t(inTheConsole ? 'shell.tenant.console' : 'shell.tenant.learner')}
            </span>

            <span className="flex-1" />

            {session?.signedIn ? (
              <>
                <Appearances />
                <Notifications />
                {/*
                 * THE CONSOLE IS OFFERED TO EVERYONE, and that is honest rather than lax. Catalog
                 * and assessment carry no `@PreAuthorize` at all yet (ADR-0109 / T-9.11), so a
                 * link shown only to some people would hide the console without securing
                 * anything — and the person it hid it from could still type the address. The
                 * screens themselves say the API is open; see `NotEnforcedYet`.
                 */}
                <NavLink
                  to={inTheConsole ? '/' : '/admin/authoring'}
                  end={inTheConsole}
                  /*
                   * `max-sm:hidden` and NOT `hidden sm:inline-flex`. Both `hidden` and the
                   * `inline-flex` that `buttonClasses` already applies set `display`, and an
                   * UNPREFIXED utility cannot beat another unprefixed utility by being written
                   * later in the attribute -- Tailwind emits them in its own order, `hidden`
                   * first, so `inline-flex` won and this button was visible on a phone whatever
                   * the class list said. A variant is emitted after the base layer, so it wins.
                   * Found by looking at it at 375px.
                   */
                  className={`${buttonClasses('secondary', 'sm')} max-sm:hidden`}
                >
                  {t(inTheConsole ? 'shell.to-learner' : 'shell.to-console')}
                </NavLink>
                {session.name ? <Avatar name={session.name} /> : null}
                <Button
                  voice="ghost"
                  size="sm"
                  aria-label={t('shell.sign-out')}
                  onClick={() => {
                    // Dropped BEFORE the request, not after: `signOut` navigates, and anything
                    // sequenced after it is not guaranteed to run.
                    forgetHome();
                    void signOut();
                  }}
                  className="px-2"
                >
                  <LogOut aria-hidden="true" className="size-4" />
                  <span className="sr-only sm:not-sr-only">{t('shell.sign-out')}</span>
                </Button>
              </>
            ) : null}
          </header>

          <main
            id="main"
            tabIndex={-1}
            className="mx-auto w-full max-w-6xl flex-1 px-4 py-6 focus:outline-none sm:px-6 sm:py-8"
          >
            {/*
             * Keyed on the route so the arrival animation replays on navigation. One reveal for
             * the whole page rather than a stagger per card: on the device this product is used on,
             * a dozen animations landing at once is the thing that reads as slow.
             */}
            <div key={pathname} className="rise">
              {session === null ? <Loading what="loading.session" /> : null}
              {/*
               * The screens are handed what the shell already asked for. See shellContext.ts:
               * Home wanting a name is not worth a second request on the most-hit screen.
               */}
              {session?.signedIn ? (
                <Outlet context={{ name: session.name } satisfies ShellContext} />
              ) : null}
              {arrival?.kind === 'sign-in-now' ? <Loading what="loading.sign-in" /> : null}
            </div>
          </main>
        </div>
      </div>

      {drawerOpen ? (
        <Drawer label={t('shell.nav')} onClose={closeDrawer} returnFocusTo={menuButton}>
          {brand}
          <Sidebar inTheConsole={inTheConsole} />
        </Drawer>
      ) : null}
    </div>
  );
}

/**
 * The person, as initials.
 *
 * <p>`toLocaleUpperCase` with the current locale, not `toUpperCase`: Turkish is one of the two
 * languages this product ships in, and a dotless-i uppercased by the invariant rules gives "I"
 * where the language wants "İ". A wrong initial on somebody's own name is a small insult that is
 * entirely avoidable.
 */
function Avatar({ name }: { name: string }) {
  const { locale } = useLocale();
  const initials = name
    .trim()
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => [...part][0] ?? '')
    .join('')
    .toLocaleUpperCase(locale);

  return (
    // `title` and not `aria-label`: this is decorative next to the name it abbreviates, and the
    // full name is already in the accessibility tree via the sign-out control's context. The
    // tooltip is for the sighted case where two colleagues share initials.
    <span
      title={name}
      className="grid size-9 shrink-0 place-items-center rounded-full bg-brand-tint text-xs font-bold text-brand"
    >
      {initials}
    </span>
  );
}

/**
 * Appearance and language, from the header (T-10.9).
 *
 * <p><b>In the header rather than on a settings page, and that is the whole reason it gets used.</b>
 * Somebody who needs the light theme needs it now, on the screen they are looking at, not after
 * finding a preferences page. The same goes for a learner whose browser guessed the wrong
 * language: the control has to be reachable from wherever they are stuck.
 *
 * <p>The panel's open/close behaviour is {@link Notifications}'s, deliberately duplicated rather
 * than extracted: two call sites is not yet a component, and the day there is a third the shape to
 * extract will be obvious. What is NOT duplicated is the decision inside it — Escape and
 * `focusout` rather than an outside click, so it is operable by a keyboard.
 */
function Appearances() {
  const t = useT();
  const [open, setOpen] = useState(false);
  const wrapper = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) {
      return;
    }
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        setOpen(false);
      }
    }
    function onFocusOut(event: FocusEvent) {
      const next = event.relatedTarget;
      if (next instanceof Node && wrapper.current?.contains(next)) {
        return;
      }
      setOpen(false);
    }
    document.addEventListener('keydown', onKeyDown);
    wrapper.current?.addEventListener('focusout', onFocusOut);
    const held = wrapper.current;
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      held?.removeEventListener('focusout', onFocusOut);
    };
  }, [open]);

  return (
    <div ref={wrapper} className="relative">
      <Button
        voice="ghost"
        size="sm"
        aria-label={t('prefs.open')}
        aria-expanded={open}
        onClick={() => setOpen((was) => !was)}
        className="px-2"
      >
        <Palette aria-hidden="true" className="size-4" />
      </Button>
      {open ? (
        <div
          role="group"
          aria-label={t('prefs.title')}
          className="card absolute end-0 top-full z-40 mt-2 w-72 p-4 shadow-float"
        >
          {/* Signed in by construction: the header only renders this beside the sign-out control. */}
          <Appearance signedIn />
        </div>
      ) : null}
    </div>
  );
}

/**
 * Notifications, and an honest admission that there are none.
 *
 * <p>THERE IS NO NOTIFICATIONS ENDPOINT. The learner-facing API is a closed set of sixteen `/me/`
 * paths (docs/api-surface.md) and not one of them answers this question — the reminder machinery
 * that exists (`/api/v1/reminders/unsent`, T-5.6) is the mailer's, read by a scheduled job, and
 * carries no per-learner read state. So this says so, rather than showing an invented badge with
 * an invented count. When the endpoint arrives, the panel is where its list goes.
 */
function Notifications() {
  const t = useT();
  const [open, setOpen] = useState(false);
  const wrapper = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) {
      return;
    }
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        setOpen(false);
      }
    }
    // `focusout` rather than a document click: a panel that closes on Escape and on losing focus
    // is operable by a keyboard, and one that closes only on an outside CLICK is not.
    function onFocusOut(event: FocusEvent) {
      const next = event.relatedTarget;
      if (next instanceof Node && wrapper.current?.contains(next)) {
        return;
      }
      setOpen(false);
    }
    document.addEventListener('keydown', onKeyDown);
    wrapper.current?.addEventListener('focusout', onFocusOut);
    const held = wrapper.current;
    return () => {
      document.removeEventListener('keydown', onKeyDown);
      held?.removeEventListener('focusout', onFocusOut);
    };
  }, [open]);

  return (
    <div ref={wrapper} className="relative">
      <Button
        voice="ghost"
        size="sm"
        aria-label={t('shell.notifications')}
        aria-expanded={open}
        onClick={() => setOpen((was) => !was)}
        className="px-2"
      >
        <Bell aria-hidden="true" className="size-4" />
      </Button>
      {open ? (
        <div
          role="group"
          aria-label={t('shell.notifications')}
          className="card absolute end-0 top-full z-40 mt-2 w-72 p-4 shadow-float"
        >
          <p className="text-sm text-muted">{t('shell.notifications.none')}</p>
        </div>
      ) : null}
    </div>
  );
}
