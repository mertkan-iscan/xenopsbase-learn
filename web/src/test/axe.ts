import { expect } from 'vitest';
import { axe } from 'vitest-axe';

/**
 * An accessibility assertion that says what it actually checked (T-10.1).
 *
 * <p>Colour contrast is disabled, and not because it does not matter: jsdom has no layout and no
 * canvas, so axe cannot measure it here and reports nothing either way. Leaving the rule enabled
 * would make this suite look like it checks contrast while checking nothing — the failure mode
 * this project keeps refusing everywhere else. Contrast belongs to a browser-based check when
 * there are screens worth running one against (T-10.8).
 */
export async function expectNoAxeViolations(container: Element) {
  const results = await axe(container, {
    rules: { 'color-contrast': { enabled: false } },
  });
  expect(results).toHaveNoViolations();
}
