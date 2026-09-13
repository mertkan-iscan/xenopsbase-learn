import '@testing-library/jest-dom/vitest';
import { expect } from 'vitest';
import * as matchers from 'vitest-axe/matchers';

// Accessibility assertions are ordinary assertions here, available in every test rather than in
// a special suite somebody remembers to run (T-10.1).
expect.extend(matchers);

/**
 * `window.matchMedia`, which jsdom does not implement.
 *
 * <p>The shell asks for it on mount: the navigation drawer may only be open below 60rem, so it
 * watches that query and closes the drawer if the window is widened past it (a desktop browser
 * dragged, a tablet turned). Without a stub, every test that renders the shell fails inside a
 * passive effect with "matchMedia is not a function" — an error about the environment wearing the
 * costume of an error about the component.
 *
 * <p><b>`matches: false` is the deliberate default</b>, not a shrug: false means "narrower than
 * the query", so the suite renders the phone layout. This is a mobile-first product whose learner
 * is assumed to be on a mid-range Android (docs/design-prompt.md), and the layout most worth
 * having under test is that one. A test needing the desktop branch stubs it itself.
 *
 * <p>Both listener APIs are present because the modern pair is what the code uses and the
 * deprecated pair is what some libraries still reach for; a stub missing either produces a
 * failure inside a dependency rather than in our own code.
 */
if (!window.matchMedia) {
  window.matchMedia = ((query: string) => ({
    matches: false,
    media: query,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  })) as typeof window.matchMedia;
}

/**
 * `localStorage` and `sessionStorage`, which this jsdom provides as an object with no methods.
 *
 * <p>Not a convenience. Two things in this application remember a choice in the browser — the
 * language (`i18n/locale.ts`) and the palette (`theme/theme.ts`) — and both wrap every access in a
 * `try`/`catch`, because a private window or a browser set to block site data throws on the
 * accessor itself. That is correct, and it means a suite with no storage silently exercises only
 * the degraded path: every test passes, and nothing ever asserts that a preference is actually
 * remembered.
 *
 * <p>So this is a real implementation, backed by a `Map`, installed only when the environment has
 * not provided one. It is cleared between tests by the tests that care; a shared reset here would
 * be a hidden dependency for every test that does not.
 *
 * <p>The `Storage` contract is small enough to implement honestly: values are strings (a number
 * stored and read back is `"1"`, which is where a lazy stub diverges from a browser), a missing key
 * is `null` and not `undefined`, and `length` and `key` exist because code that enumerates storage
 * is code this stub should not quietly break.
 */
function memoryStorage(): Storage {
  const values = new Map<string, string>();
  return {
    get length() {
      return values.size;
    },
    clear() {
      values.clear();
    },
    getItem(key: string) {
      // `null`, never `undefined`: the difference is invisible under `??` and very visible under
      // `=== null`, which is what a guard against "nothing stored" is usually written as.
      return values.has(key) ? (values.get(key) as string) : null;
    },
    key(index: number) {
      return [...values.keys()][index] ?? null;
    },
    removeItem(key: string) {
      values.delete(key);
    },
    setItem(key: string, value: string) {
      values.set(key, String(value));
    },
  };
}

for (const name of ['localStorage', 'sessionStorage'] as const) {
  if (typeof window[name]?.setItem !== 'function') {
    Object.defineProperty(window, name, { configurable: true, value: memoryStorage() });
  }
}
