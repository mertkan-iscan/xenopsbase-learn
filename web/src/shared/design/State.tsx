/**
 * The eight semantic states, as one closed set (docs/design-prompt.md).
 *
 * <p>These carry more meaning in this product than any component does, so they are a union rather
 * than a string: a screen cannot invent a ninth, and adding one is a type error everywhere it has
 * to be drawn. The same reasoning the backend applies to `IntegritySignal`, which is an enum whose
 * constants each carry their own learner-facing sentence rather than a lookup somewhere else.
 *
 * <p>THE ONE THAT MATTERS MOST IS `awaiting`. `passed: null` with `grading: AWAITING_GRADING` is
 * not a fail, and drawing it as one locks a learner out of a course they may well have passed —
 * with nothing anywhere reporting a fault. It is dashed and unresolved on purpose, and it is the
 * reason this is a component at all rather than a className each screen picks.
 */
import { useT } from '../i18n/useLocale.ts';
import type { MessageKey } from '../i18n/messages.en.ts';

export type StateName =
  | 'due'
  | 'overdue'
  | 'in-progress'
  | 'locked'
  | 'awaiting'
  | 'passed'
  | 'not-passed'
  | 'draft'
  | 'published';

/**
 * The key each state is said with, rather than the words themselves.
 *
 * <p>A `Record<StateName, MessageKey>` rather than a template string built from the name: the
 * mapping is explicit, so a state renamed in the union without a sentence written for it does not
 * compile — the same guarantee the union itself gives, extended to the words.
 */
const labels: Record<StateName, MessageKey> = {
  due: 'state.due',
  overdue: 'state.overdue',
  'in-progress': 'state.in-progress',
  locked: 'state.locked',
  awaiting: 'state.awaiting',
  passed: 'state.passed',
  'not-passed': 'state.not-passed',
  draft: 'state.draft',
  published: 'state.published',
};

/**
 * @param detail appended to the label — the date an obligation passed, how long ago, the version
 *   number. `overdue` should always carry one: "overdue" without a date is a scolding rather than
 *   information.
 */
export function StateChip({
  state,
  detail,
}: {
  state: StateName;
  detail?: string | undefined;
}) {
  const t = useT();
  return (
    <span className={`chip chip--${state}`}>
      {t(labels[state])}
      {detail ? ` · ${detail}` : ''}
    </span>
  );
}

/**
 * A filled rule, never a ring.
 *
 * <p>`percent` is the server's record of what has been watched, not the browser's idea of where
 * the video is — the two disagree whenever somebody seeks, and only one of them decides whether a
 * gate opens.
 */
export function Progress({
  percent,
  label,
  dense = false,
}: {
  percent: number;
  label: string;
  dense?: boolean;
}) {
  const clamped = Math.max(0, Math.min(100, Math.round(percent)));
  return (
    <span
      className={dense ? 'progress progress--dense' : 'progress'}
      role="progressbar"
      aria-valuenow={clamped}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-label={label}
    >
      <span className="progress__fill" style={{ width: `${clamped}%` }} />
    </span>
  );
}
