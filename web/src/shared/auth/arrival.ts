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

export type Arrival =
  /** Nobody is here and nothing is at stake: send them to the issuer. */
  | { kind: 'sign-in-now' }
  /** Say what happened and let them choose, because something would be lost or already went wrong. */
  | { kind: 'explain'; because: 'parked-work' | 'came-back-signed-out' };

export function arrivalFor(session: Session): Arrival {
  if (hasParkedWork()) {
    return { kind: 'explain', because: 'parked-work' };
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
