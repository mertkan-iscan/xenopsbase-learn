import { hasParkedWork } from './recovery.ts';
import type { Session } from './session.ts';

/**
 * What a signed-out person should meet: the issuer, or a panel (T-10.2, T-10.3).
 *
 * <p>Arriving at the front door with no session is not the same event as a session ending while
 * somebody was working, and the product got this wrong by treating them the same. A first visitor
 * met a panel telling them they were signed out — which they knew — with a button doing the only
 * thing available. That is a click nobody chose to make.
 *
 * <p>But bouncing EVERY signed-out render to the issuer is the other bug, and it is the expensive
 * one. Signing in again is a full-page navigation, so anything in memory is gone: the learner who
 * submitted a forty-minute exam thirty seconds after their session ended needs to be told their
 * answers are safe, not silently sent away from the screen that says so. `recovery.ts` exists for
 * exactly that case, and this is the decision that keeps it reachable.
 */
const ATTEMPTED = 'learn.sign-in-attempted';
const SIGNED_OUT = 'learn.signed-out-on-purpose';

export type Arrival =
  /** Nobody is here and nothing is at stake: send them to the issuer. */
  | { kind: 'sign-in-now' }
  /** Say what happened and let them choose, because something would be lost or already went wrong. */
  | { kind: 'explain'; because: 'parked-work' | 'came-back-signed-out' | 'signed-out' };

export function arrivalFor(session: Session): Arrival {
  if (hasParkedWork()) {
    return { kind: 'explain', because: 'parked-work' };
  }
  // SIGNING OUT MUST NOT BE UNDONE BY THE FRONT DOOR.
  //
  // Sign-out ends this application's session and then sends the browser to the issuer's
  // end-session endpoint, which returns it here. Arriving here signed out is exactly the
  // condition the automatic sign-in above was written for -- so without this, the click lands
  // back at the issuer, and if the SSO session there has not gone the person is signed straight
  // back in without ever seeing a form. From their side the button did nothing.
  //
  // Read and cleared in one go: the next arrival is an ordinary one.
  if (justSignedOut()) {
    return { kind: 'explain', because: 'signed-out' };
  }
  // THE LOOP GUARD. If sign-in has already been tried in this tab and the answer is still "signed
  // out", sending them again produces a redirect loop between two hosts, which reads to a person
  // as the site being broken and leaves no way to stop it. One automatic attempt, then words.
  if (alreadyTried()) {
    return { kind: 'explain', because: 'came-back-signed-out' };
  }
  void session;
  return { kind: 'sign-in-now' };
}

function justSignedOut(): boolean {
  try {
    const deliberate = window.sessionStorage.getItem(SIGNED_OUT) !== null;
    window.sessionStorage.removeItem(SIGNED_OUT);
    return deliberate;
  } catch {
    return false;
  }
}

/** Called by `signOut` before it navigates away, while this tab still has storage. */
export function rememberTheSignOut(): void {
  try {
    window.sessionStorage.setItem(SIGNED_OUT, new Date().toISOString());
    // A deliberate sign-out also ends the loop guard's memory: the next sign-in is a fresh
    // attempt, not a retry of whatever happened before it.
    window.sessionStorage.removeItem(ATTEMPTED);
  } catch {
    // Without storage the front door signs them back in, which is the behaviour this fixes and
    // not one worth throwing over -- the sign-out itself has already happened.
  }
}

function alreadyTried(): boolean {
  try {
    return window.sessionStorage.getItem(ATTEMPTED) !== null;
  } catch {
    // No storage means no loop guard, so the safe answer is the one that cannot loop.
    return true;
  }
}

/** Called immediately before navigating to the issuer. */
export function rememberTheAttempt(): void {
  try {
    window.sessionStorage.setItem(ATTEMPTED, new Date().toISOString());
  } catch {
    // Nothing to do: `alreadyTried` fails closed, so a tab without storage shows the panel.
  }
}

/** Called once a session exists, so a later sign-out starts from a clean slate. */
export function forgetTheAttempt(): void {
  try {
    window.sessionStorage.removeItem(ATTEMPTED);
  } catch {
    // Ignored for the same reason as above.
  }
}
