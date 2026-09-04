import { ErrorState, Loading } from '../shared/state/States.tsx';
import { useHeartbeats } from './useHeartbeats.ts';
import { useHls, type HlsState } from './useHls.ts';
import { usePlaybackToken } from './usePlaybackToken.ts';

/**
 * The player (T-3.5).
 *
 * <h2>Native controls, and the two controls that are ours</h2>
 *
 * Play, pause, seek, volume, fullscreen and captions come from `<video controls>`. That is not
 * laziness — it is the accessible option. Browser-native controls are keyboard operable, labelled
 * in the user's own language, understood by every screen reader, work with platform media keys,
 * and appear in the OS media session. A custom control bar has to re-earn all of that and usually
 * earns about half, which is how "keyboard operable and screen-reader labelled" becomes a claim
 * rather than a fact.
 *
 * Quality and playback rate are ours because native controls do not expose them consistently:
 * quality is an hls.js concept the element knows nothing about, and rate is hidden in a context
 * menu on some browsers and absent on others. Both are plain labelled `<select>`s — operable by
 * keyboard because they are the real element rather than a div pretending.
 *
 * <h2>Rate is a control here and a fact for the server</h2>
 *
 * Playback rate changes how much video passes per wall-clock second, so progress accounting has to
 * know it or a learner at 2× looks like a learner claiming twice the time they spent. The heartbeat
 * that reports it is T-3.6; what T-3.5 owes is that the rate is a value the player owns and can
 * report, rather than something buried in a browser menu we never see.
 */
export function VideoPlayer({ nodeId, title }: { nodeId: string; title: string }) {
  const token = usePlaybackToken(nodeId);
  const playback = token.status === 'ready' || token.status === 'renewing' ? token.playback : undefined;
  // One owner of the element: `useHls` creates the ref, writes the source, restores the position
  // and applies the rate. This component reads state and renders controls.
  const [attachVideo, hls] = useHls(playback?.manifestUrl);

  // What was actually watched, batched to analytics (T-3.6). It posts to `reporting` directly and
  // never through streaming: telemetry is the most write-heavy path in the product, and the whole
  // point is that it cannot slow the one a learner is waiting on.
  useHeartbeats(hls.element, nodeId, playback?.token);

  if (token.status === 'loading') {
    return <Loading what="the video" />;
  }
  if (token.status === 'refused') {
    // No retry button on a terminal refusal. An assignment was revoked or a gate is closed, and
    // a button that re-asks a question already answered is a button that does nothing twice --
    // and, at the mint rate limit, eventually something worse than nothing.
    return <ErrorState message={token.message} />;
  }
  if (hls.error) {
    return <ErrorState message={hls.error} />;
  }

  return (
    <figure className="player">
      <video
        ref={attachVideo}
        controls
        playsInline
        // Not `autoPlay`: a video that starts on its own is hostile to somebody using a screen
        // reader, who is now listening to two things at once.
        preload="metadata"
        aria-label={title}
        className="player__video"
      >
        {/* Captions belong here as <track>, from the asset's own text tracks (T-3.9). Until an
            asset has any, an empty caption list is the honest rendering -- a hard-coded English
            track pointing at nothing would announce captions that do not exist. */}
      </video>

      <figcaption className="player__controls">
        <label className="player__control">
          <span>Quality</span>
          <select
            value={hls.selectedQualityId}
            onChange={(event) => hls.selectQuality(Number(event.target.value))}
          >
            {hls.qualities.map((quality) => (
              <option key={quality.id} value={quality.id}>
                {quality.label}
              </option>
            ))}
          </select>
          {/* The indicator is separate from the selector on purpose: with Auto selected the
              question a viewer actually has is "what am I getting", and a select showing "Auto"
              does not answer it. */}
          <span className="player__quality" aria-live="polite">
            Playing {labelOf(hls, hls.activeQualityId)}
          </span>
        </label>

        <label className="player__control">
          <span>Speed</span>
          <select value={hls.rate} onChange={(event) => hls.setRate(Number(event.target.value))}>
            {[0.5, 0.75, 1, 1.25, 1.5, 2].map((option) => (
              <option key={option} value={option}>
                {option}×
              </option>
            ))}
          </select>
        </label>

        {/* Deliberately quiet while renewal is merely retrying: there are minutes of valid
            playback left and nothing for the learner to do. It says something only because a
            silent degradation nobody can see is how "it stopped and I do not know why" happens. */}
        {token.status === 'renewing' ? (
          <span className="player__notice" role="status">
            Reconnecting…
          </span>
        ) : null}
      </figcaption>
    </figure>
  );
}

function labelOf(hls: HlsState, id: number): string {
  return hls.qualities.find((quality) => quality.id === id)?.label ?? 'automatic quality';
}
