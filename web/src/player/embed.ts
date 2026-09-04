import { PLAYER_PROTOCOL, type CommandBody, type Event } from './messages.ts';

/**
 * The host-side half of the embed contract (ADR-0110, T-3.5).
 *
 * <p>This is what an integrator writes against, and — deliberately — what our own learner app
 * writes against too. There is no privileged in-process path that only we get, because a private
 * variant is one that is never exercised by the people who would notice it break. T-10.7 states
 * the same rule as "the application consumes the published package, not a private copy"; this is
 * that rule arriving one task early, while it is still free.
 *
 * ```js
 * const player = embedPlayer({
 *   into: document.querySelector('#video'),
 *   origin: 'https://player.xenopslearn.example',
 *   nodeId: '…',
 *   title: 'Fire safety, part 1',
 * });
 * player.on('progress', ({ seconds }) => …);
 * player.seek(120);
 * ```
 *
 * <p>Every method is fire-and-forget and every answer arrives as an event. That is the cost
 * ADR-0110 names: `seek` cannot return where it landed, because the thing that knows is in another
 * document. Designed in from the start rather than discovered at the first method that wants a
 * return value.
 */

export type PlayerEvents = {
  ready: { protocol: string };
  progress: { seconds: number; duration: number };
  ended: Record<string, never>;
  error: { message: string; terminal: boolean };
  resize: { aspectRatio: number };
};

export type EmbeddedPlayerHandle = {
  readonly frame: HTMLIFrameElement;
  play(): void;
  pause(): void;
  seek(seconds: number): void;
  setRate(rate: number): void;
  on<K extends keyof PlayerEvents>(type: K, listener: (payload: PlayerEvents[K]) => void): () => void;
  destroy(): void;
};

export type EmbedOptions = {
  into: HTMLElement;
  /** Where the player is served from. A different origin to the host is the point (ADR-0110). */
  origin: string;
  nodeId: string;
  title: string;
};

let channels = 0;

export function embedPlayer({ into, origin, nodeId, title }: EmbedOptions): EmbeddedPlayerHandle {
  // Unique per embed: two players on one page are the same origin as each other, so origin
  // checking alone cannot tell their messages apart.
  const channel = `xol-player-${++channels}`;
  const listeners = new Map<string, Set<(payload: never) => void>>();

  const frame = document.createElement('iframe');
  const source = new URL('/player.html', origin);
  source.searchParams.set('node', nodeId);
  source.searchParams.set('channel', channel);
  source.searchParams.set('title', title);
  frame.src = source.toString();
  frame.title = title;
  // Named individually rather than borrowed from the host: without this the fullscreen button
  // inside the player silently does nothing, which ADR-0110 lists as the first cost of the
  // iframe and the thing an integrator hits before anything else.
  frame.allow = 'fullscreen; picture-in-picture; autoplay; encrypted-media';
  frame.setAttribute('frameborder', '0');
  frame.style.border = '0';
  frame.style.width = '100%';
  // 16:9 until the player reports what it actually is. An iframe has no intrinsic aspect ratio,
  // so something has to hold one and the host should not have to.
  frame.style.aspectRatio = '16 / 9';
  into.appendChild(frame);

  function onMessage(event: MessageEvent) {
    // Both checks, and neither is redundant. The origin check is what makes an unrelated frame's
    // message untrusted; the channel check is what makes another of OUR players' messages not
    // ours. Messages from an iframe are data from a document the host does not control, so
    // nothing here acts on a message beyond dispatching a typed payload.
    if (event.origin !== new URL(origin).origin) {
      return;
    }
    const message = event.data as Event | undefined;
    if (!message || typeof message !== 'object' || message.channel !== channel) {
      return;
    }
    const payload: Record<string, unknown> = { ...message };
    delete payload.channel;
    delete payload.type;
    listeners.get(message.type)?.forEach((listener) =>
      (listener as (p: unknown) => void)(payload),
    );
  }

  window.addEventListener('message', onMessage);

  function send(command: CommandBody) {
    // Addressed to the player's origin rather than '*': the host DOES know where it put the
    // player, so there is no reason to broadcast a command to whatever else may be listening.
    frame.contentWindow?.postMessage({ ...command, channel }, new URL(origin).origin);
  }

  return {
    frame,
    play: () => send({ type: 'play' }),
    pause: () => send({ type: 'pause' }),
    seek: (seconds: number) => send({ type: 'seek', seconds }),
    setRate: (rate: number) => send({ type: 'setRate', rate }),
    on(type, listener) {
      const forType = listeners.get(type) ?? new Set();
      forType.add(listener as (payload: never) => void);
      listeners.set(type, forType);
      return () => forType.delete(listener as (payload: never) => void);
    },
    destroy() {
      window.removeEventListener('message', onMessage);
      listeners.clear();
      frame.remove();
    },
  };
}

export { PLAYER_PROTOCOL };
