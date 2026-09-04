/**
 * The embed contract (ADR-0110, T-3.5) — every message that crosses the iframe boundary, in one
 * file, because a contract spread across the code that sends it is a contract nobody can read.
 *
 * <p>This is the surface T-10.7 publishes and semver's. Adding a message is a minor version;
 * changing or removing one is a major, and the `PLAYER_PROTOCOL` below is what a host checks to
 * find out which it is talking to.
 *
 * <h2>Why every message carries a channel id</h2>
 *
 * A `message` listener hears from every frame on the page, including ones we did not create.
 * Filtering on origin is necessary and not sufficient — two of our own players on the same page
 * are the same origin as each other. The channel id is what makes "is this mine" answerable.
 */

export const PLAYER_PROTOCOL = '1.0';

/**
 * Host → player, without the channel.
 *
 * Split from {@link Command} rather than reached for with `Omit`, because `Omit` over a union
 * collapses it to the properties every member shares — so `Omit<Command, 'channel'>` loses
 * `seconds` and `rate` entirely, and the loss is silent until something tries to send one.
 */
export type CommandBody =
  | { type: 'play' }
  | { type: 'pause' }
  | { type: 'seek'; seconds: number }
  | { type: 'setRate'; rate: number };

/** Host → player, as it crosses the boundary. */
export type Command = CommandBody & { channel: string };

/** Player → host. */
export type Event =
  /** Sent once, when the player is listening. A host that commands before this is talking to
   *  nobody: the iframe's script may not have run yet. */
  | { channel: string; type: 'ready'; protocol: string }
  | { channel: string; type: 'progress'; seconds: number; duration: number }
  | { channel: string; type: 'ended' }
  /** A refusal or a playback failure, already rendered for the viewer. The host gets it so it can
   *  react — hide a player it cannot show, log an integration problem — not so it can display it,
   *  because it already is displayed. */
  | { channel: string; type: 'error'; message: string; terminal: boolean }
  /** The player's own idea of how tall it should be, so the host can size a frame that has no
   *  intrinsic aspect ratio. ADR-0110 names this as a cost of the iframe; this is that cost. */
  | { channel: string; type: 'resize'; aspectRatio: number };

export function isCommand(value: unknown, channel: string): value is Command {
  if (typeof value !== 'object' || value === null) {
    return false;
  }
  const message = value as { channel?: unknown; type?: unknown };
  return (
    message.channel === channel &&
    typeof message.type === 'string' &&
    ['play', 'pause', 'seek', 'setRate'].includes(message.type)
  );
}
