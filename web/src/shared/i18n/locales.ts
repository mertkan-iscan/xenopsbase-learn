/**
 * The locales this product is built in, and how a browser's answer becomes one of them.
 *
 * <p>A closed union rather than a string, for the same reason `StateName` is one: a screen cannot
 * ask for a language that has no catalogue, and adding one is a type error everywhere it has to be
 * resolved rather than a `undefined` that renders as a blank page.
 */
export const LOCALES = ['en', 'tr'] as const;

export type Locale = (typeof LOCALES)[number];

/** The one used when nobody has said otherwise, including by the services. */
export const FALLBACK: Locale = 'en';

export function isLocale(value: string | null | undefined): value is Locale {
  return value !== null && value !== undefined && (LOCALES as readonly string[]).includes(value);
}

/**
 * A BCP-47 tag reduced to a locale we have a catalogue for.
 *
 * <p>Matched on the PRIMARY SUBTAG only: `tr-CY` is Turkish as spoken in Cyprus and this product
 * has one Turkish, so refusing it over the region would hand a Turkish speaker an English screen
 * to prove a point. Region- and script-specific catalogues are a later problem, and this is the
 * function that would grow to hold them.
 */
export function localeFrom(tag: string | null | undefined): Locale | null {
  if (!tag) {
    return null;
  }
  const primary = tag.trim().toLowerCase().split(/[-_]/)[0];
  return isLocale(primary) ? primary : null;
}

/**
 * What the browser says, reduced the same way.
 *
 * <p>`languages` in order rather than `language`: somebody whose first preference is a language we
 * do not have and whose second is Turkish should get Turkish, not English. `navigator.language` is
 * only the first entry and would miss that.
 */
export function localeFromBrowser(): Locale | null {
  if (typeof navigator === 'undefined') {
    return null;
  }
  const preferences = navigator.languages?.length ? navigator.languages : [navigator.language];
  for (const tag of preferences) {
    const found = localeFrom(tag);
    if (found) {
      return found;
    }
  }
  return null;
}
