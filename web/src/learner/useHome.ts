import { useEffect, useSyncExternalStore } from 'react';
import { catalog, failureFrom, type ApiFailure } from '../shared/api/client.ts';
import type { components } from '../shared/api/catalog.d.ts';

export type HomeView = components['schemas']['HomeView'];

/**
 * `GET /api/v1/me/home`, fetched once and shared by every screen built on it.
 *
 * <p>WHY THIS IS A STORE AND NOT A `useEffect` IN EACH SCREEN. Three screens are built on this one
 * response — Home, Discover and the course player's syllabus — because it is the ONLY learner-facing
 * endpoint that describes assigned training (docs/api-surface.md: the learner surface is a closed
 * set of sixteen `/me/` paths, and none of the others lists courses). Fetching it per screen would
 * mean a round trip every time somebody moves between them, on the connection this product least
 * wants to spend one on.
 *
 * <p>STALE WHILE REVALIDATING, which is the part that matters for correctness rather than speed. A
 * plain cache would be wrong here: a learner finishes a video and comes back to Home, and the
 * percentage they just earned is the first thing they look for. So every mount refetches — but
 * whatever is already known is rendered immediately instead of a loading state, so navigating
 * between the three screens never flashes. The server's own 60-second Valkey cache (T-5.8) is what
 * makes that refetch cheap.
 *
 * <p>The `revalidating` flag is deliberately NOT rendered as a spinner. A screen that is showing
 * correct data does not need to tell anybody it is checking, and a spinner that appears on every
 * navigation is the thing that makes an application feel slow.
 */
export type HomeState =
  | { status: 'loading' }
  | { status: 'ready'; home: HomeView; revalidating: boolean }
  | { status: 'failed'; failure: ApiFailure };

let state: HomeState = { status: 'loading' };
const listeners = new Set<() => void>();

function publish(next: HomeState) {
  state = next;
  for (const listener of listeners) {
    listener();
  }
}

function subscribe(listener: () => void) {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}

let inFlight: Promise<void> | null = null;

/**
 * Ask again. Shared, so three mounted screens asking at once make one request.
 *
 * <p>A failure REPLACES the data rather than being swallowed beside it. The alternative — keeping
 * the last good response on screen and reporting the failure quietly — means a learner acting on
 * a course list that may no longer be theirs, which in a product about obligations is the wrong
 * way to be wrong.
 */
export function refreshHome(): Promise<void> {
  if (inFlight) {
    return inFlight;
  }
  if (state.status === 'ready') {
    publish({ ...state, revalidating: true });
  }
  inFlight = catalog
    .GET('/api/v1/me/home')
    .then(({ data, response, error }) => {
      publish(
        data
          ? { status: 'ready', home: data, revalidating: false }
          : { status: 'failed', failure: failureFrom(response, error) },
      );
    })
    .catch((unreachable: unknown) => {
      publish({ status: 'failed', failure: failureFrom(undefined, unreachable) });
    })
    .finally(() => {
      inFlight = null;
    });
  return inFlight;
}

/**
 * Throw away what is known, so the next mount fetches rather than showing a stale answer.
 *
 * <p>For the case a refetch cannot cover: signing out. Leaving one person's assigned training in a
 * module-level variable for the next person to sign in on the same browser is a disclosure bug,
 * not a caching one.
 */
export function forgetHome() {
  publish({ status: 'loading' });
}

export function useHome(): HomeState {
  // Every mount revalidates. The effect, not the render, so a screen rendering twice under
  // StrictMode does not fire two requests -- and `inFlight` above collapses them anyway.
  useEffect(() => {
    void refreshHome();
  }, []);

  // The third argument is the server snapshot, and it is the same function: this is a static build
  // with no server rendering (docs/frontend.md), so there is no second environment to differ in.
  return useSyncExternalStore(
    subscribe,
    () => state,
    () => state,
  );
}
