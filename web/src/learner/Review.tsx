import { StateChip } from '../shared/design/State.tsx';
import { Card } from '../shared/design/Surface.tsx';
import { formatNumber, formatPercent } from '../shared/i18n/format.ts';
import { useLocale } from '../shared/i18n/useLocale.ts';

/**
 * What a learner may see after submitting (T-6.9, docs/review.md).
 *
 * <p>THIS IS NOT ONE SCREEN WITH PARTS HIDDEN. The review is *constructed* from the test's policy
 * — two axes, visibility and timing — so `SCORE_ONLY` is a genuinely different composition from
 * `FULL`, not the full one with the questions removed. Building it the other way is how an answer
 * key ends up in a response that merely does not render it.
 *
 * <p>The backend already builds it that way: `withoutTheKeyUnless(...)` removes the key from a
 * deep copy rather than filtering a view. These components are the same shape on this side.
 *
 * <p><b>THE OPTIONAL PROPS ARE OPTIONAL BECAUSE THE API DOES NOT KNOW THEM.</b> `passMark` and
 * `attemptsAllowed` used to be required, and {@link ReviewScreen} satisfied them with `70` and
 * with the attempt number — a pass mark nobody had asked the server for, printed as though it were
 * this test's rule. The `Review` response carries `scorePercent`, `passed`, `attemptNumber` and
 * `submittedAt`, and no pass mark and no allowance (docs/api-surface.md). So they are optional
 * here, and each one has a sentence for the case where it is absent rather than a default that
 * reads like a fact.
 */

/**
 * A verdict on one item, as data rather than as a sentence.
 *
 * <p>It was a `string`, built in {@link ReviewScreen} as `'Correct'`, `'Not correct'` and
 * `` `${awarded} of ${points}` ``. Those are English typed into a component, and the lint rule
 * that forbids that could not see them because they were returned from a function rather than
 * written between tags. Turkish also reorders the last one — "{points} üzerinden {awarded}" — so
 * there is no arrangement of the English pieces that produces it.
 */
export type Outcome =
  | { kind: 'correct' }
  | { kind: 'not-correct' }
  | { kind: 'scored'; awarded: number; points: number };

export type MarkedItem = {
  id: string;
  label: string;
  /** The verdict. `null` where a person still has it — never "0" and never "incorrect". */
  outcome: Outcome | null;
};

export function ScoreOnlyReview({
  testTitle,
  percent,
  passMark,
  passed,
  submittedAt,
  attemptsUsed,
  attemptsAllowed,
}: {
  testTitle: string;
  percent: number;
  /** Absent unless something actually knows this test's rule. */
  passMark?: number | undefined;
  passed: boolean;
  submittedAt: string;
  attemptsUsed: number;
  /** Absent unless something actually knows the allowance. */
  attemptsAllowed?: number | undefined;
}) {
  const { locale, t } = useLocale();
  return (
    <Card className="mx-auto flex max-w-2xl flex-col gap-5 p-5 sm:p-6">
      <section aria-labelledby="review-title" className="flex flex-col gap-4">
        <h1 id="review-title" className="label-caps">
          {testTitle}
        </h1>

        <div className="flex flex-col items-start gap-3">
          <StateChip state={passed ? 'passed' : 'not-passed'} />
          <p className="font-display text-5xl font-bold tabular-nums">
            {formatPercent(locale, percent)}
          </p>
          <p className="text-sm text-muted">
            {passMark === undefined
              ? t('review.submitted', { at: submittedAt })
              : t('review.pass-mark', { mark: formatPercent(locale, passMark), at: submittedAt })}
          </p>
        </div>

        {/*
         * SAID AS A POLICY, NOT AS AN APOLOGY. A learner who is shown a score and no questions
         * assumes something failed to load unless the screen tells them otherwise, and support
         * hears about it. "That is this course's policy, not a fault" is the whole message.
         */}
        <p className="text-sm">{t('review.score-only')}</p>

        <div className="flex flex-col gap-1 rounded-lg border border-hairline bg-surface-muted p-4">
          <span className="label-caps">{t('review.attempts')}</span>
          <span className="text-sm">
            {attemptsAllowed === undefined
              ? t('review.attempt-number', { n: formatNumber(locale, attemptsUsed) })
              : t('review.attempts-used', {
                  used: formatNumber(locale, attemptsUsed),
                  allowed: formatNumber(locale, attemptsAllowed),
                })}
            {passed ? ` ${t('review.no-more-needed')}` : ''}
          </span>
        </div>
      </section>
    </Card>
  );
}

export function AwaitingGradingReview({
  testTitle,
  items,
  disclosure,
}: {
  testTitle: string;
  items: MarkedItem[];
  /**
   * The integrity disclosure, as the API produced it. Passed in rather than written here because
   * the list is generated from the same enum the recorder accepts — a signal cannot be collected
   * without appearing in it, and prose in a component stops matching the code the first time
   * somebody adds a kind.
   */
  disclosure?: string;
}) {
  const { t, plural } = useLocale();
  const withAPerson = items.filter((item) => item.outcome === null).length;

  return (
    <Card className="mx-auto flex max-w-2xl flex-col gap-5 p-5 sm:p-6">
      <section aria-labelledby="awaiting-title" className="flex flex-col gap-4">
        <h1 id="awaiting-title" className="label-caps">
          {testTitle}
        </h1>

        <div className="flex flex-col items-start gap-3">
          <StateChip state="awaiting" />
          {/*
           * NOT A SCORE, AND NOT A PLACEHOLDER WHERE A SCORE GOES. There is no number yet, and
           * showing a partial one as though it were the result is how `passed: null` becomes a
           * fail in a learner's head even when the chip above says otherwise.
           */}
          <p className="font-display text-xl font-bold">
            {plural('review.with-a-person', withAPerson)}
          </p>
          <p className="text-sm">{t('review.awaiting-body')}</p>
        </div>

        <ul className="flex flex-col overflow-hidden rounded-lg border border-hairline">
          {items.map((item) => (
            <li
              key={item.id}
              className={[
                'flex items-center justify-between gap-3 border-b border-hairline px-4 py-3 text-sm last:border-b-0',
                // The unresolved rows are dashed and amber-tinted, matching the `awaiting` chip
                // above them. They are NOT greyed out: grey reads as "done with, and negative".
                item.outcome === null ? 'bg-awaiting-bg text-awaiting-fg' : 'bg-surface',
              ].join(' ')}
            >
              <span className="font-semibold">{item.label}</span>
              <span className="text-end tabular-nums">
                {item.outcome ? said(item.outcome, t) : t('review.with-a-marker')}
              </span>
            </li>
          ))}
        </ul>

        <p className="text-xs text-muted">{t('review.marking-time')}</p>
        {disclosure ? <p className="text-xs text-muted">{disclosure}</p> : null}
      </section>
    </Card>
  );
}

/**
 * One verdict, in words.
 *
 * <p>The `scored` case reads "{awarded} of {points}" in English and
 * "{points} üzerinden {awarded}" in Turkish — the two numbers swap places. That is the whole
 * reason this goes through the catalogue as a sentence rather than being assembled with a
 * template literal at the call site.
 */
function said(outcome: Outcome, t: ReturnType<typeof useLocale>['t']): string {
  switch (outcome.kind) {
    case 'correct':
      return t('review.outcome.correct');
    case 'not-correct':
      return t('review.outcome.not-correct');
    case 'scored':
      return t('review.outcome.scored', {
        awarded: outcome.awarded,
        points: outcome.points,
      });
  }
}
