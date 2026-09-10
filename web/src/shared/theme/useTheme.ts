import { useSyncExternalStore } from 'react';
import { currentTheme, subscribeToTheme } from './theme.ts';

/**
 * The palette this render is in (T-10.9).
 *
 * <p>`useSyncExternalStore` rather than a context, for the reasons `useLocale` gives: the store in
 * `theme.ts` is the authority because it writes to `document.documentElement` before React exists,
 * and there is no provider to mount — so no test that renders a screen fails with an exception
 * about a missing one.
 *
 * <p>The third argument is the server snapshot and is the same function. This application is a
 * static build with no server rendering (docs/frontend.md), so there is no second environment for
 * it to differ in.
 */
export function useTheme() {
  return useSyncExternalStore(subscribeToTheme, currentTheme, currentTheme);
}
