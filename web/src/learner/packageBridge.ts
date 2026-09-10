import { packaging } from '../shared/api/client.ts';

/**
 * The half of the SCORM launch chain that lives on the application's origin (T-4.4, ADR-0105).
 *
 * <h2>Why there is a bridge at all</h2>
 *
 * <p>An uploaded package runs inside a wrapper on the tenant's CONTENT ORIGIN, which holds no
 * cookie, no token and nothing worth stealing — that is the whole decision, and it means the
 * wrapper cannot save anything. It posts the data model here; this has the learner's session and
 * makes the call.
 *
 * <p>So the two ends are two origins that name each other exactly. Every check below is on that
 * boundary, and none of them is optional:
 *
 * <ul>
 *   <li><b>`event.origin` by equality, never `endsWith`.</b>
 *       {@code acme.usercontent.example.com.evil.test} ends with something useful to an attacker
 *       and equals nothing.
 *   <li><b>`event.source` is the frame we opened.</b> Otherwise any other frame on the page, or a
 *       popup, can post a message that looks like the wrapper's and write into a learner's
 *       compliance record.
 *   <li><b>`targetOrigin` is the content origin, never `'*'`.</b> The state we send back is the
 *       learner's own answers and score.
 * </ul>
 *
 * <h2>What crosses, and what deliberately does not</h2>
 *
 * <p>The wrapper sends the CMI data model. It does not send, and this does not accept, a claim
 * that the learner is complete — "completion is derived by the server" (ADR-0107), and the
 * derivation is in `Cmi.java`. A message here carries what the package stored, in the standard's
 * own vocabulary, and nothing about what it means.
 */

/** The wrapper's half of the protocol. Anything else on the channel is dropped. */
const FROM_WRAPPER = 'xenopslearn.scorm';

/** Ours. The wrapper checks for it, so a message from anything else on our origin is ignored. */
const FROM_APP = 'xenopslearn.app';

type WrapperMessage =
  | { type: 'ready'; payload?: { profile?: string | null } }
  | { type: 'opened' }
  | { type: 'initialized' }
  | { type: 'set'; payload?: { element?: string; value?: string } }
  | { type: 'commit'; payload?: { data?: Record<string, string>; addedSeconds?: number } }
  | { type: 'terminated' };

/** What a screen needs to render while a package is open. */
export type RuntimeSnapshot = {
  data: Record<string, string>;
  entry: string;
  completed: boolean;
  passed: boolean | null;
  launches: number;
  secondsSpent: number;
  /** What the package says THIS launch has lasted, and */
  sessionSeconds: number;
  /** every launch added together. Two numbers, because SCORM draws the distinction and so must we. */
  totalSeconds: number;
  /**
   * The launch that owns this registration.
   *
   * <p>Quoted back on every save. The most recent launch wins, so a save from a tab that was
   * superseded is refused rather than merged — two tabs hold two whole data models and there is no
   * merge of them that means anything to the package that wrote them.
   */
  session: string | null;
  launchUrl: string | null;
  contentOrigin: string;
};

/**
 * A save that was refused because another launch has taken the registration.
 *
 * <p>Its own type rather than a boolean on the result, because the caller has to do something
 * different: not retry, not report a failure, but stop and tell the learner. Everything else that
 * can go wrong on a save is recovered by the next commit carrying the whole data model again.
 */
export class Superseded extends Error {}

/**
 * Opens the learner's runtime for a package.
 *
 * <p>A read that creates on first sight, and that is what counts a launch: `cmi.core.entry` has to
 * answer `ab-initio` the first time and `resume` afterwards, which is only knowable if asking is
 * the thing that records it.
 */
export async function openRuntime(
  packageId: string,
  nodeId: string | undefined,
): Promise<RuntimeSnapshot | null> {
  const { data } = await packaging.GET('/api/v1/me/runtime/{packageId}', {
    params: { path: { packageId }, query: nodeId ? { nodeId } : {} },
  });
  return data ? snapshot(data) : null;
}

/**
 * Stores what the package left. Returns what the platform derived, for a screen to render.
 *
 * @throws Superseded when another launch has taken this registration
 */
export async function saveRuntime(
  packageId: string,
  nodeId: string | undefined,
  data: Record<string, string>,
  addedSeconds: number,
  session: string | null,
): Promise<RuntimeSnapshot | null> {
  const { data: saved, response } = await packaging.PUT('/api/v1/me/runtime/{packageId}', {
    params: { path: { packageId }, query: nodeId ? { nodeId } : {} },
    body: { data, addedSeconds, ...(session ? { session } : {}) },
  });
  if (response.status === 409) {
    throw new Superseded();
  }
  /*
   * 429 IS NOT AN ERROR HERE, and treating it as one would be the wrong shape twice over.
   *
   * The server keeps a write budget per registration and refuses above it, which is safe precisely
   * because every commit carries the whole data model: the next one restores everything this one
   * would have. So it falls through to `null` — nothing to render, nothing to report, and the
   * wrapper is neither told nor stopped.
   */
  return saved ? snapshot(saved) : null;
}

function snapshot(view: {
  data?: Record<string, string>;
  entry?: string;
  completed?: boolean;
  passed?: boolean;
  launches?: number;
  secondsSpent?: number;
  sessionSeconds?: number;
  totalSeconds?: number;
  session?: string;
  launchUrl?: string;
  contentOrigin?: string;
}): RuntimeSnapshot {
  return {
    data: view.data ?? {},
    entry: view.entry ?? 'ab-initio',
    completed: view.completed === true,
    // `undefined` and `false` are different answers here: a package with no test says nothing
    // about passing, and rendering that as "failed" would put a fail beside every reading module.
    passed: view.passed === undefined ? null : view.passed,
    launches: view.launches ?? 0,
    secondsSpent: view.secondsSpent ?? 0,
    sessionSeconds: view.sessionSeconds ?? 0,
    totalSeconds: view.totalSeconds ?? 0,
    session: view.session ?? null,
    launchUrl: view.launchUrl ?? null,
    contentOrigin: view.contentOrigin ?? '',
  };
}

/**
 * Listens to one wrapper frame and relays what it says.
 *
 * @param frame the iframe element. Its `contentWindow` is the only sender accepted
 * @param onSaved called after every successful save, with what the platform derived from it
 * @returns a function that stops listening. Call it on unmount, or a learner who opens two
 *          packages in one session gets two listeners and the second package's commits are written
 *          twice
 */
export function bridgeToWrapper({
  frame,
  contentOrigin,
  packageId,
  nodeId,
  state,
  learner,
  supersededText,
  onSaved,
  onSuperseded,
}: {
  frame: HTMLIFrameElement;
  contentOrigin: string;
  packageId: string;
  nodeId: string | undefined;
  state: RuntimeSnapshot;
  /**
   * Who is watching, for the packages that display it.
   *
   * <p>`cmi.core.student_name` is read-only and LMS-supplied, so a course that greets the learner
   * by name is reading a value only this side can provide. It comes from the session the
   * application already holds rather than from a call: the wrapper has no credential and could not
   * ask anybody.
   */
  learner: { id: string; name: string } | null;
  /** What to show over a superseded course, translated here because the wrapper has no catalogue. */
  supersededText: string;
  onSaved: (snapshot: RuntimeSnapshot) => void;
  /** Another launch has taken the registration. This one stops saving and says so. */
  onSuperseded: () => void;
}): () => void {
  let stopped = false;
  let session = state.session;

  function reply(type: string, payload?: unknown) {
    const target = frame.contentWindow;
    if (!target) {
      return;
    }
    // Named exactly. '*' here would hand the learner's saved answers to whatever origin happened
    // to be hosting the frame at that moment.
    target.postMessage({ source: FROM_APP, type, payload }, contentOrigin);
  }

  async function onMessage(event: MessageEvent) {
    if (stopped) {
      return;
    }
    // Both checks, always. See the header: neither is sufficient alone, and a failure of either is
    // dropped in silence -- a reply saying "wrong origin" helps somebody find the right one.
    if (event.origin !== contentOrigin || event.source !== frame.contentWindow) {
      return;
    }
    const message = event.data as ({ source?: string } & WrapperMessage) | null;
    if (!message || message.source !== FROM_WRAPPER) {
      return;
    }

    if (message.type === 'ready') {
      // The wrapper is waiting to be told where the learner got to. It opens the course anyway
      // after a short timeout, so a failure here costs a resume rather than the whole launch.
      reply('state', { data: state.data, entry: state.entry, learner });
      return;
    }

    if (message.type === 'commit') {
      const data = message.payload?.data ?? {};
      const addedSeconds = message.payload?.addedSeconds ?? 0;
      try {
        const saved = await saveRuntime(packageId, nodeId, data, addedSeconds, session);
        if (saved && !stopped) {
          // The session can only ever be confirmed, never changed -- an open is what moves it --
          // but reading it back keeps this honest if that ever stops being true.
          session = saved.session;
          onSaved(saved);
        }
      } catch (refusal) {
        if (refusal instanceof Superseded) {
          /*
           * ANOTHER LAUNCH HAS THE COURSE, almost always this learner in another tab.
           *
           * The wrapper is told so it stops committing and covers the package with a message, and
           * the screen is told so it can say the same thing outside the frame. Both, because the
           * frame is where the learner is looking and the screen is what they will still be able
           * to read after the package navigates itself somewhere.
           */
          if (!stopped) {
            reply('superseded', { text: supersededText });
            onSuperseded();
          }
          return;
        }
        /*
         * A FAILED SAVE IS NOT SHOWN TO THE LEARNER, and that is deliberate rather than lazy.
         *
         * The package has already moved on; there is nothing they could do about it and no action
         * to offer. What matters is that the next commit sends the WHOLE data model again -- a
         * package's `Commit` means "this is the state", not "this is what changed" -- so a
         * dropped save is recovered by the next one rather than lost. `LMSFinish` forces a commit
         * for exactly this reason, which makes the last one the one that counts.
         */
      }
    }
  }

  const handler = (event: MessageEvent) => void onMessage(event);
  window.addEventListener('message', handler);
  return () => {
    stopped = true;
    window.removeEventListener('message', handler);
  };
}
