import { useOutletContext } from 'react-router';

/**
 * What the shell already knows and a screen would otherwise have to ask for again.
 *
 * <p>WHY THIS EXISTS. Home wants the person's first name for its greeting, and the obvious way to
 * get it — calling `useSession()` in the screen — is a SECOND `/auth/session` request on the
 * most-hit authenticated screen in the product, because `readSession` does not cache. The whole
 * design of Home is built on it needing exactly one call (T-5.8, docs/design-prompt.md), and
 * spending a round trip on a nicety is the wrong trade on the connection this product assumes.
 *
 * <p>The shell has already asked. This is the router handing the answer down, which costs nothing.
 *
 * <p>In its own module rather than exported from `Shell.tsx` so that a screen wanting the name does
 * not import the shell — and so this stays a type and one hook, which is all a screen should be
 * able to reach for.
 */
export type ShellContext = {
  /** The signed-in person's display name, or `null` before the session has answered. */
  name: string | null;
};

export function useShellContext(): ShellContext {
  // `useOutletContext` is typed as whatever you claim, so the cast is unavoidable. It is safe
  // because every route in `routes.tsx` is a child of `Shell`, which is the only thing that
  // provides it -- and a route added outside that tree would fail immediately and visibly rather
  // than subtly, since `name` would be undefined on first render.
  return useOutletContext<ShellContext>();
}
