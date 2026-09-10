/**
 * The nine semantic states, as one closed set (docs/design-prompt.md).
 *
 * <p>These carry more meaning in this product than any component does, so they are a union rather
 * than a string: a screen cannot invent a tenth, and adding one is a type error everywhere it has
 * to be drawn. The same reasoning the backend applies to `IntegritySignal`, which is an enum whose
 * constants each carry their own learner-facing sentence rather than a lookup somewhere else.
 *
 * <p>THE ONE THAT MATTERS MOST IS `awaiting`. `passed: null` with `grading: AWAITING_GRADING` is
 * not a fail, and drawing it as one locks a learner out of a course they may well have passed —
 * with nothing anywhere reporting a fault. It is amber and dashed on purpose: unresolved, rather
 * than a greyed-out version of a verdict.
 *
 * <p>COLOUR IS NEVER THE ONLY CHANNEL. Every chip carries three: a tint, a word, and an icon. A
 * learner with a colour vision deficiency, a printed compliance report, and a screenshot pasted
 * into a support ticket all have to survive losing the hue.
 */
import {
  CalendarClock,
  CircleCheck,
  CirclePlay,
  CircleX,
  Globe,
  Hourglass,
  Lock,
  PencilLine,
  TriangleAlert,
  type LucideIcon,
} from 'lucide-react';
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
 * How each state is said, and how it is drawn.
 *
 * <p>A `Record<StateName, ...>` rather than a template string built from the name: the mapping is
 * explicit, so a state added to the union without words and a look written for it does not
 * compile.
 *
 * <p>The two dashed ones are dashed for different reasons. `awaiting` is dashed because it is
 * genuinely unresolved — a verdict has not been reached. `draft` is dashed because it is not
 * published yet. Neither is a failure, and neither should read as one.
 */
const look: Record<StateName, { label: MessageKey; icon: LucideIcon; className: string }> = {
  due: {
    label: 'state.due',
    icon: CalendarClock,
    className: 'border-due-edge bg-due-bg text-due-fg',
  },
  overdue: {
    label: 'state.overdue',
    icon: TriangleAlert,
    className: 'border-overdue-edge bg-overdue-bg text-overdue-fg',
  },
  'in-progress': {
    label: 'state.in-progress',
    icon: CirclePlay,
    className: 'border-progress-edge bg-progress-bg text-progress-fg',
  },
  locked: {
    label: 'state.locked',
    icon: Lock,
    className: 'border-locked-edge bg-locked-bg text-locked-fg',
  },
  awaiting: {
    label: 'state.awaiting',
    icon: Hourglass,
    className: 'border-dashed border-awaiting-edge bg-awaiting-bg text-awaiting-fg',
  },
  passed: {
    label: 'state.passed',
    icon: CircleCheck,
    className: 'border-passed-edge bg-passed-bg text-passed-fg',
  },
  'not-passed': {
    label: 'state.not-passed',
    icon: CircleX,
    className: 'border-failed-edge bg-failed-bg text-failed-fg',
  },
  draft: {
    label: 'state.draft',
    icon: PencilLine,
    className: 'border-dashed border-draft-edge bg-draft-bg text-draft-fg',
  },
  published: {
    label: 'state.published',
    icon: Globe,
    className: 'border-published-edge bg-published-bg text-published-fg',
  },
};

/**
 * @param detail appended to the label — the date an obligation passed, how long ago, the version
 *   number. `overdue` should always carry one: "overdue" without a date is a scolding rather than
 *   information.
 */
export function StateChip({ state, detail }: { state: StateName; detail?: string | undefined }) {
  const t = useT();
  const { label, icon: Icon, className } = look[state];
  return (
    // `data-state` rather than a class name is what a test asserts on: the styling is Tailwind
    // utilities that will be edited, and the state this chip claims to be is the thing that must
    // not change by accident.
    <span
      data-state={state}
      className={`inline-flex items-center gap-1.5 rounded-full border px-2.5 py-1 text-xs font-semibold whitespace-nowrap ${className}`}
    >
      <Icon aria-hidden="true" className="size-3.5 shrink-0" strokeWidth={2.25} />
      {/* Label and detail in ONE element: they read as one phrase, and a screen reader should not
          pause between "overdue" and the date that makes it mean something. */}
      <span>
        {t(label)}
        {detail ? ` · ${detail}` : ''}
      </span>
    </span>
  );
}

/**
 * A filled rule, never a ring.
 *
 * <p>`percent` is the server's record of what has been watched, not the browser's idea of where
 * the video is — the two disagree whenever somebody seeks, and only one of them decides whether a
 * gate opens.
 *
 * <p>`label` is a whole sentence naming what is being measured, because "62%" announced on its own
 * tells somebody using a screen reader the number and not the thing.
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
  // Clamped rather than trusted. A percentage past the end would otherwise draw outside its track,
  // and `aria-valuenow` above `aria-valuemax` is a lie told to the accessibility tree.
  const clamped = Math.max(0, Math.min(100, Math.round(percent)));
  return (
    <span
      role="progressbar"
      aria-valuenow={clamped}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-label={label}
      className={`block w-full overflow-hidden rounded-full bg-surface-sunken ${dense ? 'h-1' : 'h-2'}`}
    >
      <span
        className="block h-full rounded-full bg-brand transition-[width] duration-500 ease-out"
        style={{ width: `${clamped}%` }}
      />
    </span>
  );
}
