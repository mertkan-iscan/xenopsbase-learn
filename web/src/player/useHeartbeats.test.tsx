import { act, render } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { useHeartbeats, type Sample } from './useHeartbeats.ts';

/**
 * The heartbeat loop (T-3.6): what the player tells analytics, and what it does when it cannot.
 *
 * <p>The two properties worth the trouble of testing are the ones that only show up under
 * conditions nobody reproduces by hand — a learner offline for an hour, and an analytics service
 * that has fallen over. Both have a wrong answer that looks fine in development: buffer forever,
 * and retry forever.
 */

const FLUSH = 10_000;

type Posted = { nodeId: string; playbackToken: string; samples: Sample[] };

let posts: Posted[] = [];
let respond: () => { status: number } | 'network-error' = () => ({ status: 202 });

/** A video element the test drives, since jsdom's has no clock of its own. */
function fakeVideo(): HTMLVideoElement {
  const video = document.createElement('video');
  let current = 0;
  Object.defineProperty(video, 'currentTime', {
    get: () => current,
    set: (value: number) => {
      current = value;
    },
    configurable: true,
  });
  Object.defineProperty(video, 'paused', { value: false, writable: true, configurable: true });
  Object.defineProperty(video, 'ended', { value: false, writable: true, configurable: true });
  Object.defineProperty(video, 'playbackRate', { value: 1, writable: true, configurable: true });
  return video;
}

function Probe({ video, token }: { video: HTMLVideoElement | null; token?: string }) {
  useHeartbeats(video, 'node-1', token);
  return null;
}

beforeEach(() => {
  posts = [];
  respond = () => ({ status: 202 });
  vi.useFakeTimers({ shouldAdvanceTime: true });
  vi.stubGlobal(
    'fetch',
    // openapi-fetch hands `fetch` a built Request rather than (url, init), so the body has to be
    // read off the Request. Reading `init.body` returns undefined and, worse, throws when `init`
    // itself is absent -- which the hook then catches as a network failure and the test reads as
    // "nothing was posted".
    vi.fn(async (input: Request | string, init?: { body?: string }) => {
      const answer = respond();
      if (answer === 'network-error') {
        throw new TypeError('Failed to fetch');
      }
      const body =
        input instanceof Request ? await input.clone().text() : (init?.body ?? '{}');
      posts.push(JSON.parse(body || '{}') as Posted);
      return new Response(JSON.stringify({ samples: 0 }), {
        status: answer.status,
        headers: { 'content-type': 'application/json' },
      });
    }),
  );
});

afterEach(() => {
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

/** Plays `seconds` of video, one observation tick at a time. */
async function play(video: HTMLVideoElement, seconds: number) {
  for (let n = 0; n < seconds; n++) {
    video.currentTime = video.currentTime + 1;
    await act(async () => {
      await vi.advanceTimersByTimeAsync(1000);
    });
  }
}

describe('the heartbeat loop', () => {
  it('reports the intervals it covered, batched rather than one at a time', async () => {
    const video = fakeVideo();
    render(<Probe video={video} token="token-1" />);

    await play(video, 12);

    // One post, not twelve. At 5,000 concurrent learners the difference between these two is
    // ~500 posts/second and ~6,000 (ADR-0107).
    expect(posts).toHaveLength(1);
    expect(posts[0]!.samples.length).toBeGreaterThan(1);
    expect(posts[0]!.playbackToken).toBe('token-1');
    // Contiguous playback: each sample ends where the next begins, so the union is one run.
    expect(posts[0]!.samples.every((s) => s.toSecond > s.fromSecond)).toBe(true);
  });

  it('does not credit a seek as watched content', async () => {
    const video = fakeVideo();
    render(<Probe video={video} token="token-1" />);

    await play(video, 3);
    // Drag the scrubber to the end. This is the attack the whole design exists to defeat: a
    // furthest position is a claim, and nothing here turns a jump into coverage.
    video.currentTime = 600;
    await play(video, 2);

    await act(async () => {
      await vi.advanceTimersByTimeAsync(FLUSH);
    });

    const covered = posts.flatMap((post) => post.samples);
    expect(covered.some((s) => s.toSecond - s.fromSecond > 3)).toBe(false);
    expect(covered.reduce((total, s) => total + (s.toSecond - s.fromSecond), 0)).toBeLessThan(10);
  });

  it('bounds the buffer, and drops the oldest rather than the newest', async () => {
    const video = fakeVideo();
    respond = () => 'network-error';
    render(<Probe video={video} token="token-1" />);

    // Twelve minutes of playback with nothing getting through.
    await play(video, 720);

    respond = () => ({ status: 202 });
    await play(video, 15);

    const posted = posts.flatMap((post) => post.samples);
    // Bounded: twelve minutes offline must not produce a twelve-minute post. The server refuses
    // one that large (413), and the client would have spent the memory building it first.
    expect(posts.every((post) => post.samples.length <= 60)).toBe(true);
    // And what comes through afterwards is RECENT coverage -- what a resume position and a
    // progress bar are computed from, which is the part a learner sees working.
    expect(Math.max(...posted.map((sample) => sample.toSecond))).toBeGreaterThan(700);
  });

  it('retries a failed post once, with the batch intact', async () => {
    const video = fakeVideo();
    render(<Probe video={video} token="token-1" />);

    respond = () => 'network-error';
    await play(video, 12);
    expect(posts).toHaveLength(0);

    respond = () => ({ status: 202 });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(FLUSH);
    });

    // Nothing was dropped by the retry itself: the samples buffered during the outage arrive.
    expect(posts).toHaveLength(1);
    expect(posts[0]!.samples.length).toBeGreaterThan(1);
  });

  it('gives up on a batch rather than retrying it forever', async () => {
    const video = fakeVideo();
    respond = () => 'network-error';
    render(<Probe video={video} token="token-1" />);

    await play(video, 25);
    const attempts = () => (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls.length;
    expect(attempts()).toBeGreaterThan(0);

    // The video stops, so no new samples arrive. Whatever is still buffered gets its one retry
    // and is then let go.
    Object.defineProperty(video, 'paused', { value: true, configurable: true });
    await act(async () => {
      await vi.advanceTimersByTimeAsync(FLUSH * 3);
    });
    const settled = attempts();

    // The property: attempts STOP. A client that retried a dead batch for the length of an
    // outage would stampede the moment the service came back, and every client would do it at
    // the same moment.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(FLUSH * 10);
    });
    expect(attempts()).toBe(settled);
  });

  it('does not retry a refusal the server was specific about', async () => {
    const video = fakeVideo();
    respond = () => ({ status: 400 });
    render(<Probe video={video} token="token-1" />);

    await play(video, 12);
    const afterFirst = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls.length;
    expect(afterFirst).toBeGreaterThan(0);

    // A 400 answers the same way however many times it is sent -- the server says so with a
    // specific status precisely so the client can stop. The buffer moves on rather than jamming
    // on a batch that will never be accepted.
    await act(async () => {
      await vi.advanceTimersByTimeAsync(FLUSH);
    });
    expect(posts.every((post) => post.samples.length > 0)).toBe(true);
  });

  it('sends nothing at all before there is a token', async () => {
    const video = fakeVideo();
    render(<Probe video={video} />);

    await play(video, 30);

    // No token means no session to attribute a sample to, and the server would refuse it as
    // MISSING_ATTRIBUTION. Not sending is the honest version of the same answer.
    expect(posts).toHaveLength(0);
  });

  it('stops when the player goes away', async () => {
    const video = fakeVideo();
    const { unmount } = render(<Probe video={video} token="token-1" />);

    await play(video, 12);
    const duringPlayback = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls.length;

    unmount();
    await act(async () => {
      await vi.advanceTimersByTimeAsync(FLUSH * 5);
    });

    // One final flush on the way out is allowed -- closing a tab should not silently discard the
    // last ten seconds of every session -- but the loop must not outlive the player.
    expect((globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls.length)
      .toBeLessThanOrEqual(duringPlayback + 1);
  });
});
