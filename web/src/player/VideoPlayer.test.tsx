import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { expectNoAxeViolations } from '../test/axe.ts';
import { VideoPlayer } from './VideoPlayer.tsx';

/**
 * The player's surface (T-3.5): what a viewer can reach, and what they are told when they cannot
 * watch.
 *
 * <p>What these do NOT check is that a video decodes, and that is a real limit stated rather than
 * papered over. jsdom has no media pipeline, and the local stack has no video at all — the fake
 * provider mints URLs on a domain reserved never to resolve (local-stack.md). Adaptive playback
 * is provable only against T-9.14's real account, so what is asserted here is everything around
 * the picture: the controls, the labels, and the refusals.
 */

/**
 * hls.js, stubbed — because jsdom has no Media Source Extensions, so the real one reports
 * `isSupported() === false` and the player correctly renders "this browser cannot play this
 * video" instead of a player. That is the right behaviour and the wrong thing to be testing here:
 * these tests are about the surface around the picture, and the picture is exactly the part no
 * amount of jsdom will ever prove (T-9.14's real account is the only thing that can).
 *
 * The stub is deliberately inert. It reports support, accepts the calls the hook makes, and fires
 * no events — so the player renders its default state and nothing here depends on hls.js's own
 * behaviour, which is hls.js's to test.
 */
const hls = vi.hoisted(() => ({ constructed: 0 }));

vi.mock('hls.js', () => {
  class FakeHls {
    constructor() {
      hls.constructed += 1;
    }
    static isSupported() {
      return true;
    }
    static Events = {
      MANIFEST_PARSED: 'hlsManifestParsed',
      LEVEL_SWITCHED: 'hlsLevelSwitched',
      ERROR: 'hlsError',
    };
    static ErrorTypes = { MEDIA_ERROR: 'mediaError', NETWORK_ERROR: 'networkError' };
    levels: { height: number; bitrate: number }[] = [];
    currentLevel = -1;
    attachMedia() {}
    loadSource() {}
    on() {}
    destroy() {}
    recoverMediaError() {}
    startLoad() {}
  }
  return { default: FakeHls };
});

const FIVE_MINUTES = 5 * 60 * 1000;

let respond: () => { status: number; body: unknown } = () => ({ status: 200, body: playback() });

function playback() {
  const now = Date.now();
  return {
    nodeId: 'node-1',
    videoAssetId: 'asset-1',
    token: 'token-1',
    manifestUrl: 'https://fake-media.invalid/ref/manifest/video.m3u8?token=token-1',
    expiresAt: new Date(now + FIVE_MINUTES).toISOString(),
    renewAfter: new Date(now + FIVE_MINUTES * 0.6).toISOString(),
  };
}

beforeEach(() => {
  hls.constructed = 0;
  respond = () => ({ status: 200, body: playback() });
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

afterEach(() => vi.unstubAllGlobals());

/**
 * The branch that decides how the stream is attached, and the bug it had.
 *
 * <p>`canPlayType('application/vnd.apple.mpegurl')` returns **"maybe" on Chromium**, which cannot
 * play HLS natively at all. The first version of this player asked `canPlayType` first and used
 * hls.js only as a fallback — so in Chrome it set the manifest as `<video src>` and the video
 * silently never started: no error, no quality ladder, nothing to see. It was found by opening
 * the player in a real browser, and it is exactly the class of bug jsdom cannot find on its own,
 * because there `canPlayType` returns "" and the wrong branch is never taken.
 *
 * <p>So these two stub the browser's answers directly, which is the only way to make a jsdom
 * suite meaningful about a capability jsdom does not have.
 */
describe('choosing between MSE and native HLS', () => {
  const canPlayType = HTMLMediaElement.prototype.canPlayType;

  afterEach(() => {
    HTMLMediaElement.prototype.canPlayType = canPlayType;
    Reflect.deleteProperty(globalThis, 'MediaSource');
  });

  function browserSays({ mse, native }: { mse: boolean; native: boolean }) {
    HTMLMediaElement.prototype.canPlayType = () => (native ? 'maybe' : '');
    if (mse) {
      vi.stubGlobal('MediaSource', { isTypeSupported: () => true });
    } else {
      Reflect.deleteProperty(globalThis, 'MediaSource');
    }
  }

  it('uses hls.js where MSE exists, even when canPlayType says "maybe"', async () => {
    browserSays({ mse: true, native: true });

    render(<VideoPlayer nodeId="node-1" title="Fire safety, part 1" />);
    const video = await screen.findByLabelText('Fire safety, part 1');

    await waitFor(() => expect(hls.constructed).toBe(1));
    // The manifest must NOT be on the element: hls.js feeds it through a MediaSource, and a src
    // attribute here is the Chromium bug returning.
    expect(video).not.toHaveAttribute('src');
  });

  it('falls back to the native player only where there is no MSE', async () => {
    browserSays({ mse: false, native: true });

    render(<VideoPlayer nodeId="node-1" title="Fire safety, part 1" />);
    const video = await screen.findByLabelText('Fire safety, part 1');

    // iOS Safari, essentially -- where native HLS is genuinely the better path and hls.js could
    // not run anyway. It is also never downloaded there, which is why the check asks MediaSource
    // directly rather than importing hls.js to ask it.
    await waitFor(() => expect(video).toHaveAttribute('src'));
    expect(hls.constructed).toBe(0);
  });
});

describe('the player', () => {
  it('labels the video and offers quality and speed as real form controls', async () => {
    const { container } = render(<VideoPlayer nodeId="node-1" title="Fire safety, part 1" />);

    // Found by accessible name, which is the assertion: a control a test can only reach by class
    // name is one a screen reader cannot reach at all.
    const video = await screen.findByLabelText('Fire safety, part 1');
    expect(video.tagName).toBe('VIDEO');
    // Native controls, deliberately (see the component): keyboard operable and labelled in the
    // viewer's own language, which a custom control bar has to re-earn and usually half-earns.
    expect(video).toHaveAttribute('controls');
    expect(screen.getByLabelText(/Quality/)).toBeInTheDocument();
    expect(screen.getByLabelText(/Speed/)).toBeInTheDocument();

    await expectNoAxeViolations(container);
  });

  it('does not start on its own', async () => {
    render(<VideoPlayer nodeId="node-1" title="Fire safety, part 1" />);

    // A video that plays unprompted talks over a screen reader, and starts a download for
    // somebody on a metered connection who never asked for one.
    const video = await screen.findByLabelText('Fire safety, part 1');
    expect(video).not.toHaveAttribute('autoplay');
  });

  it('changes playback rate through the keyboard, and the element follows', async () => {
    const user = userEvent.setup();
    render(<VideoPlayer nodeId="node-1" title="Fire safety, part 1" />);

    const video = (await screen.findByLabelText('Fire safety, part 1')) as HTMLVideoElement;
    const speed = screen.getByLabelText(/Speed/);

    await user.selectOptions(speed, '1.5');

    await waitFor(() => expect(video.playbackRate).toBe(1.5));
    // Rate is the player's to own because progress accounting needs it: a learner at 2x otherwise
    // looks like a learner claiming twice the time they spent. Reporting it is T-3.6's heartbeat.
    expect(speed).toHaveValue('1.5');
  });

  it('announces which quality is actually playing, separately from the one selected', async () => {
    render(<VideoPlayer nodeId="node-1" title="Fire safety, part 1" />);

    // With Auto selected, "Auto" is not an answer to "what am I getting". A live region carries
    // the real one, so a switch mid-playback is announced rather than silently changing a label.
    const indicator = await screen.findByText(/^Playing /);
    expect(indicator).toHaveAttribute('aria-live', 'polite');
    expect(screen.getByLabelText(/Quality/)).toHaveValue('-1');
  });

  it('shows a refusal as an alert, with the reason the service was willing to give', async () => {
    respond = () => ({ status: 403, body: { error: { code: 'CONTENT_GATED' } } });

    render(<VideoPlayer nodeId="node-1" title="Fire safety, part 1" />);

    const alert = await screen.findByRole('alert');
    expect(alert).toHaveTextContent('This content is not available yet.');
    // No retry: a closed gate answers the same way however many times it is asked, and a button
    // that does nothing twice is worse than no button.
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
  });

  it('does not translate a deliberately silent 404 into a claim about the video', async () => {
    respond = () => ({ status: 404, body: {} });

    render(<VideoPlayer nodeId="node-1" title="Fire safety, part 1" />);

    const alert = await screen.findByRole('alert');
    // The service refuses to say whether the node is unknown, unassigned, or beyond this
    // caller's permission (T-3.4's disclosure rule). A UI that said "this video was deleted"
    // would be undoing that decision on the service's behalf.
    expect(alert).toHaveTextContent('This video is not available to you.');
  });
});
