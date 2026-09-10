import { currentLocale } from './locale.ts';
import { FALLBACK, type Locale } from './locales.ts';
import { en, type MessageKey, type PluralKey } from './messages.en.ts';
import { tr } from './messages.tr.ts';

/**
 * Looking a sentence up, and putting the numbers and names into it.
 *
 * <p>Roughly a hundred lines instead of a library, and it buys the one thing a library cannot: the
 * catalogues are typed against each other, so a missing Turkish sentence is a build failure rather
 * than an English word appearing on a Turkish screen. The rest — plural categories, dates, numbers
 * — is `Intl`, which is already in every browser this product supports.
 */
const catalogues: Record<Locale, Record<MessageKey, string>> = { en, tr };

export type Params = Record<string, string | number>;

/**
 * The sentence for `key`, with `{placeholders}` filled in.
 *
 * <p>A key missing from a catalogue falls back to English rather than throwing: the type system
 * makes that unreachable through normal code, and the one path that can still reach it is a
 * message key arriving from somewhere untyped. A learner should not meet a blank screen because of
 * it.
 */
export function translate(locale: Locale, key: MessageKey, params?: Params): string {
  const sentence = catalogues[locale][key] ?? catalogues[FALLBACK][key];
  return params ? fill(sentence, params) : sentence;
}

/**
 * The count-sensitive form of a sentence.
 *
 * <p>`key` names a family — `x.one` and `x.other` — and `Intl.PluralRules` picks. NOT an
 * `n === 1` test: English happens to work that way and Turkish does not have the distinction at
 * all, so a hand-written check would be an English grammar rule applied to every language the
 * product ever adds.
 *
 * <p>`count` is available to the sentence as `{count}`, already formatted for the locale, so a
 * caller never has to pass it twice.
 */
export function plural(
  locale: Locale,
  key: PluralKey,
  count: number,
  params?: Params,
): string {
  const category = new Intl.PluralRules(locale).select(count);
  const chosen = `${key}.${category}` as MessageKey;
  const known = catalogues[locale][chosen] ?? catalogues[locale][`${key}.other` as MessageKey];
  const sentence = known ?? catalogues[FALLBACK][`${key}.other` as MessageKey] ?? key;
  return fill(sentence, { ...params, count: new Intl.NumberFormat(locale).format(count) });
}

/**
 * `{name}` substitution, and nothing more.
 *
 * <p>Deliberately not a template language. Anything a placeholder could need — a plural, a date, a
 * percentage — has its own function above or in `format.ts`, where it is done once and correctly,
 * rather than being expressed in a mini-syntax inside four hundred strings.
 *
 * <p>An unmatched placeholder is left as it is written. It is visible in a screenshot, which is
 * how somebody notices; silently deleting it produces a sentence with a hole in it that reads as
 * though it were meant.
 */
function fill(sentence: string, params: Params): string {
  return sentence.replace(/\{(\w+)\}/g, (whole, name: string) =>
    name in params ? String(params[name]) : whole,
  );
}

/**
 * The lookup, for code that is not a component.
 *
 * <p>`shared/api/client.ts` is the caller this exists for: it turns a failed response into a
 * sentence from inside a promise callback, where there is no hook and no component. It reads the
 * store directly rather than being handed a locale, so that no call site has to thread one through
 * four layers of error handling to say "not found".
 */
export function currentT(key: MessageKey, params?: Params): string {
  return translate(currentLocale(), key, params);
}
