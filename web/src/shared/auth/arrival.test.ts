import { afterEach, describe, expect, it } from 'vitest';
import { arrivalFor, forgetTheAttempt, rememberTheAttempt } from './arrival.ts';
import { parkWork } from './recovery.ts';
import type { Session } from './session.ts';

/**
 * What a signed-out person meets (T-10.2, T-10.3).
 *
 * <p>The rule this encodes is a distinction the product did not make: arriving with no session and
 * having a session end mid-work are different events. Treating them the same produced a panel that
 * told a first-time visitor what they already knew; treating them the same the other way would
 * send a learner away from the screen saying their exam answers were saved.
 */
const SIGNED_OUT: Session = { signedIn: false, name: null, signInUrl: '/oauth2/authorization/oidc' };

describe('arriving signed out', () => {
  afterEach(() => {
    window.sessionStorage.clear();
  });

  it('opens the sign-in page rather than a panel, on a first visit', () => {
    expect(arrivalFor(SIGNED_OUT)).toEqual({ kind: 'sign-in-now' });
  });

  it('explains instead of redirecting when there is work parked', () => {
    // The forty-minute exam. Sending this person to the issuer without a word is the bug the
    // panel existed for, and it has to keep working now that the panel is conditional.
    parkWork('attempt-submission', { answers: ['a', 'b'] }, '/review/an-attempt');

    expect(arrivalFor(SIGNED_OUT)).toEqual({ kind: 'explain', because: 'parked-work' });
  });

  it('stops after one automatic attempt, rather than looping between two hosts', () => {
    rememberTheAttempt();

    // A redirect loop reads to a person as the site being broken, and leaves them no way to stop
    // it. One attempt, then words.
    expect(arrivalFor(SIGNED_OUT)).toEqual({ kind: 'explain', because: 'came-back-signed-out' });
  });

  it('forgets the attempt once a session exists, so a later sign-out starts clean', () => {
    rememberTheAttempt();
    forgetTheAttempt();

    expect(arrivalFor(SIGNED_OUT)).toEqual({ kind: 'sign-in-now' });
  });

  it('prefers the parked-work explanation over the loop guard', () => {
    // Both true at once: the person signed in, it did not take, and they have answers waiting.
    // What they need to read about is the answers.
    rememberTheAttempt();
    parkWork('attempt-submission', { answers: [] }, '/review/an-attempt');

    expect(arrivalFor(SIGNED_OUT)).toEqual({ kind: 'explain', because: 'parked-work' });
  });
});
