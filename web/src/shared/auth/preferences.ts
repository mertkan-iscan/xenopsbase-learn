import { identity } from '../api/client.ts';
import { chooseLocale } from '../i18n/locale.ts';
import type { Locale } from '../i18n/locales.ts';
import { chooseTheme, type Theme } from '../theme/theme.ts';

/**
 * Saving how somebody wants this product to look and read (T-10.9).
 *
 * <h2>The screen changes first and the request follows</h2>
 *
 * <p>{@link chooseTheme} and {@link chooseLocale} are called before the call is made, not after it
 * returns. A switch that waits for a round trip is a switch that feels broken on a slow
 * connection, and the thing being changed is a rendering preference — there is no state to
 * corrupt by being optimistic, and nothing downstream depends on the two being in step.
 *
 * <h2>A failed save is not shown, and that is a decision rather than an omission</h2>
 *
 * <p>If the request fails, the choice still applies in this browser and is still in
 * `localStorage`, so the person has what they asked for on the machine they asked for it on. What
 * they lose is that it follows them to their phone. Interrupting somebody with an error about a
 * colour they can already see would be the wrong trade — and the failure is self-correcting: the
 * next change, or the next sign-in from a browser that did save, writes it.
 *
 * <p>What must NOT happen is the opposite: reverting the screen because the save failed. That is
 * an error message written in the interface itself, and it takes away the thing that was working.
 */
export type Preferences = {
  /** Absent leaves the stored value alone; `''` clears it. Never send a guess. */
  language?: Locale | '';
  theme?: Theme | '';
};

/**
 * Applies a preference here and asks identity to remember it.
 *
 * <p>Only the fields that were passed are sent, and that is load-bearing at the API: an absent
 * field means "leave it alone", so a theme switch cannot wipe the language somebody set on another
 * device. `PreferencesResource` documents the same three-valued contract from the other side.
 */
export async function savePreferences(preferences: Preferences): Promise<void> {
  if (preferences.theme) {
    chooseTheme(preferences.theme);
  }
  if (preferences.language) {
    chooseLocale(preferences.language);
  }
  try {
    await identity.PUT('/api/v1/users/me/preferences', {
      body: {
        ...(preferences.language === undefined ? {} : { language: preferences.language }),
        // Upper-cased on the way out: identity stores this as an enum name, the way it stores
        // every other closed set, and `theme.ts` owns the conversion back. Sending `light` would
        // work today -- the parser is case-tolerant -- and would be a second spelling of the same
        // value travelling between two systems, which is where a mismatch eventually lives.
        ...(preferences.theme === undefined
          ? {}
          : { theme: preferences.theme === '' ? '' : preferences.theme.toUpperCase() }),
      },
    });
  } catch {
    // Deliberately silent. See above: the choice has already been applied and remembered here.
  }
}
