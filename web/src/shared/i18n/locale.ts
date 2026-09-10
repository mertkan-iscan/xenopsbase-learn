import { FALLBACK, isLocale, localeFromBrowser, type Locale } from './locales.ts';

/**
 * Which language this document is being read in.
 *
 * <h2>A store outside React, subscribed to from inside it</h2>
 *
 * <p>This was a context provider first, and it was wrong. `shared/api/client.ts` turns a failed
 * response into a sentence, and it is a plain function called from promise callbacks all over the
 * product — there is no hook available to it, and making one would put React in front of every API
 * call. A provider that also published the current locale to a module variable had to assign that
 * variable while rendering, which is a side effect during render and which
 * `react-hooks/globals` rejects for good reasons.
 *
 * <p>So the direction is inverted: <b>the store is the authority and React subscribes to it</b>,
 * through `useSyncExternalStore` in {@link ./useLocale.ts}. Non-React callers read
 * {@link currentLocale} directly; components re-render because they are subscribed. Nothing
 * mutates anything during a render.
 *
 * <p>One document reads in one language, so a module-level value is not a shortcut here — it is
 * the shape of the thing. Two languages side by side on one page is not a case this product has.
 *
 * <h2>Where the answer comes from, in order</h2>
 *
 * <ol>
 *   <li><b>The server</b>, through `/api/v1/me`. A person's language is a property of the person,
 *       not of the machine they are at, so it follows them to a phone and to a shared terminal on
 *       a factory floor. That is the record.
 *   <li><b>This browser's last known answer</b>, in `localStorage`. A cache, and only for the few
 *       hundred milliseconds before `/me` returns — without it every reload shows English and then
 *       blinks into Turkish, which looks like a fault.
 *   <li><b>The browser's own preference</b>, for somebody who has never told us.
 *   <li><b>English</b>.
 * </ol>
 */

const REMEMBERED = 'xenops.locale';

function remembered(): Locale | null {
  try {
    const saved = window.localStorage.getItem(REMEMBERED);
    return isLocale(saved) ? saved : null;
  } catch {
    // Private windows, and browsers set to block site data, throw on the accessor itself. A
    // language preference is not worth a blank page.
    return null;
  }
}

function remember(locale: Locale) {
  try {
    window.localStorage.setItem(REMEMBERED, locale);
  } catch {
    // See above. The server holds the record; this was only ever the head start.
  }
}

let current: Locale = remembered() ?? localeFromBrowser() ?? FALLBACK;
const listeners = new Set<() => void>();

/** The locale, for code that is not a component. */
export function currentLocale(): Locale {
  return current;
}

export function subscribeToLocale(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/**
 * Puts the whole document into a language.
 *
 * <p>Not exported for general use — {@link chooseLocale} and {@link adoptServerLocale} are the two
 * ways this is meant to happen, and they differ in whether the choice is remembered. The `lang`
 * attribute is set here because it belongs to the same fact: a screen reader chooses its voice
 * from it, and a Turkish page marked `lang="en"` is read aloud as though it were misspelt English.
 */
function apply(next: Locale) {
  if (next === current) {
    return;
  }
  current = next;
  if (typeof document !== 'undefined') {
    document.documentElement.lang = next;
  }
  for (const listener of listeners) {
    listener();
  }
}

/** A deliberate choice made in this browser. The caller is responsible for telling the server. */
export function chooseLocale(next: Locale) {
  remember(next);
  apply(next);
}

/**
 * What `/api/v1/me` said.
 *
 * <p><b>Null is not English.</b> `app_user.language` is nullable for the same reason `time_zone`
 * is — V13's migration says it plainly: "they have not told us" is a findable population and "they
 * chose English" is not, and collapsing the two loses that distinction forever. So a null falls
 * through to whatever this browser had already resolved, and pins nobody to the fallback.
 */
export function adoptServerLocale(next: Locale | null) {
  if (next === null) {
    return;
  }
  remember(next);
  apply(next);
}

/**
 * Forces a locale before React starts, for the embedded player and for tests.
 *
 * <p>Deliberately does NOT remember: the player is told its language by the host page it is
 * embedded in, and writing that into this origin's storage would let one customer's English page
 * decide what language the learner's own app opens in afterwards.
 */
export function forceLocale(next: Locale | null | undefined) {
  if (next) {
    apply(next);
  }
}
