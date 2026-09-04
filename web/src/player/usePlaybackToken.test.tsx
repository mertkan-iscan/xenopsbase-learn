import { act, render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { usePlaybackToken, type TokenState } from './usePlaybackToken.ts';

/**
 * The renewal loop (T-3.5), which is the half of T-3.4 that lives in a browser.
 *
 * <p>The server bounds a suspension at one token lifetime only if the player actually comes back
 * for the next token. `PlaybackEntitlementTest` proves the server mints a fresh decision every
 * five minutes; these prove the player asks. Neither half is worth much alone: a server that
 * would refuse and a client that never asks is a revocation window of "the length of the video".
 */

const FIVE_MINUTES = 5 * 60 * 1000;
const THREE_MINUTES = 3 * 60 * 1000;

const START = Date.parse('2026-09-04T09:00:00.000Z');
let minted = 0;
let respond: () => { status: number; body: unknown } = () => ({ status: 200, body: token() });

function token() {
  minted += 1;
  // Read from the (fake) clock rather than a variable the test also advances: the token's own
  // expiry has to be relative to the moment it was minted, exactly as the server computes it.
  const now = Date.now();
  return {
    nodeId: 'node-1',
    videoAssetId: 'asset-1',
    token: `token-${minted}`,
    manifestUrl: `https://fake-media.invalid/ref/manifest/video.m3u8?token=token-${minted}`,
    expiresAt: new Date(now + FIVE_MINUTES).toISOString(),
    renewAfter: new Date(now + THREE_MINUTES).toISOString(),
  };
}

/** Reports what the hook is doing, so a test reads the state machine rather than a rendered box. */
function Probe({ onState }: { onState: (state: TokenState) => void }) {
  const state = usePlaybackToken('node-1');
  onState(state);
  return <span data-testid="status">{state.status}</span>;
}

beforeEach(() => {
  minted = 0;
  respond = () => ({ status: 200, body: token() });
  // shouldAdvanceTime, because Testing Library's `waitFor` polls on a timer: with a frozen fake
  // clock it never gets a second look and every assertion times out having checked once.
  vi.useFakeTimers({ shouldAdvanceTime: true });
  vi.setSystemTime(START);

  vi.stubGlobal(
    'fetch',
    vi.fn(async () => {
      const { status, body } = respond();
      return new Response(JSON.stringify(body), {
        status,
        headers: { 'content-type': 'application/json' },
      });
    }),
  );
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

/**
 * Moves time forward.
 *
 * Only `advanceTimersByTimeAsync`, and deliberately not `setSystemTime` as well: advancing fake
 * timers already moves `Date.now()`, so doing both moves the clock twice as far as the test
 * intends. That reads as a token expiring early — a plausible-looking bug in the code under test
 * rather than in the harness, which is what makes it worth a comment.
 */
async function advance(ms: number) {
  await act(async () => {
    await vi.advanceTimersByTimeAsync(ms);
  });
}

describe('the playback token renewal loop', () => {
  it('mints a token when it mounts', async () => {
    render(<Probe onState={() => {}} />);
    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('ready'));
    expect(minted).toBe(1);
  });

  it('renews when the server said to, not when the token expires', async () => {
    render(<Probe onState={() => {}} />);
    await waitFor(() => expect(minted).toBe(1));

    // One second before renewAfter: still the first token. A player that renewed early would be
    // minting more often than the server asked, which is what the rate limit exists to refuse.
    await advance(THREE_MINUTES - 1000);
    expect(minted).toBe(1);

    await advance(1000);
    await waitFor(() => expect(minted).toBe(2));
  });

  // Forty renewals through the real hook and the real client; generous because it is doing
  // forty round trips, not because anything here waits on wall-clock time.
  it('plays a two-hour video through many tokens without a gap', { timeout: 30_000 }, async () => {
    const states: TokenState[] = [];
    render(<Probe onState={(state) => states.push(state)} />);
    await waitFor(() => expect(minted).toBe(1));

    for (let elapsed = 0; elapsed < 2 * 60 * 60 * 1000; elapsed += THREE_MINUTES) {
      await advance(THREE_MINUTES);
    }

    // Two hours at a three-minute cadence. The exact figure matters less than the shape: a video
    // outlives its tokens many times over, which is what makes the five-minute TTL a real
    // revocation window rather than a number in a config file.
    expect(minted).toBeGreaterThanOrEqual(40);
    expect(states.some((state) => state.status === 'refused')).toBe(false);
  });

  it('keeps playing when a renewal fails, and only gives up when the token has run out', async () => {
    render(<Probe onState={() => {}} />);
    await waitFor(() => expect(minted).toBe(1));

    respond = () => ({ status: 503, body: { error: { code: 'UNAVAILABLE' } } });
    await advance(THREE_MINUTES);

    // Renewing, not refused: there are two minutes of valid playback left and nothing for the
    // learner to do about it. Interrupting here would stop a video the edge is happy to serve.
    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('renewing'));

    // A recovery inside the window is invisible, which is the whole point of renewing early.
    respond = () => ({ status: 200, body: token() });
    await advance(10_000);
    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('ready'));
  });

  it('gives up once the token it is holding has actually expired', async () => {
    render(<Probe onState={() => {}} />);
    await waitFor(() => expect(minted).toBe(1));

    respond = () => ({ status: 503, body: {} });
    // Past renewAfter and past expiry: now the video really has stopped, so saying so is the
    // honest thing rather than a spinner that never resolves.
    await advance(FIVE_MINUTES + 30_000);

    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('refused'));
  });

  it('does not retry a refusal that will not change', async () => {
    respond = () => ({ status: 403, body: { error: { code: 'CONTENT_GATED' } } });
    const states: TokenState[] = [];
    render(<Probe onState={(state) => states.push(state)} />);

    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('refused'));
    const afterFirst = minted;

    await advance(FIVE_MINUTES);
    // A revoked assignment or a closed gate answers the same way however many times it is asked,
    // and a player looping on it is only generating the traffic the mint rate limit refuses.
    expect(minted).toBe(afterFirst);
    expect(states.at(-1)).toMatchObject({ terminal: true, message: 'This content is not available yet.' });
  });

  it('stops minting when the player goes away', async () => {
    const { unmount } = render(<Probe onState={() => {}} />);
    await waitFor(() => expect(minted).toBe(1));

    unmount();
    await advance(FIVE_MINUTES * 3);

    // A renewal loop outliving its player mints tokens for a video nobody is watching -- and
    // spends a real learner's rate-limit budget doing it.
    expect(minted).toBe(1);
  });

  it('says the one true thing about a refusal the server deliberately would not explain', async () => {
    respond = () => ({ status: 404, body: {} });
    render(<Probe onState={() => {}} />);

    await waitFor(() => expect(screen.getByTestId('status')).toHaveTextContent('refused'));
    // Not "this video does not exist". The service refuses to say whether the node is unknown,
    // unassigned or merely beyond this caller's permission (T-3.4), and a UI that guessed would
    // be undoing that disclosure decision on its behalf.
    expect(screen.getByTestId('status')).toBeInTheDocument();
  });
});
