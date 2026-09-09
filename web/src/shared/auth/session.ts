/**
 * What this browser knows about being signed in, which is deliberately almost nothing (T-10.2).
 *
 * There is no token here. There is no expiry, no refresh timer and no retry-because-the-token-
 * expired, because the gateway holds the tokens and renews them server-side before they expire
 * (see its `SecurityConfiguration`). What the frontend keeps is one boolean and a name to show in
 * a corner.
 *
 * That is the point rather than a simplification: this application also renders uploaded SCORM
 * packages and third-party embeds, and a token in a variable here is a token those can reach.
 */
const CHANNEL = 'learn.session';

export type Session = { signedIn: boolean; name: string | null; signInUrl: string };

const signedOut: Session = {
  signedIn: false,
  name: null,
  signInUrl: '/oauth2/authorization/oidc',
};

/**
 * Ask the gateway who is here.
 *
 * Also the call that makes the CSRF cookie exist, which is why the application makes it before
 * anything else: Spring writes that cookie lazily, and without a read first the very first write
 * of a session is refused once, in a way that looks random.
 */
export async function readSession(): Promise<Session> {
  try {
    const response = await fetch('/auth/session', {
      credentials: 'same-origin',
      headers: { Accept: 'application/json' },
    });
    if (!response.ok) {
      return signedOut;
    }
    return (await response.json()) as Session;
  } catch {
    // The gateway being unreachable is not the same as being signed out, but from a screen's
    // point of view the next thing to do is identical, and pretending to know the difference
    // would mean showing a signed-in shell that cannot load anything.
    return signedOut;
  }
}

/** Leave for the identity provider. A full navigation, because sign-in is not an XHR. */
export function signIn(session: Session = signedOut): void {
  window.location.assign(session.signInUrl);
}

/**
 * End the session here, then at the issuer.
 *
 * Order matters and it is the opposite of the obvious one. The local session goes first, so a
 * person who never reaches the issuer -- closes the tab, loses the network -- is still signed out
 * of this product. Then the browser navigates to the end-session URL, because signing out of the
 * product while leaving the SSO session alive means the next sign-in is silent and looks like the
 * sign-out did nothing.
 */
export async function signOut(): Promise<void> {
  let endSessionUrl: string | null = null;
  try {
    const response = await fetch('/auth/logout', {
      method: 'POST',
      credentials: 'same-origin',
      headers: { ...csrfHeader(), Accept: 'application/json' },
    });
    if (response.ok) {
      endSessionUrl = ((await response.json()) as { endSessionUrl: string | null }).endSessionUrl;
    }
  } catch {
    // Nothing to do but tell the other tabs and stop showing a signed-in shell.
  }
  announceSignedOut();
  window.location.assign(endSessionUrl ?? '/');
}

/**
 * The CSRF header for a write.
 *
 * The cookie is readable by script on purpose: it is not a credential, it is a value this origin
 * has to echo back. What makes it work is that a page on another origin can send the cookie and
 * cannot read it, so it cannot produce the header.
 */
export function csrfHeader(): Record<string, string> {
  const token = document.cookie
    .split('; ')
    .find((entry) => entry.startsWith('XSRF-TOKEN='))
    ?.slice('XSRF-TOKEN='.length);
  return token ? { 'X-XSRF-TOKEN': decodeURIComponent(token) } : {};
}

/**
 * Multi-tab, in a few lines.
 *
 * The session is a cookie, so signing out in one tab has already signed the others out -- the
 * failure this prevents is not a security one, it is a person looking at a second tab that still
 * shows their name and clicking things that all fail. The channel tells the others to stop
 * pretending.
 */
export function onSignedOutElsewhere(react: () => void): () => void {
  if (typeof BroadcastChannel === 'undefined') {
    return () => {};
  }
  const channel = new BroadcastChannel(CHANNEL);
  channel.onmessage = (event: MessageEvent) => {
    if (event.data === 'signed-out') {
      react();
    }
  };
  return () => channel.close();
}

export function announceSignedOut(): void {
  if (typeof BroadcastChannel === 'undefined') {
    return;
  }
  const channel = new BroadcastChannel(CHANNEL);
  channel.postMessage('signed-out');
  channel.close();
}
