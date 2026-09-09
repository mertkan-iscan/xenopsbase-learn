import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { identity } from '../api/client.ts';
import {
  announceSignedOut,
  csrfHeader,
  onSignedOutElsewhere,
  readSession,
  signOut,
} from './session.ts';

/**
 * The browser's whole share of authentication (T-10.2).
 *
 * The assertion that matters most is the last one: nothing a script can read holds a token. That
 * is not a property of this file being careful -- it is a property of the design, because the
 * gateway never sends one. A test is here anyway, because the way this gets lost is somebody
 * adding a convenience later and nothing noticing.
 */
describe('the session in the browser', () => {
  beforeEach(() => {
    document.cookie = 'XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/';
    // sessionStorage only: under this jsdom `window.localStorage` is a plain object with no
    // `clear`, which is worth knowing and not worth working around -- nothing here writes to it,
    // and the assertion below reads whatever it is rather than assuming a Storage.
    window.sessionStorage.clear();
    vi.stubGlobal('location', { assign: vi.fn(), pathname: '/exam/1', origin: 'http://localhost' });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.restoreAllMocks();
  });

  it('reads who is here', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ signedIn: true, name: 'ayse', signInUrl: '/in' }), {
          headers: { 'content-type': 'application/json' },
        }),
      ),
    );

    await expect(readSession()).resolves.toEqual({
      signedIn: true,
      name: 'ayse',
      signInUrl: '/in',
    });
  });

  it('treats an unreachable gateway as signed out rather than guessing', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('connection refused')));

    await expect(readSession()).resolves.toMatchObject({ signedIn: false });
  });

  it('echoes the CSRF cookie as a header, and sends none when there is no cookie', () => {
    expect(csrfHeader()).toEqual({});

    document.cookie = 'XSRF-TOKEN=abc%2F123; path=/';

    // Decoded: the cookie is percent-encoded on the wire and the header is the raw value.
    expect(csrfHeader()).toEqual({ 'X-XSRF-TOKEN': 'abc/123' });
  });

  it('ends the local session first, then leaves for the issuer', async () => {
    document.cookie = 'XSRF-TOKEN=t; path=/';
    const fetched = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ endSessionUrl: 'https://keycloak/logout?x=1' }), {
        headers: { 'content-type': 'application/json' },
      }),
    );
    vi.stubGlobal('fetch', fetched);

    await signOut();

    const [url, init] = fetched.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/auth/logout');
    expect(init.method).toBe('POST');
    expect((init.headers as Record<string, string>)['X-XSRF-TOKEN']).toBe('t');
    // The issuer's session is ended by NAVIGATING, not by fetching it: fetching a redirect leaves
    // the SSO session alive and makes the next sign-in silent, which looks like sign-out did
    // nothing.
    expect(window.location.assign).toHaveBeenCalledWith('https://keycloak/logout?x=1');
  });

  it('still signs out locally when the gateway cannot be reached', async () => {
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new Error('offline')));

    await signOut();

    expect(window.location.assign).toHaveBeenCalledWith('/');
  });

  it('tells the other tabs, so none of them keeps showing a name', async () => {
    const noticed = vi.fn();
    const stop = onSignedOutElsewhere(noticed);

    announceSignedOut();
    await vi.waitFor(() => expect(noticed).toHaveBeenCalled());

    stop();
  });

  /**
   * T-10.2's last criterion, in the only form a test can take.
   *
   * A script in an embedded frame on this origin can read `localStorage`, `sessionStorage` and
   * every cookie that is not `HttpOnly`. So the assertion is that after a signed-in session has
   * been read and a call has been made, none of those holds anything token-shaped -- and that the
   * API client attaches no `Authorization` header of its own, because it has nothing to attach.
   *
   * The other half of the guarantee is the session cookie's `HttpOnly` flag, which is set by the
   * gateway and asserted there: jsdom cannot see the flag, only the server can promise it.
   */
  it('leaves no token anywhere a script in an embedded frame could read it', async () => {
    // A fresh Response per call: a body can only be read once, and reusing one turns the second
    // call into "Body is unusable" -- an error about the stub wearing the costume of an error
    // about the client.
    const fetched = vi.fn(() =>
      Promise.resolve(
        new Response(JSON.stringify({ signedIn: true, name: 'ayse', signInUrl: '/in' }), {
          headers: { 'content-type': 'application/json' },
        }),
      ),
    );
    vi.stubGlobal('fetch', fetched);

    await readSession();
    await identity.GET('/api/v1/me');

    const readable = [
      JSON.stringify(window.localStorage),
      JSON.stringify(window.sessionStorage),
      document.cookie,
    ].join(' ');
    expect(readable).not.toMatch(/eyJ/); // a JWT's base64 header, whatever else it holds
    expect(readable.toLowerCase()).not.toContain('bearer');

    const lastCall = fetched.mock.calls.at(-1) as unknown as [Request];
    expect(lastCall[0].headers.get('authorization')).toBeNull();
  });
});
