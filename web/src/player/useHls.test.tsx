import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { useHls, type PlaybackHistory } from './useHls.ts';

/**
 * The two things the server tells the player to do with the element (T-3.7): where to start, and
 * how far a learner may drag the scrubber.
 *
 * <p>Tested against the hook with no manifest, which is not a shortcut — both behaviours belong to
 * the element rather than to the stream, and asserting them without hls.js in the way is the
 * difference between testing the rule and testing a mock of a decoder jsdom cannot run anyway.
 */

function Probe({ history }: { history: PlaybackHistory }) {
  const [attachVideo] = useHls(undefined, history);
  return <video ref={attachVideo} data-testid="video" />;
}

/**
 * jsdom's `currentTime` is inert on a media element with no media, so the test owns it. The same
 * trick the heartbeat tests use, and for the same reason: what is under test is what the player
 * writes to the element, which is unobservable if the element ignores writes.
 */
function trackable(video: HTMLVideoElement): HTMLVideoElement {
  let current = 0;
  Object.defineProperty(video, 'currentTime', {
    get: () => current,
    set: (value: number) => {
      current = value;
    },
    configurable: true,
  });
  return video;
}

describe('what the server tells the player about this learner', () => {
  it('resumes where the learner stopped, once the answer arrives', () => {
    const { rerender } = render(<Probe history={{}} />);
    const video = trackable(screen.getByTestId('video') as HTMLVideoElement);

    // The progress request answers after the element exists -- it races the playback token, and
    // either can win. This is the order where the position is late.
    rerender(<Probe history={{ resumeFrom: 120 }} />);
    expect(video.currentTime).toBe(120);

    // And a learner who then seeks back is not dragged forward again on the next update. The
    // position is applied once; after that it is theirs.
    video.currentTime = 30;
    rerender(<Probe history={{ resumeFrom: 120 }} />);
    expect(video.currentTime).toBe(30);
  });

  it('starts at the beginning when there is nothing to resume', () => {
    render(<Probe history={{ resumeFrom: 0 }} />);
    const video = trackable(screen.getByTestId('video') as HTMLVideoElement);

    expect(video.currentTime).toBe(0);
  });

  it('refuses a seek past what has been watched when the item forbids skipping ahead', () => {
    render(<Probe history={{ seekCeiling: 30 }} />);
    const video = trackable(screen.getByTestId('video') as HTMLVideoElement);

    video.currentTime = 400;
    video.dispatchEvent(new Event('seeking'));

    // The scrubber stays operable and simply will not go past the end of what this learner has
    // been shown -- the same boundary the server refuses to credit coverage beyond, so an honest
    // player and a modified one end up with the same progress.
    expect(video.currentTime).toBe(30);
  });

  it('leaves seeking alone on an item that allows it', () => {
    render(<Probe history={{}} />);
    const video = trackable(screen.getByTestId('video') as HTMLVideoElement);

    video.currentTime = 400;
    video.dispatchEvent(new Event('seeking'));

    // Which is most items. Skipping ahead is ordinary behaviour for a video, and it costs the
    // learner coverage rather than being forbidden.
    expect(video.currentTime).toBe(400);
  });
});
