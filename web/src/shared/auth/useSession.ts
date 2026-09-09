import { useEffect, useState } from 'react';
import { onSignedOutElsewhere, readSession, type Session } from './session.ts';

/**
 * The session, as a screen sees it (T-10.2).
 *
 * `null` while the answer is unknown, which is a third state rather than an inconvenience: showing
 * a sign-in prompt during the moment before the gateway has answered would flash it at everybody
 * who is, in fact, signed in.
 *
 * The subscription is what makes a second tab honest. Signing out in one tab has already ended the
 * session for every tab -- the credential is one cookie -- so without this the others keep showing
 * a name and a menu whose every click fails.
 */
export function useSession(): Session | null {
  const [session, setSession] = useState<Session | null>(null);

  useEffect(() => {
    let current = true;
    void readSession().then((answer) => {
      if (current) {
        setSession(answer);
      }
    });
    const stop = onSignedOutElsewhere(() => {
      if (current) {
        setSession((was) => (was ? { ...was, signedIn: false, name: null } : was));
      }
    });
    return () => {
      current = false;
      stop();
    };
  }, []);

  return session;
}
