import { StrictMode, useEffect } from 'react';
import { createRoot } from 'react-dom/client';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { routes } from '../app/routes.tsx';
import { forceLocale } from '../shared/i18n/locale.ts';
import { localeFrom } from '../shared/i18n/locales.ts';
import { serveFixtures } from './fixtures.ts';
import '../styles.css';

/**
 * The design preview (dev-only).
 *
 * <p>`npm run dev`, then http://localhost:5173/preview.html — no stack, no sign-in, no database.
 * It mounts THE APPLICATION'S OWN ROUTE TABLE (`app/routes.tsx`) against a memory history with
 * `fetch` stubbed, so what is on screen is the real shell rendering the real screens through the
 * real loading and error handling. A preview assembled out of its own copies of the components
 * would drift, and the first thing it would stop showing is whatever had just changed.
 *
 * <h2>It has no chrome, and that is the point</h2>
 *
 * <p>It carries no strip of controls — no screen picker, no language toggle, no theme toggle. Those
 * were a mistake twice over: they were taken for part of the product, which is the worst thing a
 * preview can do; and being `position: fixed` in the same viewport as the side panel and the
 * sticky header, they covered the very things they existed to show.
 *
 * <p>So everything is in the URL instead, and the page renders nothing but the application:
 *
 * <pre>
 *   preview.html                          the learner's home, in English
 *   preview.html?screen=/discover         browsing assigned training
 *   preview.html?screen=/admin/grading    the marking queue
 *   preview.html?lang=tr&amp;theme=dark       Turkish, dark
 * </pre>
 *
 * <p>Widths are checked by resizing the window, which is the only honest way: a CSS frame 375px
 * wide inside a 1440px window still matches every `min-width` media query, so a "phone" frame
 * would show the desktop layout and claim it had proved something.
 *
 * <p><b>It is not in the build.</b> `vite.config.ts` lists two entries, `index.html` and
 * `player.html`; Vite serves any HTML at the project root in development, and a production build
 * has nothing that reaches this file.
 */
serveFixtures();

const asked = new URLSearchParams(window.location.search);
const screen = asked.get('screen') ?? '/';
const lang = asked.get('lang');
const theme = asked.get('theme');

/*
 * The theme is set as the attribute the real application uses, so what is on screen is the same
 * cascade a person with an explicit light or dark preference gets -- not a preview-only class.
 */
if (theme === 'dark' || theme === 'light') {
  document.documentElement.setAttribute('data-theme', theme);
}

function Preview() {
  // `forceLocale` rather than a prop: the locale store is the authority for non-React code too
  // (`client.ts` reads it to set Accept-Language), and a preview that set only a React prop would
  // render Turkish sentences over English request headers.
  useEffect(() => {
    if (lang) {
      forceLocale(localeFrom(lang));
    }
  }, []);

  const router = createMemoryRouter(routes, { initialEntries: [screen] });
  return <RouterProvider router={router} />;
}

const root = document.getElementById('root');
if (!root) {
  throw new Error('preview.html has no #root element to mount into');
}

createRoot(root).render(
  <StrictMode>
    <Preview />
  </StrictMode>,
);
