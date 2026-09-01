import type { ReactNode } from 'react';

/**
 * Loading, failed and empty — defined once, before there are screens to retrofit (T-10.1).
 *
 * <p>These three states are the ones every screen has and the ones every screen gets wrong when
 * they are written per screen: a spinner nobody announces to a screen reader, an error swallowed
 * into a blank page, an empty list that looks like a failure. They are components rather than a
 * convention so that a new screen gets them by using them.
 */

export function Loading({ what }: { what: string }) {
  return (
    // aria-busy plus a live region: a sighted person sees the text, and somebody using a screen
    // reader is told, rather than left on a page that says nothing while it changes underneath.
    <p className="state state--loading" role="status" aria-live="polite" aria-busy="true">
      Loading {what}…
    </p>
  );
}

export function ErrorState({ message, retry }: { message: string; retry?: () => void }) {
  return (
    // role="alert" because a failure is not a status update: it interrupts, on purpose.
    <div className="state state--error" role="alert">
      <p>{message}</p>
      {retry ? (
        <button type="button" onClick={retry}>
          Try again
        </button>
      ) : null}
    </div>
  );
}

export function Empty({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="state state--empty">
      <p className="state__title">{title}</p>
      {/* An empty state says what to do next, or it is just a smaller failure. */}
      {children}
    </div>
  );
}
