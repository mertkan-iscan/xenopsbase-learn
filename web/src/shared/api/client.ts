import createClient from 'openapi-fetch';
import type { paths as identityPaths } from './identity.d.ts';
import type { paths as reportingPaths } from './reporting.d.ts';
import type { paths as streamingPaths } from './streaming.d.ts';

/**
 * The typed client for `identity` (T-10.1).
 *
 * <p>Its types are generated from the service's own OpenAPI description and checked against the
 * running service by `npm run api:check`, so a backend change that breaks a call here fails a
 * build rather than a screen. Nothing in this file is hand-written knowledge about the API —
 * paths, bodies and responses all come from `identity.d.ts`.
 */
// Same origin by default -- the gateway in production, the dev server's proxy locally. An
// override exists for pointing a build at a service directly, and using it means accepting the
// CORS conversation that comes with it.
//
// Spelled out as the origin rather than left empty, which would produce a relative `/api/...`.
// In a browser the two are identical, because a relative path resolves against exactly this
// origin. Under jsdom they are not: its fetch is WHATWG-strict and refuses a URL with no base,
// so an empty string turns every test of every screen into "Failed to parse URL" -- an error
// about the test environment wearing the costume of an error about the API.
const sameOrigin = typeof window === 'undefined' ? '' : window.location.origin;
const baseUrl = import.meta.env.VITE_IDENTITY_URL ?? sameOrigin;

/**
 * DEVELOPMENT ONLY, AND DELIBERATELY UGLY.
 *
 * Real sign-in is T-10.2: the gateway holds the session and relays a token inward, and this
 * application never sees a refresh token or a password. Until that exists, a token pasted from
 * `make token U=acme-admin` is how a screen talks to a real service — which is still better than
 * a mock, because a mock agrees with whatever the person writing it believed.
 *
 * The name says `DEV` so that the day it appears in a production build, it is obvious in a diff.
 */
const developmentToken = import.meta.env.VITE_DEV_TOKEN;

const developmentAuth = {
  onRequest({ request }: { request: Request }) {
    if (developmentToken) {
      request.headers.set('Authorization', `Bearer ${developmentToken}`);
    }
    return request;
  },
};

/**
 * Resolved per call rather than captured when the client is built.
 *
 * `openapi-fetch` reads `globalThis.fetch` once, at `createClient`, which means anything that
 * replaces `fetch` afterwards is ignored — a test's stub, and equally the request instrumentation
 * T-9.13 will want to install. Deferring the lookup costs one property read per request and makes
 * the client honest about which `fetch` it is using: the current one.
 */
const currentFetch: typeof fetch = (input, init) => globalThis.fetch(input, init);

export const identity = createClient<identityPaths>({ baseUrl, fetch: currentFetch });
identity.use(developmentAuth);

/**
 * `streaming`, which the player talks to for playback tokens (T-3.4, T-3.5).
 *
 * Same origin as identity, deliberately: the browser talks to ONE origin and something behind it
 * routes by path — the gateway in production (T-10.2), the dev server's proxy locally. Two
 * clients here means two generated contracts, not two hosts the browser knows about.
 *
 * That routing is sharper than it looks. Both services answer under `/api/v1/me`, so the proxy
 * rules are order-sensitive; `vite.config.ts` says so where the rules are.
 */
export const streaming = createClient<streamingPaths>({ baseUrl, fetch: currentFetch });

/**
 * `reporting`, which the player posts heartbeats to (T-3.6).
 *
 * A third client rather than a call through streaming, because that is the property the task
 * exists for: telemetry is the most write-heavy path in the product and it must not touch
 * anything a learner's playback depends on. A convenience endpoint on streaming that forwarded
 * to here would undo that in one commit.
 */
export const reporting = createClient<reportingPaths>({ baseUrl, fetch: currentFetch });
reporting.use(developmentAuth);
streaming.use(developmentAuth);

/**
 * What a screen shows when a call fails. The shape is deliberately small: a sentence a person can
 * act on, and the status for the one case where the sentence depends on it.
 */
export type ApiFailure = { status: number; message: string };

export function failureFrom(response: Response | undefined, error: unknown): ApiFailure {
  if (!response) {
    return {
      status: 0,
      message:
        error instanceof Error && error.message
          ? `Could not reach the service: ${error.message}`
          : 'Could not reach the service.',
    };
  }
  if (response.status === 401) {
    return { status: 401, message: 'You are not signed in.' };
  }
  if (response.status === 403) {
    return { status: 403, message: 'You are signed in, but this is not yours to do.' };
  }
  if (response.status === 404) {
    // The disclosure rule (T-2.4) means a 404 can also be "you may not know this exists", and a
    // screen must not translate it into "it is gone" -- that would be the UI asserting something
    // the API deliberately refused to say.
    return { status: 404, message: 'Not found, or not visible to you.' };
  }
  return { status: response.status, message: `The service answered ${response.status}.` };
}
