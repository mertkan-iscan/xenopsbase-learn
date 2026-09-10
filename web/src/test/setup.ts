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
