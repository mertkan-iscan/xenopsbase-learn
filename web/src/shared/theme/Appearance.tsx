import { Monitor, Moon, Sun } from 'lucide-react';
import { savePreferences } from '../auth/preferences.ts';
import { FieldGroup } from '../design/Field.tsx';
import { LOCALES, type Locale } from '../i18n/locales.ts';
import { useLocale, useT } from '../i18n/useLocale.ts';
import type { MessageKey } from '../i18n/messages.en.ts';
import { THEMES, type Theme } from './theme.ts';
import { useTheme } from './useTheme.ts';

/**
 * Choosing a palette and a language (T-10.9).
 *
 * <h2>Radio buttons, not a toggle</h2>
 *
 * <p>There are three answers — light, dark, and follow the device — and a two-state switch can
 * only express two of them. Products that ship a toggle end up with "follow the device" hidden in
 * a settings page nobody finds, or dropped entirely, which strands everybody whose operating
 * system already switches at dusk.
 *
 * <p>They are real `<input type="radio">` elements in a `fieldset` with a `legend`, not buttons
 * with `aria-pressed`. Arrow keys move between them, the group is announced with its question, and
 * the browser does all of that for free — which is what the visually-hidden input plus a styled
 * label is buying here.
 */
const themeIcons: Record<Theme, typeof Sun> = {
  light: Sun,
  dark: Moon,
  system: Monitor,
};

const themeLabels: Record<Theme, MessageKey> = {
  light: 'prefs.theme.light',
  dark: 'prefs.theme.dark',
  system: 'prefs.theme.system',
};

const languageLabels: Record<Locale, MessageKey> = {
  en: 'prefs.language.en',
  tr: 'prefs.language.tr',
};

export function Appearance({ signedIn }: { signedIn: boolean }) {
  const t = useT();
  const { locale } = useLocale();
  const theme = useTheme();

  return (
    <div className="flex flex-col gap-4">
      <FieldGroup legend={t('prefs.theme')}>
        <div className="flex flex-wrap gap-1.5">
          {THEMES.map((option) => {
            const Icon = themeIcons[option];
            const chosen = option === theme;
            return (
              <label
                key={option}
                className={`inline-flex min-h-9 cursor-pointer items-center gap-2 rounded-lg border px-3 text-[0.8125rem] font-semibold transition-colors duration-150 ${
                  chosen
                    ? 'border-brand-tint-edge bg-brand-tint text-brand'
                    : 'border-hairline bg-surface text-muted hover:bg-surface-muted hover:text-ink'
                }`}
              >
                {/*
                 * `sr-only` and NOT `hidden`, and not `appearance-none` either. A hidden input is
                 * not focusable, so the whole group drops out of the tab order and the arrow keys
                 * stop working -- which is the exact accessibility this pattern exists to keep.
                 * Visually hidden leaves it focusable, and `focus-within` below draws the ring on
                 * the label the person can actually see.
                 */}
                <input
                  type="radio"
                  name="theme"
                  value={option}
                  checked={chosen}
                  onChange={() => void savePreferences({ theme: option })}
                  className="sr-only"
                />
                <Icon aria-hidden="true" className="size-4" />
                {t(themeLabels[option])}
              </label>
            );
          })}
        </div>
      </FieldGroup>

      <FieldGroup legend={t('prefs.language')}>
        <div className="flex flex-wrap gap-1.5">
          {LOCALES.map((option) => {
            const chosen = option === locale;
            return (
              <label
                key={option}
                className={`inline-flex min-h-9 cursor-pointer items-center gap-2 rounded-lg border px-3 text-[0.8125rem] font-semibold transition-colors duration-150 ${
                  chosen
                    ? 'border-brand-tint-edge bg-brand-tint text-brand'
                    : 'border-hairline bg-surface text-muted hover:bg-surface-muted hover:text-ink'
                }`}
              >
                <input
                  type="radio"
                  name="language"
                  value={option}
                  checked={chosen}
                  onChange={() => void savePreferences({ language: option })}
                  className="sr-only"
                />
                {/*
                 * Each language is named IN ITSELF -- "Türkçe", not "Turkish". Somebody who has
                 * landed in a language they cannot read is exactly the person using this control,
                 * and a list translated into the language they are stuck in does not help them.
                 */}
                {t(languageLabels[option])}
              </label>
            );
          })}
        </div>
      </FieldGroup>

      {/*
       * Says where the choice went, and the two sentences are different facts rather than
       * reassurance. Signed in, it is on the account and follows them to their phone; signed out,
       * it is in this browser only -- which matters most on the shared terminal a factory-floor
       * learner is standing at.
       */}
      <p className="text-xs text-muted">
        {t(signedIn ? 'prefs.saved-to-account' : 'prefs.saved-here')}
      </p>
    </div>
  );
}
