import type { Locale } from './locales.ts';

/**
 * Dates, numbers, percentages and durations — from one place (docs/design-prompt.md).
 *
 * <p>They used to be four `toLocaleDateString(undefined, …)` calls in four screens, each with its
 * own option object and its own idea of what "undefined" meant. `undefined` there is the BROWSER's
 * locale, which is the wrong one the moment a person chooses a language that is not their
 * browser's — a Turkish screen with English month names is exactly the kind of half-translated
 * page that reads as broken.
 *
 * <p>Every formatter here is constructed per call. `Intl` caches internally, the volumes are a
 * handful per render, and a cached formatter keyed on a locale that can change is a bug waiting
 * for the first person who switches language without reloading.
 */

/** A day, as short as it can be and still unambiguous: "4 Sep", "4 Eyl". */
export function formatDay(locale: Locale, iso: string): string {
  return new Intl.DateTimeFormat(locale, { day: 'numeric', month: 'short' }).format(new Date(iso));
}

/** A moment, with the time on it: for an audit line or an invitation's expiry. */
export function formatMoment(locale: Locale, iso: string): string {
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'short' }).format(
    new Date(iso),
  );
}

export function formatNumber(locale: Locale, value: number): string {
  return new Intl.NumberFormat(locale).format(value);
}

/**
 * A whole percentage, with the sign where the language puts it.
 *
 * <p>English writes "62%" and Turkish writes "%62". That is the smallest possible example of why
 * this is a formatter and not a string with a `%` typed after it — and the one most likely to be
 * got wrong by hand, because the English form looks like punctuation rather than grammar.
 *
 * @param whole 0–100 as the API reports it, divided here because `Intl` works in fractions
 */
export function formatPercent(locale: Locale, whole: number): string {
  return new Intl.NumberFormat(locale, { style: 'percent', maximumFractionDigits: 0 }).format(
    whole / 100,
  );
}

/**
 * How long something has been waiting, in the largest unit that still says something.
 *
 * <p>`Intl.NumberFormat`'s unit style rather than a `+ ' min'`: it gives "5 min" and "5 dk" from
 * the same call, and it knows where the number goes relative to the unit in languages where that
 * is not the front. Minutes below an hour, hours below two days, days after that — the point of
 * the column is comparing one wait against another, and "4,320 min" compares badly.
 */
export function formatWaited(locale: Locale, seconds: number): string {
  const hours = Math.floor(seconds / 3600);
  const [value, unit] =
    hours < 1
      ? [Math.floor(seconds / 60), 'minute' as const]
      : hours < 48
        ? [hours, 'hour' as const]
        : [Math.floor(hours / 24), 'day' as const];
  return new Intl.NumberFormat(locale, { style: 'unit', unit, unitDisplay: 'short' }).format(value);
}

/**
 * A position in a video, as `m:ss`.
 *
 * <p>NOT localised, on purpose: a timecode is read against the player's own scrubber, which shows
 * the same colon-separated digits in every language. Turning it into "1 dakika 4 saniye" would
 * make the two disagree on screen. Locale-aware digits would be a real consideration for a
 * language with its own numerals; neither language here has them.
 */
export function formatPosition(seconds: number): string {
  const whole = Math.max(0, Math.floor(seconds / 60));
  const rest = Math.max(0, Math.floor(seconds % 60));
  return `${whole}:${String(rest).padStart(2, '0')}`;
}
