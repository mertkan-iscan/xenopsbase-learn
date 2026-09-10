import { useSyncExternalStore } from 'react';

/**
 * The one width this design changes shape at, stated once.
 *
 * <p>It is `--breakpoint-desk` in `styles.css`, which is where the `desk:` variant comes from, and
 * it is this string wherever JavaScript has to know the same thing. Two places rather than one is
 * unavoidable — CSS cannot hand a media query to a component — but two places is the ceiling, and
 * a screen that hard-codes `60rem` a third time is the thing this module exists to prevent.
 *
 * <p>The shell needs it to know when to stop holding a drawer open. The item shell needs it to
 * decide whether the syllabus is a panel beside the video or a tab underneath it — which is a
 * question about structure rather than appearance, so CSS alone cannot answer it.
 */
export const DESK = '(min-width: 60rem)';

/**
 * The query, made once and kept.
 *
 * <p>Lazily, because this module is imported by components that run in jsdom, where `matchMedia`
 * is a stub installed by `test/setup.ts` — evaluating it at module scope would run before the
 * stub and throw during import rather than during a render.
 */
let query: MediaQueryList | null = null;

function media(): MediaQueryList {
  query ??= window.matchMedia(DESK);
  return query;
}

function subscribe(listener: () => void): () => void {
  const wide = media();
  wide.addEventListener('change', listener);
  return () => wide.removeEventListener('change', listener);
}

/**
 * Whether there is room for the desktop shape.
 *
 * <p><b>`useSyncExternalStore` rather than `useState` plus an effect</b>, and that is a
 * correctness point rather than a style one. The obvious version — `useState(false)` and an effect
 * that calls `setWide(query.matches)` on mount — sets state synchronously inside an effect, which
 * `react-hooks` v7 refuses outright as a cascading render: the component renders the phone layout,
 * commits it, then immediately renders again. `useSyncExternalStore` reads the real answer during
 * the FIRST render instead, so a desktop browser never paints the phone layout at all.
 *
 * <p>The server snapshot is `false` — mobile-first, which is not a slogan here: a learner is
 * assumed to be on a mid-range Android phone (docs/design-prompt.md), so the layout that renders
 * without a measurement is theirs. This is a static build with no server rendering
 * (docs/frontend.md), so nothing but jsdom ever takes that branch.
 */
export function useIsDesktop(): boolean {
  return useSyncExternalStore(
    subscribe,
    () => media().matches,
    () => false,
  );
}
