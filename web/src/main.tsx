import { StrictMode, lazy, Suspense, type ComponentType } from 'react';
import { createRoot } from 'react-dom/client';
import { createBrowserRouter, RouterProvider } from 'react-router';
import { Shell } from './app/Shell.tsx';
import { Home } from './learner/Home.tsx';
import { Loading } from './shared/state/States.tsx';
import './styles.css';

// The admin console is loaded only by somebody who navigates to it. That is the whole mechanism
// keeping one deployable from meaning one download (docs/frontend.md): the learner app is used on
// whatever device a person has, and the console's weight is not theirs to carry.
//
// One helper rather than five copies of the same three-line lazy import, now that there are five.
function consoleScreen<Name extends string>(
  load: () => Promise<Record<Name, ComponentType>>,
  name: Name,
) {
  return lazy(async () => ({ default: (await load())[name] }));
}

const People = consoleScreen(() => import('./admin/People.tsx'), 'People');
const RoleEditor = consoleScreen(() => import('./admin/RoleEditor.tsx'), 'RoleEditor');
const Authoring = consoleScreen(() => import('./admin/Authoring.tsx'), 'Authoring');
const Compliance = consoleScreen(() => import('./admin/Compliance.tsx'), 'Compliance');
const Assign = consoleScreen(() => import('./admin/Assign.tsx'), 'Assign');
const Grading = consoleScreen(() => import('./admin/Grading.tsx'), 'Grading');

// The player is lazy for the same reason and a stronger one: it pulls hls.js, which is larger
// than everything else in this application put together.
const Watch = lazy(async () => ({ default: (await import('./learner/Watch.tsx')).Watch }));

const Progress = lazy(async () => ({ default: (await import('./learner/Progress.tsx')).Progress }));
const Sit = lazy(async () => ({ default: (await import('./learner/Sit.tsx')).Sit }));
const ReviewScreen = lazy(async () => ({
  default: (await import('./learner/ReviewScreen.tsx')).ReviewScreen,
}));

function deferred(element: React.ReactNode, what: string) {
  return <Suspense fallback={<Loading what={what} />}>{element}</Suspense>;
}

const router = createBrowserRouter([
  {
    path: '/',
    Component: Shell,
    children: [
      // Home is not lazy. It is the most-hit authenticated screen in the product and the one a
      // learner lands on; a chunk boundary in front of it buys nothing and costs a round trip on
      // the connection least able to afford one.
      { index: true, Component: Home },
      { path: 'progress', element: deferred(<Progress />, 'your progress') },
      { path: 'watch/:nodeId', element: deferred(<Watch />, 'the video') },
      { path: 'test/:testId', element: deferred(<Sit />, 'your test') },
      { path: 'review/:attemptId', element: deferred(<ReviewScreen />, 'your result') },
      { path: 'admin/people', element: deferred(<People />, 'the console') },
      { path: 'admin/roles', element: deferred(<RoleEditor />, 'the role editor') },
      { path: 'admin/authoring', element: deferred(<Authoring />, 'the course') },
      { path: 'admin/assign', element: deferred(<Assign />, 'assignments') },
      { path: 'admin/grading', element: deferred(<Grading />, 'the marking queue') },
      { path: 'admin/compliance', element: deferred(<Compliance />, 'the report') },
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
