import createClient from 'openapi-fetch';
import { csrfHeader } from '../auth/session.ts';
import { currentLocale } from '../i18n/locale.ts';
import { currentT } from '../i18n/t.ts';
import type { paths as assessmentPaths } from './assessment.d.ts';
import type { paths as catalogPaths } from './catalog.d.ts';
import type { paths as identityPaths } from './identity.d.ts';
import type { paths as packagingPaths } from './packaging.d.ts';
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
 * THE CREDENTIAL IS A COOKIE, AND IT IS NOT IN THIS FILE (T-10.2).
 *
 * There used to be a `VITE_DEV_TOKEN` here, pasted from `make token`, with a comment saying real
 * sign-in was T-10.2's. This is T-10.2: the gateway holds the session and relays a token inward,
 * and this application never sees one. So the middleware below attaches no credential at all --
 * it sends the session cookie and, for writes, the CSRF value the cookie's origin has to echo.
 *
 * `same-origin` rather than `include`: there is one origin (the gateway serves this app and
 * relays `/api` behind it), and `include` would send the session to any origin a call ever
 * reached, which is the kind of default that is only wrong once.
 */
const browserSession = {
  onRequest({ request }: { request: Request }) {
    const withCookies = new Request(request, { credentials: 'same-origin' });
    /*
     * THE LANGUAGE TRAVELS WITH EVERY CALL, and this is the only place it is attached.
     *
     * The services generate sentences a learner reads — why a node is locked, what an integrity
     * signal records, why playback was refused — and they are stateless about who is asking: catalog
     * must not read identity's `app_user` table to find out (ADR-0109). So the client states it, in
     * the header HTTP already has for exactly this, and the gateway relays it untouched
     * (`ApiRelay.NOT_FORWARDED` names three headers and this is not one of them).
     *
     * Set rather than appended: this is a stated preference, not a negotiation, and a `q`-weighted
     * list built from the browser's own header would let a service answer in a language the person
     * did not choose.
     */
    withCookies.headers.set('Accept-Language', currentLocale());
    if (request.method !== 'GET' && request.method !== 'HEAD') {
      for (const [name, value] of Object.entries(csrfHeader())) {
        withCookies.headers.set(name, value);
      }
    }
    return withCookies;
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
identity.use(browserSession);

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
reporting.use(browserSession);
streaming.use(browserSession);

/**
 * `catalog`: what training exists, who it reaches, and what is pinned inside a video (E5).
 *
 * Same origin again, and the routing behind it is the sharpest case in the table: catalog and
 * streaming BOTH answer under `/api/v1/me/nodes/{id}/…` — playback and progress on one side, the
 * interstitials pinned in that node's timeline on the other. The gateway tells them apart by the
 * segment after the id, and `UpstreamsTest` is where that is asserted rather than hoped.
 */
export const catalog = createClient<catalogPaths>({ baseUrl, fetch: currentFetch });
catalog.use(browserSession);

/**
 * `assessment`: banks, questions, tests, attempts and marking (E6).
 *
 * The largest surface here by some way, and the one where a drifted client would be least
 * obvious: an admin console that cannot build a question is broken loudly, and one that builds a
 * subtly wrong one is not. `npm run api:check` is what makes that a build failure rather than a
 * screen.
 */
export const assessment = createClient<assessmentPaths>({ baseUrl, fetch: currentFetch });
assessment.use(browserSession);

/**
 * `packaging`: uploading a SCORM, cmi5 or slides archive and turning it into something launchable
 * (E4, ADR-0105).
 *
 * <p><b>Only half of that service is behind this client, and the other half is deliberately not
 * reachable from here.</b> This one talks to `/api/v1/uploads` — reserve a package, get a signed
 * target, ask for the archive to be processed — through the same gateway as everything else. The
 * package's own files are served by the tenant's CONTENT ORIGIN, which this application touches
 * only by putting a URL into an iframe: it never fetches from it, and could not usefully, because
 * that origin holds nothing of ours and answers nothing about anybody.
 *
 * <p>The archive itself does not travel through this client either. It goes browser → object
 * storage, with a plain `fetch` to the signed URL and <b>no credentials of ours attached</b> —
 * see `uploadArchive` in `admin/upload.ts`, which is where that is explained.
 */
export const packaging = createClient<packagingPaths>({ baseUrl, fetch: currentFetch });
packaging.use(browserSession);

/**
 * What a screen shows when a call fails. The shape is deliberately small: a sentence a person can
 * act on, and the status for the one case where the sentence depends on it.
 */
export type ApiFailure = { status: number; message: string };

/**
 * <p>`currentT` rather than a hook: this is a plain function called from promise callbacks all over
 * the product, and making it a hook would put React in front of every API call. The provider
 * publishes the locale as it renders and this reads it — see `LocaleProvider.tsx` for what that
 * costs.
 */
export function failureFrom(response: Response | undefined, error: unknown): ApiFailure {
  if (!response) {
    return {
      status: 0,
      message:
        error instanceof Error && error.message
          ? currentT('api.unreachable.detail', { reason: error.message })
          : currentT('api.unreachable'),
    };
  }
  if (response.status === 401) {
    // The gateway's word for it (T-10.2): the session ended and signing in again is the answer.
    // Distinct from 403 below, and the distinction decides whether a screen preserves work or
    // discards it -- see shared/auth/recovery.ts.
    return { status: 401, message: currentT('api.session-ended') };
  }
  if (response.status === 403) {
    return { status: 403, message: currentT('api.forbidden') };
  }
  if (response.status === 404) {
    // The disclosure rule (T-2.4) means a 404 can also be "you may not know this exists", and a
    // screen must not translate it into "it is gone" -- that would be the UI asserting something
    // the API deliberately refused to say.
    return { status: 404, message: currentT('api.not-found') };
  }
  return { status: response.status, message: currentT('api.status', { status: response.status }) };
}
