import { StateChip } from '../shared/design/State.tsx';
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
 */

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
  passMark: number;
  passed: boolean;
  submittedAt: string;
  attemptsUsed: number;
  attemptsAllowed: number;
}) {
  const { locale, t } = useLocale();
  return (
    <section className="review panel" aria-labelledby="review-title">
      <h1 id="review-title" className="u-caps review__head">
        {testTitle}
      </h1>
      <div className="review__body">
        <StateChip state={passed ? 'passed' : 'not-passed'} />
        <p className="review__score u-display">{formatPercent(locale, percent)}</p>
        <p>
          {t('review.pass-mark', { mark: formatPercent(locale, passMark), at: submittedAt })}
        </p>
        {/*
         * SAID AS A POLICY, NOT AS AN APOLOGY. A learner who is shown a score and no questions
         * assumes something failed to load unless the screen tells them otherwise, and support
         * hears about it. "That is this course's policy, not a fault" is the whole message.
         */}
        <p className="review__policy">{t('review.score-only')}</p>
        <div className="panel review__attempts">
          <span className="u-caps">{t('review.attempts')}</span>
          <span>
            {t('review.attempts-used', {
              used: formatNumber(locale, attemptsUsed),
              allowed: formatNumber(locale, attemptsAllowed),
            })}
            {passed ? ` ${t('review.no-more-needed')}` : ''}
          </span>
        </div>
      </div>
    </section>
  );
}

export type MarkedItem = {
  id: string;
  label: string;
  /** The verdict as words. `null` where a person still has it — never "0" and never "incorrect". */
  outcome: string | null;
};

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
    <section className="review panel" aria-labelledby="awaiting-title">
      <h1 id="awaiting-title" className="u-caps review__head">
        {testTitle}
      </h1>
      <div className="review__body">
        <StateChip state="awaiting" />
        {/*
         * NOT A SCORE, AND NOT A PLACEHOLDER WHERE A SCORE GOES. There is no number yet, and
         * showing a partial one as though it were the result is how `passed: null` becomes a fail
         * in a learner's head even when the chip above says otherwise.
         */}
        <p className="u-display review__pending">
          {plural('review.with-a-person', withAPerson)}
        </p>
        <p>{t('review.awaiting-body')}</p>
        <ul className="review__items panel">
          {items.map((item) => (
            <li
              key={item.id}
              className={item.outcome === null ? 'review__item review__item--pending' : 'review__item'}
            >
              <span>{item.label}</span>
              <span className="review__outcome">
                {item.outcome ?? t('review.with-a-marker')}
              </span>
            </li>
          ))}
        </ul>
        <p className="u-meta">{t('review.marking-time')}</p>
        {disclosure ? <p className="u-meta review__disclosure">{disclosure}</p> : null}
      </div>
    </section>
  );
}
