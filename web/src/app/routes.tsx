import { lazy, Suspense, type ComponentType, type ReactNode } from 'react';
import type { RouteObject } from 'react-router';
import { Login } from '../auth/Login.tsx';
import { Discover } from '../learner/Discover.tsx';
import { Home } from '../learner/Home.tsx';
import type { LoadingKey } from '../shared/i18n/messages.en.ts';
import { Loading } from '../shared/state/States.tsx';
import { Shell } from './Shell.tsx';

/**
 * The route table, in its own module so that TWO things can mount it.
 *
 * <p>`main.tsx` mounts it against the browser's history; `src/preview/` mounts it against a memory
 * history with `fetch` stubbed, which is how the design is looked at without a backend. That is
 * the same argument ADR-0110 makes about the player and `frontend.md` repeats: a private path that
 * only one caller uses is a path nobody would notice breaking. A preview built from its own copy
 * of the routes would keep rendering a screen the application had stopped rendering.
 */
// The admin console is loaded only by somebody who navigates to it. That is the whole mechanism
// keeping one deployable from meaning one download (docs/frontend.md): the learner app is used on
// whatever device a person has, and the console's weight is not theirs to carry.
//
// One helper rather than six copies of the same three-line lazy import.
function consoleScreen<Name extends string>(
  load: () => Promise<Record<Name, ComponentType>>,
  name: Name,
) {
  return lazy(async () => ({ default: (await load())[name] }));
}

const People = consoleScreen(() => import('../admin/People.tsx'), 'People');
const RoleEditor = consoleScreen(() => import('../admin/RoleEditor.tsx'), 'RoleEditor');
const Authoring = consoleScreen(() => import('../admin/Authoring.tsx'), 'Authoring');
const Compliance = consoleScreen(() => import('../admin/Compliance.tsx'), 'Compliance');
const Assign = consoleScreen(() => import('../admin/Assign.tsx'), 'Assign');
const Grading = consoleScreen(() => import('../admin/Grading.tsx'), 'Grading');

// The player is lazy for the same reason and a stronger one: it pulls hls.js, which is larger
// than everything else in this application put together.
const Watch = lazy(async () => ({ default: (await import('../learner/Watch.tsx')).Watch }));

const Progress = lazy(async () => ({ default: (await import('../learner/Progress.tsx')).Progress }));
const Sit = lazy(async () => ({ default: (await import('../learner/Sit.tsx')).Sit }));
const ReviewScreen = lazy(async () => ({
  default: (await import('../learner/ReviewScreen.tsx')).ReviewScreen,
}));

function deferred(element: ReactNode, what: LoadingKey) {
  return <Suspense fallback={<Loading what={what} />}>{element}</Suspense>;
}

export const routes: RouteObject[] = [
  /*
   * THE SIGN-IN SCREEN SITS OUTSIDE THE SHELL, and that is the point of it being a route at all.
   *
   * The shell is the signed-in frame: a side panel of destinations, a company name, a sign-out
   * control. Rendering the sign-in screen inside it would draw navigation to six places nobody can
   * go and a menu for an account nobody is in. So `/login` is its own tree with its own `main`
   * landmark, and the shell redirects to it rather than growing a signed-out mode.
   *
   * Not lazy: it is the first screen a signed-out person sees, and a chunk boundary in front of it
   * buys nothing and costs a round trip on the connection least able to afford one.
   */
  { path: '/login', Component: Login },
  {
    path: '/',
    Component: Shell,
    children: [
      // Home is not lazy. It is the most-hit authenticated screen in the product and the one a
      // learner lands on; a chunk boundary in front of it buys nothing and costs a round trip on
      // the connection least able to afford one.
      { index: true, Component: Home },
      // NEITHER IS DISCOVER, and for a sharper reason: it is built on the SAME `/api/v1/me/home`
      // response Home just fetched (docs/api-surface.md has no learner-facing course search), so
      // it is a filter over data already in the browser. A lazy chunk in front of a screen that
      // needs no new data is a round trip bought for nothing.
      { path: 'discover', Component: Discover },
      { path: 'progress', element: deferred(<Progress />, 'loading.progress') },
      { path: 'watch/:nodeId', element: deferred(<Watch />, 'loading.video') },
      { path: 'test/:testId', element: deferred(<Sit />, 'loading.test') },
      { path: 'review/:attemptId', element: deferred(<ReviewScreen />, 'loading.result') },
      { path: 'admin/people', element: deferred(<People />, 'loading.console') },
      { path: 'admin/roles', element: deferred(<RoleEditor />, 'loading.role-editor') },
      { path: 'admin/authoring', element: deferred(<Authoring />, 'loading.course') },
      { path: 'admin/assign', element: deferred(<Assign />, 'loading.assignments') },
      { path: 'admin/grading', element: deferred(<Grading />, 'loading.marking') },
      { path: 'admin/compliance', element: deferred(<Compliance />, 'loading.report') },
    ],
  },
];
