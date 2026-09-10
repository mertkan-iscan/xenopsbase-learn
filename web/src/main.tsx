import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter, RouterProvider } from 'react-router';
import { routes } from './app/routes.tsx';
import './styles.css';

/**
 * The application's entry point, and nothing else.
 *
 * <p>The route table lives in `app/routes.tsx` rather than here because two things mount it: this
 * file against the browser's history, and `src/preview/` against a memory history with `fetch`
 * stubbed. A preview assembled from its own copy of the routes would drift, and the first thing it
 * would stop showing is whatever had just changed.
 */
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
