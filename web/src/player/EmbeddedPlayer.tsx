import { useEffect, useRef } from 'react';
import { embedPlayer, type EmbeddedPlayerHandle } from './embed.ts';

/**
 * How our own screens show a video (T-3.5): through the same iframe and the same loader a
 * customer uses (ADR-0110).
 *
 * <p>The alternative — rendering `<VideoPlayer>` directly, since it is right there in the same
 * bundle — would be faster to write and would leave the embed path exercised by nobody who would
 * notice it break. T-10.7 states the rule for the published package; it costs nothing to obey it
 * now, and obeying it now is what makes the first customer embed boring.
 *
 * @param origin where the player document is served from. In development that is this same dev
 *        server, which means the iframe is same-origin locally and cross-origin in production —
 *        the one thing about this that is NOT faithful, and worth remembering when a bug appears
 *        only after deployment.
 */
export function EmbeddedPlayer({
  nodeId,
  title,
  origin = import.meta.env.VITE_PLAYER_ORIGIN ?? window.location.origin,
  onProgress,
}: {
  nodeId: string;
  title: string;
  origin?: string;
  onProgress?: (seconds: number, duration: number) => void;
}) {
  const container = useRef<HTMLDivElement>(null);
  // The callback lives in a ref so a parent re-rendering with a new function identity does not
  // tear the iframe down and rebuild it -- which would restart the video. Assigned in an effect
  // rather than during render: a render must not touch a ref, and a render that did would be
  // doing it on a pass React is free to throw away.
  const progress = useRef(onProgress);
  useEffect(() => {
    progress.current = onProgress;
  }, [onProgress]);

  useEffect(() => {
    const into = container.current;
    if (!into) {
      return;
    }
    let player: EmbeddedPlayerHandle | undefined;
    try {
      player = embedPlayer({ into, origin, nodeId, title });
    } catch {
      // jsdom and any environment without a real iframe. The screen around it stays usable.
      return;
    }
    const stop = player.on('progress', ({ seconds, duration }) => {
      progress.current?.(seconds, duration);
    });
    // The height the player reports, applied to the frame. ADR-0110 names sizing as the loader's
    // main reason to exist; this is the host end of it.
    const stopResize = player.on('resize', ({ aspectRatio }) => {
      if (player && aspectRatio > 0) {
        player.frame.style.aspectRatio = String(aspectRatio);
      }
    });
    return () => {
      stop();
      stopResize();
      player?.destroy();
    };
  }, [nodeId, title, origin]);

  return <div ref={container} className="player__embed" />;
}
