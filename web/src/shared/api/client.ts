import createClient from 'openapi-fetch';
import type { paths } from './identity.d.ts';

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
const baseUrl = import.meta.env.VITE_IDENTITY_URL ?? '';

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

export const identity = createClient<paths>({ baseUrl });

identity.use({
  onRequest({ request }) {
    if (developmentToken) {
      request.headers.set('Authorization', `Bearer ${developmentToken}`);
    }
    return request;
  },
});

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
