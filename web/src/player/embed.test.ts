import { afterEach, describe, expect, it, vi } from 'vitest';
import { embedPlayer, PLAYER_PROTOCOL } from './embed.ts';
import type { Event } from './messages.ts';

/**
 * The embed contract (ADR-0110, T-3.5) — the surface T-10.7 will publish and semver.
 *
 * <p>Worth testing now rather than when it is published, because these are the properties that
 * are cheap to hold and expensive to add back: no credential in the URL, messages filtered by
 * origin AND channel, and permissions named on the frame rather than assumed.
 */

const ORIGIN = 'https://player.example';

let embedded: { destroy: () => void } | undefined;

afterEach(() => {
  embedded?.destroy();
  embedded = undefined;
  document.body.innerHTML = '';
  vi.unstubAllGlobals();
});

function embed(into = document.body.appendChild(document.createElement('div'))) {
  const player = embedPlayer({ into, origin: ORIGIN, nodeId: 'node-1', title: 'Fire safety' });
  embedded = player;
  return player;
}

/** A message as the browser would deliver it from the frame's origin. */
function fromPlayer(event: Event, origin = ORIGIN) {
  window.dispatchEvent(new MessageEvent('message', { data: event, origin }));
}

function channelOf(player: { frame: HTMLIFrameElement }) {
  return new URL(player.frame.src).searchParams.get('channel')!;
}

describe('the embeddable player', () => {
  it('puts the video in the URL and no credential anywhere near it', () => {
    const player = embed();
    const source = new URL(player.frame.src);

    expect(source.origin).toBe(ORIGIN);
    expect(source.pathname).toBe('/player.html');
    expect(source.searchParams.get('node')).toBe('node-1');

    // The player mints its own token against the session (T-3.4), so no playback credential is
    // ever in a src attribute, a browser history entry or a referrer header.
    expect(player.frame.src).not.toMatch(/token/i);
  });

  it('asks for the permissions the player needs, by name', () => {
    const player = embed();

    // Without this the fullscreen button inside the player silently does nothing -- ADR-0110's
    // first named cost of the iframe, and the thing an integrator hits before anything else.
    expect(player.frame.allow).toContain('fullscreen');
    expect(player.frame.allow).toContain('picture-in-picture');
  });

  it('holds an aspect ratio the host did not have to supply, and adjusts to the real one', () => {
    const player = embed();
    expect(player.frame.style.aspectRatio).toBe('16 / 9');

    fromPlayer({ channel: channelOf(player), type: 'resize', aspectRatio: 4 / 3 });
    player.on('resize', ({ aspectRatio }) => {
      player.frame.style.aspectRatio = String(aspectRatio);
    });
    fromPlayer({ channel: channelOf(player), type: 'resize', aspectRatio: 4 / 3 });

    expect(player.frame.style.aspectRatio).not.toBe('16 / 9');
  });

  it('delivers events from its own player', () => {
    const player = embed();
    const heard = vi.fn();
    player.on('progress', heard);

    fromPlayer({ channel: channelOf(player), type: 'progress', seconds: 12, duration: 300 });

    expect(heard).toHaveBeenCalledWith({ seconds: 12, duration: 300 });
  });

  it('ignores a message from another origin, however well-formed', () => {
    const player = embed();
    const heard = vi.fn();
    player.on('progress', heard);

    fromPlayer(
      { channel: channelOf(player), type: 'progress', seconds: 99, duration: 300 },
      'https://not-us.example',
    );

    // An embedded player is in a page full of other frames, and a message event hears from all of
    // them. Origin is the check that makes a stranger's message untrusted.
    expect(heard).not.toHaveBeenCalled();
  });

  it('ignores a message from a different player on the same page', () => {
    const first = embed();
    const second = embedPlayer({
      into: document.body.appendChild(document.createElement('div')),
      origin: ORIGIN,
      nodeId: 'node-2',
      title: 'Second',
    });
    const heard = vi.fn();
    first.on('ended', heard);

    try {
      fromPlayer({ channel: channelOf(second), type: 'ended' });

      // Two of our own players are the same origin as each other, so origin alone cannot tell
      // their messages apart. That is the entire reason the channel id exists.
      expect(heard).not.toHaveBeenCalled();
    } finally {
      second.destroy();
    }
  });

  it('stops listening when it is destroyed', () => {
    const player = embed();
    const heard = vi.fn();
    player.on('ended', heard);
    const channel = channelOf(player);

    player.destroy();
    embedded = undefined;
    fromPlayer({ channel, type: 'ended' });

    expect(heard).not.toHaveBeenCalled();
    expect(document.querySelector('iframe')).toBeNull();
  });

  it('states a protocol version, so a host can tell what it is talking to', () => {
    expect(PLAYER_PROTOCOL).toMatch(/^\d+\.\d+$/);
  });
});
