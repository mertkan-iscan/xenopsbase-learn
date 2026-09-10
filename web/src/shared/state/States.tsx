import { CircleAlert, Inbox } from 'lucide-react';
import type { ReactNode } from 'react';
import { Button } from '../design/Button.tsx';
import { useT } from '../i18n/useLocale.ts';
import type { LoadingKey } from '../i18n/messages.en.ts';

/**
 * Loading, failed and empty — defined once, before there are screens to retrofit (T-10.1).
 *
 * <p>These three states are the ones every screen has and the ones every screen gets wrong when
 * they are written per screen: a spinner nobody announces to a screen reader, an error swallowed
 * into a blank page, an empty list that looks like a failure. They are components rather than a
 * convention so that a new screen gets them by using them.
 *
 * <p>The roles below — `status`/`aria-live` on loading, `alert` on failure — are load-bearing
 * rather than markup: they are the difference between a page that changes underneath somebody and
 * a page that says so. <b>They have now survived three redesigns unchanged, and should survive the
 * next.</b> The indigo-and-glass rewrite changed every class in this file and not one attribute.
 *
 * <p>{@link Loading} takes a message key rather than a noun. It used to be
 * `<Loading what="your training" />`, rendering `Loading {what}…` — an English sentence assembled
 * at the call site. Turkish puts the verb last and the possessive on the noun
 * ("Eğitimleriniz yükleniyor…"), so there is no arrangement of a prefix and a noun that produces
 * it. The key names the whole sentence, and every language writes its own.
 */

export function Loading({ what }: { what: LoadingKey }) {
  const t = useT();
  return (
    // aria-busy plus a live region: a sighted person sees the bars settle, and somebody using a
    // screen reader is told, rather than left on a page that says nothing while it changes.
    //
    // The bars are decorative and hidden from the tree; the sentence underneath is the
    // announcement -- three shimmering rectangles announce nothing on their own.
    <div
      role="status"
      aria-live="polite"
      aria-busy="true"
      className="flex flex-col gap-3 py-8"
    >
      <span className="label-caps">{t('loading.label')}</span>
      <span aria-hidden="true" className="shimmer h-3 w-2/3 rounded-full" />
      <span aria-hidden="true" className="shimmer h-3 w-full rounded-full" />
      <span aria-hidden="true" className="shimmer h-3 w-5/12 rounded-full" />
      <span className="text-sm text-muted">{t(what)}</span>
    </div>
  );
}

export function ErrorState({ message, retry }: { message: string; retry?: () => void }) {
  const t = useT();
  return (
    // role="alert" because a failure is not a status update: it interrupts, on purpose.
    <div
      role="alert"
      className="flex flex-col items-start gap-3 rounded-xl border border-overdue-edge bg-overdue-bg p-5"
    >
      <span className="flex items-center gap-2 text-overdue-fg">
        <CircleAlert aria-hidden="true" className="size-4 shrink-0" />
        <span className="label-caps text-overdue-fg">{t('error.label')}</span>
      </span>
      <p className="font-display text-base font-semibold text-ink">{message}</p>
      {/*
       * Said on every failure, because it is true on every failure and it is the thing the person
       * is actually worried about. A learner who thinks a dropped request cost them twenty minutes
       * of a video behaves very differently from one who knows it did not.
       */}
      <p className="text-sm text-muted">{t('error.reassurance')}</p>
      {retry ? (
        <Button voice="secondary" size="sm" onClick={retry}>
          {t('error.retry')}
        </Button>
      ) : null}
    </div>
  );
}

export function Empty({ title, children }: { title: string; children?: ReactNode }) {
  const t = useT();
  return (
    <div className="flex flex-col items-start gap-3 rounded-xl border border-dashed border-hairline-strong bg-surface-muted p-6">
      <span className="flex items-center gap-2">
        <Inbox aria-hidden="true" className="size-4 shrink-0 text-subtle" />
        <span className="label-caps">{t('empty.label')}</span>
      </span>
      <p className="font-display text-base font-semibold text-ink">{title}</p>
      {/* An empty state says what to do next, or it is just a smaller failure. */}
      <div className="text-sm text-muted">{children}</div>
    </div>
  );
}
