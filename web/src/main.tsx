import { StrictMode, lazy, Suspense } from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter, RouterProvider } from 'react-router';
import { Shell } from './app/Shell.tsx';
import { MyLearning } from './learner/MyLearning.tsx';
import { Loading } from './shared/state/States.tsx';
import './styles.css';

// The admin console is loaded only by somebody who navigates to it. That is the whole mechanism
// keeping one deployable from meaning one download (docs/frontend.md): the learner app is used on
// whatever device a person has, and the console's weight is not theirs to carry.
const People = lazy(async () => ({ default: (await import('./admin/People.tsx')).People }));

// The player is lazy for the same reason and a stronger one: it pulls hls.js, which is larger
// than everything else in this application put together.
const Watch = lazy(async () => ({ default: (await import('./learner/Watch.tsx')).Watch }));

const router = createBrowserRouter([
  {
    path: '/',
    Component: Shell,
    children: [
      { index: true, Component: MyLearning },
      {
        path: 'watch/:nodeId',
        element: (
          <Suspense fallback={<Loading what="the video" />}>
            <Watch />
          </Suspense>
        ),
      },
      {
        path: 'admin/people',
        element: (
          <Suspense fallback={<Loading what="the console" />}>
            <People />
          </Suspense>
        ),
      },
    ],
  },
]);

const root = document.getElementById('root');
if (!root) {
  throw new Error('index.html has no #root element to mount into');
}

createRoot(root).render(
  <StrictMode>
    <RouterProvider router={router} />
  </StrictMode>,
);
