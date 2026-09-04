import { useCallback, useEffect, useRef, useState } from 'react';
import { streaming } from '../shared/api/client.ts';

/**
 * The token lifecycle, which is the part of the player that is not really about video (T-3.5).
 *
 * <p>A playback token lives five minutes and cannot be recalled from the edge (ADR-0101, T-3.4).
 * That makes renewal the mechanism the whole revocation story rests on: the server bounds a
 * suspension at one token lifetime *only if* the player actually comes back for the next one. A
 * player that fetched once and cached for the session would make the five minutes decorative.
 *
 * <h2>It renews when the server says to, not when the token expires</h2>
 *
 * The response carries `renewAfter`, and this schedules on that rather than computing something
 * from `expiresAt`. Two reasons. A player that renews at expiry has already stalled — the request
 * takes time and the manifest reload takes more. And the cadence stays a server decision that can
 * be changed by editing `streaming.playback.renew-after`, without shipping a browser release to
 * everyone who embedded us.
 *
 * <h2>A failed renewal is not an error yet</h2>
 *
 * At `renewAfter` there are still two minutes of valid playback left, so a failure retries inside
 * that window and the learner sees nothing. Only when the current token has actually expired does
 * this surface a failure, because only then has the video stopped. This is the difference between
 * a player that reports every transient blip and one that is quiet until something is really
 * wrong.
 *
 * <h2>What a refusal means</h2>
 *
 * The mint is the entitlement decision, so a refusal mid-playback is meaningful: an assignment was
 * revoked, a gate closed, an account was suspended. Those are terminal — retrying will not change
 * them, and a player that retried a 403 in a loop would be minting-rate-limit fodder. A network
 * failure is not terminal and is retried.
 */

/** What the server answers, narrowed to what the player uses. */
export type Playback = {
  nodeId: string;
  videoAssetId: string;
  token: string;
  manifestUrl: string;
  expiresAt: string;
  renewAfter: string;
};

export type TokenState =
  | { status: 'loading' }
  | { status: 'ready'; playback: Playback }
  /** Playing, but the last renewal failed and the current token is still valid. */
  | { status: 'renewing'; playback: Playback }
  | { status: 'refused'; message: string; terminal: boolean };

/**
 * Refusal messages, from the status and the code the service sends (T-3.4's `RefusalReason`).
 *
 * Three different reasons deliberately answer one bare 404 so a caller cannot probe the
 * difference between "no such node", "not assigned to you" and "you hold no permission". This
 * says the one true thing all three have in common rather than guessing which it was — the
 * service refused to disclose it, and a UI that guessed would be undoing that on its behalf.
 */
export function refusalMessage(status: number, code: string | undefined): string {
  if (code === 'ACCOUNT_SUSPENDED') {
    return 'This account is suspended. Contact your administrator.';
  }
  if (code === 'ACCOUNT_READ_ONLY') {
    return 'This account is read only, so new playback cannot start.';
  }
  if (code === 'CONTENT_GATED') {
    return 'This content is not available yet.';
  }
  if (code === 'NOT_PLAYABLE') {
    return 'This video is still being processed. Try again shortly.';
  }
  if (status === 429) {
    return 'Too many playback requests. Wait a moment and try again.';
  }
  if (status === 404) {
    return 'This video is not available to you.';
  }
  if (status === 401) {
    return 'You are not signed in.';
  }
  return 'Playback could not be started.';
}

/** A refusal the player should not retry: the answer will not change by asking again. */
function isTerminal(status: number): boolean {
  return status === 401 || status === 403 || status === 404;
}

type Body = { error?: { code?: string; message?: string } };

export function usePlaybackToken(nodeId: string): TokenState {
  const [state, setState] = useState<TokenState>({ status: 'loading' });

  // The timer and the current token live in refs because renewal is a loop that must not restart
  // when React re-renders: a timeout recreated on every render is a renewal that never fires, or
  // fires far too often.
  const timer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined);
  const current = useRef<Playback | undefined>(undefined);
  const live = useRef(true);

  const mint = useCallback(
    async function mint(): Promise<void> {
      try {
        const { data, response, error } = await streaming.POST(
          '/api/v1/me/nodes/{id}/playback-token',
          { params: { path: { id: nodeId } } },
        );

        if (!live.current) {
          return;
        }
        if (data) {
          const playback = data as Playback;
          current.current = playback;
          setState({ status: 'ready', playback });
          schedule(playback);
          return;
        }

        const status = response?.status ?? 0;
        const code = (error as Body | undefined)?.error?.code;
        const held = current.current;
        // A refusal while a valid token is still in hand: the video keeps playing to the end of
        // that token, and the learner is told only if it runs out. Anything else would interrupt
        // playback that the edge is perfectly happy to keep serving.
        if (held && !expired(held) && !isTerminal(status)) {
          setState({ status: 'renewing', playback: held });
          retry();
          return;
        }
        setState({
          status: 'refused',
          message: refusalMessage(status, code),
          terminal: isTerminal(status),
        });
      } catch (unreachable) {
        if (!live.current) {
          return;
        }
        const held = current.current;
        if (held && !expired(held)) {
          setState({ status: 'renewing', playback: held });
          retry();
          return;
        }
        setState({
          status: 'refused',
          message:
            unreachable instanceof Error && unreachable.message
              ? `Could not reach the service: ${unreachable.message}`
              : 'Could not reach the service.',
          terminal: false,
        });
      }

      function schedule(playback: Playback) {
        const delay = Math.max(0, Date.parse(playback.renewAfter) - Date.now());
        clearTimeout(timer.current);
        timer.current = setTimeout(() => void mint(), delay);
      }

      // Every few seconds until the token in hand runs out. Short, because the window it is
      // retrying inside is only the gap between renewAfter and expiry.
      function retry() {
        clearTimeout(timer.current);
        timer.current = setTimeout(() => void mint(), 5000);
      }
    },
    [nodeId],
  );

  useEffect(() => {
    live.current = true;
    current.current = undefined;
    setState({ status: 'loading' });
    void mint();
    return () => {
      // Both matter. `live` stops an in-flight response from setting state on an unmounted
      // component; clearing the timer stops the renewal loop from outliving the player and
      // quietly minting tokens for a video nobody is watching — which is exactly the traffic the
      // server's rate limit exists to refuse.
      live.current = false;
      clearTimeout(timer.current);
    };
  }, [mint]);

  return state;
}

function expired(playback: Playback): boolean {
  return Date.parse(playback.expiresAt) <= Date.now();
}
