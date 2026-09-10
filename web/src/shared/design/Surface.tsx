/**
 * The surfaces a screen is composed of: a section, a card, and a stat tile.
 *
 * <p>WHAT A SECTION IS FOR, AND WHY IT IS NOT A CARD. The old system's complaint was that
 * everything was a box inside a box, three deep, and that is a mistake this system can make just
 * as easily with a shadow as the last one made with a border. {@link Section} is the answer: a
 * heading and its content, separated from what is above it by SPACE and by type, on the ground.
 * Reach for {@link Card} when something is a genuinely distinct object — one course, one person,
 * one attempt — and not merely the next thing down the page.
 *
 * <p>Every section renders a real `<section>` with a real heading wired by `aria-labelledby`, so
 * the page has a document outline somebody can navigate by headings rather than a stack of divs.
 */
import { useId, type ReactNode } from 'react';

export function Section({
  title,
  action,
  children,
  className = '',
}: {
  title: string;
  /** A control belonging to the section as a whole — a filter, a "see all", an add button. */
  action?: ReactNode;
  children: ReactNode;
  className?: string;
}) {
  const headingId = useId();
  return (
    <section aria-labelledby={headingId} className={className}>
      <div className="mb-3 flex items-baseline justify-between gap-3">
        <h2 id={headingId} className="text-base font-semibold">
          {title}
        </h2>
        {action}
      </div>
      {children}
    </section>
  );
}

/**
 * A distinct object on the ground.
 *
 * <p>`interactive` adds the hover lift, and it is deliberately opt-in: a card that rises under the
 * pointer is promising that clicking it does something, and a card that promises that and does
 * nothing is worse than a flat one.
 */
export function Card({
  interactive = false,
  className = '',
  children,
}: {
  interactive?: boolean;
  className?: string;
  children: ReactNode;
}) {
  return (
    <div
      className={[
        'card',
        interactive
          ? // Shadow and border only. NOT `-translate-y`: a grid of cards that each lift on hover
            // reflows nothing, but one that lifts under a thumb on a touch screen — where hover is
            // sticky after a tap — leaves the card raised and looking selected.
            'transition-shadow duration-200 hover:border-brand-tint-edge hover:shadow-lift'
          : '',
        className,
      ]
        .filter(Boolean)
        .join(' ')}
    >
      {children}
    </div>
  );
}

/**
 * One number and what it counts.
 *
 * <p>The number is `tabular-nums` so a row of tiles does not jitter when one of them changes from
 * 9 to 10, and the label is a whole phrase rather than a truncated noun — this is the one place in
 * the product where a count with no sentence around it would be read as a score.
 */
export function StatTile({
  value,
  label,
  tone = 'plain',
}: {
  value: number | string;
  label: string;
  /** `alert` is for a count that is bad news — overdue, refused, failed. Used sparingly. */
  tone?: 'plain' | 'brand' | 'alert';
}) {
  const tones = {
    plain: 'text-ink',
    brand: 'text-brand',
    alert: 'text-overdue-fg',
  } as const;
  return (
    <div className="card flex flex-col gap-1 p-4">
      <span className={`font-display text-2xl leading-none font-bold tabular-nums ${tones[tone]}`}>
        {value}
      </span>
      <span className="text-xs leading-snug text-muted">{label}</span>
    </div>
  );
}
