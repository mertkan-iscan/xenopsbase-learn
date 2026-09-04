import { StrictMode, useEffect, useRef } from 'react';
import { createRoot } from 'react-dom/client';
import { ErrorState } from '../shared/state/States.tsx';
import { PLAYER_PROTOCOL, isCommand, type Event } from './messages.ts';
import { VideoPlayer } from './VideoPlayer.tsx';
import '../styles.css';

/**
 * What runs inside the iframe (ADR-0110, T-3.5): the player, plus the translation between
 * `postMessage` and the DOM.
 *
 * <p>Everything the player needs arrives in the URL — the node, the channel id, the title —
 * because an iframe's `src` is the only thing a host can set before our code runs, and a player
 * that needed a command to know what to play would show an empty box until the host got around to
 * sending one.
 *
 * <p>Notably NOT in the URL: a token. The player mints its own against the session (T-3.4), so a
 * host page never handles a playback credential and no token is ever sitting in a `src` attribute,
 * a browser history entry or a referrer header.
 */

const parameters = new URLSearchParams(window.location.search);
const nodeId = parameters.get('node') ?? '';
const channel = parameters.get('channel') ?? 'player';
const title = parameters.get('title') ?? 'Video';

function post(message: Event) {
  // `parent` and not `window.top`: nested framing is the host's business, and addressing the top
  // window would skip an intermediate host that legitimately wrapped us.
  //
  // The target origin is '*' and that is a deliberate, bounded choice. We do not know the host's
  // origin — that is the entire premise of being embeddable — and these messages carry no secret:
  // a position, a duration, an already-displayed error. What must never go this way is the token,
  // and it never does, because the player mints its own and keeps it in this document.
  window.parent?.postMessage(message, '*');
}

function EmbeddedPlayer() {
  const frame = useRef<HTMLDivElement>(null);

  useEffect(() => {
    function onMessage(event: MessageEvent) {
      if (!isCommand(event.data, channel)) {
        return;
      }
      const video = frame.current?.querySelector('video');
      if (!video) {
        return;
      }
      switch (event.data.type) {
        case 'play':
          void video.play().catch(() => undefined);
          break;
        case 'pause':
          video.pause();
          break;
        case 'seek':
          // Seek-forward past unwatched content is refusable per item (T-3.7's criterion), and
          // the rule will be enforced identically here and on the server. Until intervals exist
          // there is nothing to enforce it against, so this obeys the host.
          video.currentTime = event.data.seconds;
          break;
        case 'setRate':
          video.playbackRate = event.data.rate;
          break;
      }
    }

    window.addEventListener('message', onMessage);
    post({ channel, type: 'ready', protocol: PLAYER_PROTOCOL });
    return () => window.removeEventListener('message', onMessage);
  }, []);

  useEffect(() => {
    const video = frame.current?.querySelector('video');
    if (!video) {
      return;
    }
    function progress() {
      post({ channel, type: 'progress', seconds: video!.currentTime, duration: video!.duration || 0 });
    }
    function ended() {
      post({ channel, type: 'ended' });
    }
    function loaded() {
      if (video!.videoWidth && video!.videoHeight) {
        post({ channel, type: 'resize', aspectRatio: video!.videoWidth / video!.videoHeight });
      }
    }
    // `timeupdate` fires about four times a second, which is the right cadence for a host's
    // progress bar and emphatically not the right one for telemetry. The heartbeat that reaches
    // our own services is batched every ten seconds and is T-3.6's, not this listener's.
    video.addEventListener('timeupdate', progress);
    video.addEventListener('ended', ended);
    video.addEventListener('loadedmetadata', loaded);
    return () => {
      video.removeEventListener('timeupdate', progress);
      video.removeEventListener('ended', ended);
      video.removeEventListener('loadedmetadata', loaded);
    };
  });

  if (!nodeId) {
    return <ErrorState message="This player was opened without a video to play." />;
  }
  return (
    <div ref={frame}>
      <VideoPlayer nodeId={nodeId} title={title} />
    </div>
  );
}

const root = document.getElementById('root');
if (!root) {
  throw new Error('player.html has no #root element to mount into');
}

createRoot(root).render(
  <StrictMode>
    <EmbeddedPlayer />
  </StrictMode>,
);
