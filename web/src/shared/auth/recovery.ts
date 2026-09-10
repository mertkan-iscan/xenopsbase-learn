/**
 * Work that must survive signing in again (T-10.2).
 *
 * The case this exists for, in the issue's words: a learner submits a forty-minute exam and the
 * session ended thirty seconds ago. If that loses the submission it will be the worst bug in the
 * product, and a customer will find it rather than us.
 *
 * ## Why this can be written at all
 *
 * Because there is no token in the browser. Signing in again is a full-page navigation to the
 * issuer and back, so anything held in memory is gone -- which means the work has to be written
 * somewhere, and writing it is only safe because the only thing being written is the person's own
 * answers. In a design where the frontend held a token, the same mechanism would be a queue of
 * requests with credentials attached, sitting in storage.
 *
 * ## sessionStorage, not localStorage
 *
 * Per tab, and cleared when the tab closes. Two tabs mid-exam are two different attempts, and
 * localStorage would hand one tab's answers to the other. It also means parked work cannot outlive
 * the browser session, which is the right lifetime for something nobody has confirmed they still
 * want.
 */
const KEY = 'learn.parked-work';

export type ParkedWork = {
  /** What kind of work this is, so a screen only picks up its own. */
  label: string;
  /** Where the person was, so they come back to it rather than to the home screen. */
  route: string;
  /** The payload of the call that could not be made. Never a credential. */
  payload: unknown;
  parkedAt: string;
};

export function parkWork(label: string, payload: unknown, route = window.location.pathname): void {
  const work: ParkedWork = { label, route, payload, parkedAt: new Date().toISOString() };
  try {
    window.sessionStorage.setItem(KEY, JSON.stringify(work));
  } catch {
    // Storage can be unavailable (a private window, a policy). Losing the parking is bad;
    // throwing here would ALSO lose the sign-in that was about to happen, which is worse.
  }
}

/**
 * Is there parked work, without taking it?
 *
 * <p>Separate from {@link takeParkedWork} because that one REMOVES, deliberately — reading it
 * twice would replay a submission twice. The shell needs to ask the question without answering
 * it: somebody whose exam answers are parked must be told so rather than bounced silently to the
 * issuer, and the screen that will actually replay them is not this one.
 */
export function hasParkedWork(): boolean {
  try {
    return window.sessionStorage.getItem(KEY) !== null;
  } catch {
    // Storage unavailable is not "there is work" -- guessing yes here would strand a first-time
    // visitor on a panel about answers they never wrote.
    return false;
  }
}

/** Reads and removes the parked work. Reading it twice would replay a submission twice. */
export function takeParkedWork(label?: string): ParkedWork | null {
  let raw: string | null;
  try {
    raw = window.sessionStorage.getItem(KEY);
  } catch {
    return null;
  }
  if (!raw) {
    return null;
  }
  let work: ParkedWork;
  try {
    work = JSON.parse(raw) as ParkedWork;
  } catch {
    window.sessionStorage.removeItem(KEY);
    return null;
  }
  if (label && work.label !== label) {
    return null;
  }
  window.sessionStorage.removeItem(KEY);
  return work;
}

export type Attempt = { response?: Response };

export type Outcome =
  | { kind: 'done'; response: Response }
  | { kind: 'session-ended' }
  | { kind: 'failed'; response?: Response };

/**
 * Make a call that must not lose its payload if the session has ended.
 *
 * On 401 the payload is parked and the caller is told to send the person to sign in. On anything
 * else the outcome is handed back unchanged -- a 403 is not recoverable by signing in, and parking
 * the work there would leave a submission nobody will ever replay.
 */
export async function withSessionRecovery(
  work: { label: string; payload: unknown },
  send: () => Promise<Attempt>,
): Promise<Outcome> {
  const { response } = await send();
  if (!response) {
    return { kind: 'failed' };
  }
  if (response.status === 401) {
    parkWork(work.label, work.payload);
    return { kind: 'session-ended' };
  }
  return response.ok ? { kind: 'done', response } : { kind: 'failed', response };
}
