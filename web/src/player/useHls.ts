import { useCallback, useEffect, useRef, useState } from 'react';

/**
 * Attaches an HLS stream to a `<video>` element, and keeps the position across a manifest swap
 * (T-3.5).
 *
 * <h2>This hook owns the element</h2>
 *
 * It creates the ref, hands it to the caller to attach to a `<video>`, and is the only thing that
 * writes to the element — source, position, playback rate. One owner rather than a component and
 * a hook both reaching for the same node, which is how a rate set in one place gets silently
 * undone by a source swap in the other.
 *
 * <h2>Why hls.js is imported dynamically</h2>
 *
 * It is the largest dependency in the application by a wide margin, and most of what a learner
 * does — signing in, finding a course, reading their progress — involves no video at all. The
 * admin console is kept out of a learner's download by route-level splitting (docs/frontend.md);
 * this is the same reasoning applied to the one dependency big enough to matter on its own.
 *
 * <h2>Media Source Extensions first, native HLS only where there is no MSE</h2>
 *
 * The order matters and the obvious order is wrong. `canPlayType('application/vnd.apple.mpegurl')`
 * returns <b>"maybe" on Chromium</b>, which cannot play HLS natively at all — so a check that asks
 * `canPlayType` first and only falls back to hls.js hands Chrome a manifest it will not play, and
 * does it silently: no error, no quality ladder, just a video that never starts. Found by running
 * the player in a real browser; jsdom cannot see it, because there `canPlayType` returns "" and
 * the wrong branch never gets taken.
 *
 * So the question asked first is whether MSE exists, which is the capability hls.js actually
 * needs. Native HLS is the fallback for the browsers with no MSE — iOS Safari, essentially — where
 * it is genuinely the better path anyway: hardware decoding, lower power, working AirPlay. Those
 * browsers also never download hls.js, which is why this asks `MediaSource` directly instead of
 * importing the library to ask `Hls.isSupported()`.
 *
 * <h2>The position across renewal, which is the whole reason this is a hook</h2>
 *
 * A renewed playback token means a NEW manifest URL (T-3.4 signs one per token), so continuing to
 * play means re-attaching a source and seeking back. The element's `currentTime` is read before
 * the swap and restored after the new manifest is parsed — not after `loadSource`, which returns
 * long before there is anything to seek within. Getting that ordering wrong is a player that
 * silently restarts the video every five minutes, which is both the most likely bug here and one
 * nobody would notice in a thirty-second test clip.
 */

export type Quality = {
  /** hls.js level index, or -1 for automatic. */
  id: number;
  label: string;
};

/**
 * Put this on the `<video>` as its `ref`.
 *
 * Returned as its own value rather than a field on {@link HlsState}, and that is not a style
 * choice: anything reachable from a ref is treated as ref access by the hook rules, so a record
 * carrying both this and the plain state would make every read of `qualities` or `rate` look
 * like reading `ref.current` during render. Separating them keeps the warning meaningful for the
 * case where it is a real bug.
 */
export type AttachVideo = (element: HTMLVideoElement | null) => void;

export type HlsState = {
  /**
   * The attached element, once there is one.
   *
   * Exposed as state rather than as the ref, so a caller can depend on it in an effect and be
   * re-run when it arrives — a ref would hand them a value that is null on the render they
   * subscribe on and never notifies them that it stopped being null.
   */
  element: HTMLVideoElement | null;
  qualities: Quality[];
  /** The level actually being played, whether chosen or picked automatically. */
  activeQualityId: number;
  /** What the viewer selected: -1 means "let it adapt". */
  selectedQualityId: number;
  selectQuality: (id: number) => void;
  /** Playback rate, owned here so a manifest swap cannot quietly reset it to 1x. */
  rate: number;
  setRate: (rate: number) => void;
  error: string | undefined;
};

const AUTO: Quality = { id: -1, label: 'Auto' };

/**
 * Whether this browser can play segmented media through MSE — the capability hls.js needs, asked
 * without importing hls.js so that a browser which will never use it does not download it.
 *
 * The codec string is the baseline H.264 + AAC pair every HLS ladder starts from; a browser that
 * cannot play that cannot play anything we would ship.
 */
function supportsMediaSource(): boolean {
  return (
    typeof MediaSource !== 'undefined' &&
    typeof MediaSource.isTypeSupported === 'function' &&
    MediaSource.isTypeSupported('video/mp4; codecs="avc1.42E01E,mp4a.40.2"')
  );
}

type Engine = { destroy: () => void; currentLevel: number };

/**
 * What the server says about this learner's own history with this item (T-3.7), and the only two
 * things it changes about playback.
 *
 * @property resumeFrom where to start. Applied once — a learner who seeks back after resuming has
 *   not asked to be moved again, and a hook that re-applied this on every update would fight them.
 * @property seekCeiling the furthest second the learner may seek to, when the item forbids
 *   skipping past unwatched content. Undefined when seeking is allowed, which is the ordinary
 *   case. The SERVER enforces the same rule by refusing to credit coverage that could only have
 *   come from a skip; this is the half that stops the learner from making one, so that the rule
 *   arrives as a control rather than as coverage they mysteriously did not get.
 */
export type PlaybackHistory = {
  resumeFrom?: number | undefined;
  seekCeiling?: number | undefined;
};

export function useHls(
  manifestUrl: string | undefined,
  history: PlaybackHistory = {},
): [AttachVideo, HlsState] {
  const video = useRef<HTMLVideoElement | null>(null);
  const [element, setElement] = useState<HTMLVideoElement | null>(null);
  const attachVideo = useCallback((attached: HTMLVideoElement | null) => {
    video.current = attached;
    setElement(attached);
  }, []);
  const [qualities, setQualities] = useState<Quality[]>([AUTO]);
  const [activeQualityId, setActiveQualityId] = useState(-1);
  const [selectedQualityId, setSelectedQualityId] = useState(-1);
  const [rate, setRate] = useState(1);
  const [error, setError] = useState<string | undefined>(undefined);

  // The engine is a mutable thing to command, not a value to render: in state it would re-render
  // the player on every level switch, which is several times a minute on a variable connection.
  const engine = useRef<Engine | undefined>(undefined);
  // The viewer's choice, read inside the attach without making the attach depend on it — a
  // dependency here would reload the whole stream to change a level that can be set in place.
  const selection = useRef(selectedQualityId);

  // The history is read inside the attach and inside a listener, and neither may depend on it:
  // a dependency would tear down and rebuild the stream every time the ceiling moved, which is
  // once per heartbeat.
  const resumeFrom = useRef(history.resumeFrom ?? 0);
  const seekCeiling = useRef(history.seekCeiling);
  const resumed = useRef(false);

  useEffect(() => {
    resumeFrom.current = history.resumeFrom ?? 0;
    seekCeiling.current = history.seekCeiling;
  }, [history.resumeFrom, history.seekCeiling]);

  useEffect(() => {
    selection.current = selectedQualityId;
    if (engine.current) {
      engine.current.currentLevel = selectedQualityId;
    }
  }, [selectedQualityId]);

  // Re-applied whenever the source changes as well as when the viewer changes it: a renewal
  // replaces the source, and a fresh source starts back at 1x. A learner who chose 1.5x should
  // not be quietly returned to normal speed every five minutes.
  useEffect(() => {
    const element = video.current;
    if (element) {
      element.playbackRate = rate;
    }
  }, [rate, manifestUrl]);

  // THE RESUME THAT ARRIVES LATE. The position comes from a request the player makes at the same
  // time as the one for a playback token, and either can answer first. When the manifest wins the
  // race the restore above sees a zero; this is the other order, and `resumed` is what keeps the
  // two from both seeking -- or from seeking a learner who has already moved themselves.
  useEffect(() => {
    const element = video.current;
    const seconds = history.resumeFrom ?? 0;
    if (!element || resumed.current || seconds <= 0 || element.currentTime > 0) {
      return;
    }
    element.currentTime = seconds;
    resumed.current = true;
  }, [history.resumeFrom, element]);

  // THE SEEK THE ITEM FORBIDS. Enforced on `seeking` rather than by hiding the scrubber: the
  // native controls are the accessible ones (they are the reason this player has almost no
  // controls of its own), and a learner is better served by a scrubber that will not go past
  // where they have watched than by no scrubber at all.
  useEffect(() => {
    const element = video.current;
    if (!element) {
      return;
    }
    function refuseSkippingAhead() {
      const ceiling = seekCeiling.current;
      const target = element?.currentTime ?? 0;
      if (element && ceiling !== undefined && target > ceiling) {
        element.currentTime = ceiling;
      }
    }
    element.addEventListener('seeking', refuseSkippingAhead);
    return () => element.removeEventListener('seeking', refuseSkippingAhead);
  }, [element]);

  useEffect(() => {
    const element = video.current;
    if (!element || !manifestUrl) {
      return;
    }

    let cancelled = false;
    let hls: Engine | undefined;
    // Read BEFORE the swap. On the first attach this is 0 and the server's resume position is
    // what should apply; on a renewal it is where the learner actually is, and it is the only
    // copy of that number once the source is replaced.
    const resumeAt = element.currentTime > 0 ? element.currentTime : resumeFrom.current;
    const wasPlaying = !element.paused && !element.ended;

    function restore(video: HTMLVideoElement) {
      if (resumeAt > 0) {
        video.currentTime = resumeAt;
        resumed.current = true;
      }
      if (wasPlaying) {
        // A renewal must not leave a playing video paused. The rejection is swallowed on
        // purpose: autoplay policy can refuse, and the correct response is a paused player the
        // learner can start, not an error about a video that is fine.
        void video.play().catch(() => undefined);
      }
    }

    if (!supportsMediaSource() && element.canPlayType('application/vnd.apple.mpegurl')) {
      element.src = manifestUrl;
      restore(element);
      return;
    }

    void import('hls.js').then(({ default: Hls }) => {
      if (cancelled) {
        return;
      }
      if (!Hls.isSupported()) {
        setError('This browser cannot play this video.');
        return;
      }

      const instance = new Hls({ enableWorker: true });
      hls = instance;
      engine.current = instance;
      instance.attachMedia(element);
      instance.loadSource(manifestUrl);

      instance.on(Hls.Events.MANIFEST_PARSED, () => {
        if (cancelled) {
          return;
        }
        setQualities([
          AUTO,
          ...instance.levels.map((level, index) => ({
            id: index,
            label: level.height ? `${level.height}p` : `${Math.round(level.bitrate / 1000)} kbps`,
          })),
        ]);
        // A renewal re-enters here with the viewer's previous choice still held; re-applying it
        // means a manual selection survives the swap instead of quietly reverting to auto.
        instance.currentLevel = selection.current;
        restore(element);
      });

      instance.on(Hls.Events.LEVEL_SWITCHED, (_event, data) => {
        if (!cancelled) {
          setActiveQualityId(data.level);
        }
      });

      instance.on(Hls.Events.ERROR, (_event, data) => {
        if (cancelled || !data.fatal) {
          // Non-fatal errors are hls.js's normal working noise -- a segment retried, a level
          // dropped. Surfacing them would make a healthy player look broken.
          return;
        }
        // The recoveries hls.js documents, attempted before giving up: a media error is often one
        // bad segment, and a network error is often one bad request.
        if (data.type === Hls.ErrorTypes.MEDIA_ERROR) {
          instance.recoverMediaError();
          return;
        }
        if (data.type === Hls.ErrorTypes.NETWORK_ERROR) {
          instance.startLoad();
          return;
        }
        setError('Playback stopped. The video could not be loaded.');
      });
    });

    return () => {
      cancelled = true;
      hls?.destroy();
      engine.current = undefined;
    };
  }, [manifestUrl]);

  return [attachVideo, {
    element,
    qualities,
    activeQualityId: selectedQualityId === -1 ? activeQualityId : selectedQualityId,
    selectedQualityId,
    selectQuality: setSelectedQualityId,
    rate,
    setRate,
    error,
  }];
}
