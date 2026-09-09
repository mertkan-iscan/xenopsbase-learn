import type { ReactNode } from 'react';

/**
 * Loading, failed and empty — defined once, before there are screens to retrofit (T-10.1).
 *
 * <p>These three states are the ones every screen has and the ones every screen gets wrong when
 * they are written per screen: a spinner nobody announces to a screen reader, an error swallowed
 * into a blank page, an empty list that looks like a failure. They are components rather than a
 * convention so that a new screen gets them by using them.
 *
 * <p>The design (docs/design-prompt.md) changed how they look and nothing about what they are.
 * The three signatures are unchanged, and the roles below — `status`/`aria-live` on loading,
 * `alert` on failure — are load-bearing rather than markup: they are the difference between a
 * page that changes underneath somebody and a page that says so.
 */

export function Loading({ what }: { what: string }) {
  return (
    // aria-busy plus a live region: a sighted person sees the bars settle, and somebody using a
    // screen reader is told, rather than left on a page that says nothing while it changes.
    //
    // The bars are decorative and hidden from the tree; the sentence underneath is the announcement
    // -- three pulsing rectangles announce nothing on their own.
    <div className="state state--loading" role="status" aria-live="polite" aria-busy="true">
      <span className="state__label">Loading</span>
      <span className="state__bar" aria-hidden="true" />
      <span className="state__bar" aria-hidden="true" />
      <span className="state__bar" aria-hidden="true" />
      <span className="u-meta">Loading {what}…</span>
    </div>
  );
}

export function ErrorState({ message, retry }: { message: string; retry?: () => void }) {
  return (
    // role="alert" because a failure is not a status update: it interrupts, on purpose.
    <div className="state state--error" role="alert">
      <span className="state__label">Something went wrong</span>
      <p className="state__title">{message}</p>
      {/*
       * Said on every failure, because it is true on every failure and it is the thing the person
       * is actually worried about. A learner who thinks a dropped request cost them twenty minutes
       * of a video behaves very differently from one who knows it did not.
       */}
      <p className="u-meta">Your progress is saved. Nothing was lost.</p>
      {retry ? (
        <button type="button" className="btn btn-secondary" onClick={retry}>
          Try again
        </button>
      ) : null}
    </div>
  );
}

export function Empty({ title, children }: { title: string; children?: ReactNode }) {
  return (
    <div className="state state--empty">
      <span className="state__label">Nothing here</span>
      <p className="state__title">{title}</p>
      {/* An empty state says what to do next, or it is just a smaller failure. */}
      {children}
    </div>
  );
}
