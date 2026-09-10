import { useSyncExternalStore } from 'react';
import { currentLocale, subscribeToLocale } from './locale.ts';
import type { MessageKey, PluralKey } from './messages.en.ts';
import { plural as pluralise, translate, type Params } from './t.ts';

/**
 * The language this render is in, and the function that speaks it.
 *
 * <p>`useSyncExternalStore` rather than a context: the store in {@link ./locale.ts} is the
 * authority, because non-React code has to read the same value. This is the subscription that
 * makes a component re-render when it changes — with no provider to mount, and therefore no test
 * that renders a screen and gets an exception about a missing one.
 *
 * <p>The third argument is the server snapshot, and it is the same function. This application is a
 * static build with no server rendering (docs/frontend.md), so there is no second environment for
 * it to differ in; passing it silences the hydration warning honestly rather than by assertion.
 */
export function useLocale() {
  const locale = useSyncExternalStore(subscribeToLocale, currentLocale, currentLocale);
  return {
    locale,
    t: (key: MessageKey, params?: Params) => translate(locale, key, params),
    plural: (key: PluralKey, count: number, params?: Params) =>
      pluralise(locale, key, count, params),
  };
}

/** The common case: just the lookup. */
export function useT() {
  return useLocale().t;
}
