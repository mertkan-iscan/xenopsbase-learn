import type { AxeMatchers } from 'vitest-axe/matchers';

/**
 * vitest-axe still augments the old global `Vi` namespace, which Vitest 4 no longer reads: the
 * matcher is registered at run time and invisible to the type checker. This is the same
 * augmentation in the shape Vitest 4 uses -- the one @testing-library/jest-dom already uses.
 *
 * <p>Written out rather than worked around with a cast, because a cast would also hide the day
 * the matcher stops existing, and catching that is what the build is for.
 */
declare module 'vitest' {
  // eslint-disable-next-line @typescript-eslint/no-empty-object-type, @typescript-eslint/no-unused-vars
  interface Assertion<T = unknown> extends AxeMatchers {}
}
