import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter, RouterProvider } from 'react-router';
import { routes } from './app/routes.tsx';
import { applyRememberedTheme } from './shared/theme/theme.ts';
import './styles.css';

/**
 * The application's entry point, and nothing else.
 *
 * <p>The route table lives in `app/routes.tsx` rather than here because two things mount it: this
 * file against the browser's history, and `src/preview/` against a memory history with `fetch`
 * stubbed. A preview assembled from its own copy of the routes would drift, and the first thing it
 * would stop showing is whatever had just changed.
 */
/*
 * THE PALETTE GOES ON BEFORE REACT DOES, and the ordering is the entire feature.
 *
 * Everything after the first paint is a flash of the wrong colours. Somebody who chose the light
 * theme because of how they see should not be shown a dark screen for two frames on every
 * navigation, and somebody who chose dark should not be flashed white in a dim room. The store in
 * `shared/theme/theme.ts` reads `localStorage` at module scope for exactly this reason: the
 * server's answer arrives a round trip later and corrects it if it differs, which nobody sees
 * because it almost never does.
 */
applyRememberedTheme();

const router = createBrowserRouter(routes);

const root = document.getElementById('root');
if (!root) {
  throw new Error('index.html has no #root element to mount into');
}

createRoot(root).render(
  <StrictMode>
    <RouterProvider router={router} />
  </StrictMode>,
);
