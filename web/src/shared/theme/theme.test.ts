import { beforeEach, describe, expect, it } from 'vitest';
import { adoptServerTheme, chooseTheme, currentTheme, themeFrom } from './theme.ts';

/**
 * The palette store (T-10.9).
 *
 * <p>Two of these assert things that would be invisible in a browser until somebody complained:
 * that `system` REMOVES the attribute rather than setting it to the word, and that a null from the
 * server does not overwrite a choice made here a moment ago.
 */
describe('the theme store', () => {
  beforeEach(() => {
    window.localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
  });

  it('pins the document to an explicit choice', () => {
    chooseTheme('light');
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
    expect(currentTheme()).toBe('light');

    chooseTheme('dark');
    expect(document.documentElement.getAttribute('data-theme')).toBe('dark');
  });

  it('removes the attribute for “match my device” rather than writing the word', () => {
    chooseTheme('dark');
    chooseTheme('system');

    /*
     * THE ONE THAT WOULD BE A SILENT BUG. styles.css resolves dark through
     * `@media (prefers-color-scheme: dark)` guarded by `:root:not([data-theme='light'])`, and
     * light through the bare `:root`. `data-theme="system"` matches neither branch's override, so
     * everybody who chose "match my device" would be pinned to the light palette on a dark
     * machine -- and the attribute would be sitting right there in the DOM saying the opposite.
     */
    expect(document.documentElement.hasAttribute('data-theme')).toBe(false);
    expect(currentTheme()).toBe('system');
  });

  it('remembers the choice in this browser', () => {
    chooseTheme('dark');
    expect(window.localStorage.getItem('xenops.theme')).toBe('dark');
  });

  it('takes the server’s answer, in the case the server writes it', () => {
    // identity carries the enum's own name, uppercase, the way it carries every closed set.
    adoptServerTheme(themeFrom('DARK'));
    expect(currentTheme()).toBe('dark');
  });

  it('does not treat “they have not told us” as a choice', () => {
    chooseTheme('light');
    // A person who picked light here thirty seconds ago and has not had it saved yet. Applying a
    // default on top of that would undo a choice in front of them.
    adoptServerTheme(themeFrom(null));
    expect(currentTheme()).toBe('light');
  });

  it('ignores a value this product has no palette for', () => {
    chooseTheme('dark');
    expect(themeFrom('sepia')).toBeNull();
    adoptServerTheme(themeFrom('sepia'));
    expect(currentTheme()).toBe('dark');
  });
});
