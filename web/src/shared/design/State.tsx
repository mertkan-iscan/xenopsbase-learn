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

const labels: Record<StateName, string> = {
  due: 'Due',
  overdue: 'Overdue',
  'in-progress': 'In progress',
  locked: 'Locked',
  awaiting: 'Awaiting grading',
  passed: 'Passed',
  'not-passed': 'Not passed',
  draft: 'Draft',
  published: 'Published',
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
  return (
    <span className={`chip chip--${state}`}>
      {labels[state]}
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
