import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'react-router';
import { assessment, failureFrom, type ApiFailure } from '../shared/api/client.ts';
import type { components } from '../shared/api/assessment.d.ts';
import { formatMoment } from '../shared/i18n/format.ts';
import { useLocale } from '../shared/i18n/useLocale.ts';
import { ErrorState, Loading } from '../shared/state/States.tsx';
import {
  AwaitingGradingReview,
  ScoreOnlyReview,
  type MarkedItem,
  type Outcome,
} from './Review.tsx';

type Review = components['schemas']['Review'];
type ReviewedItem = components['schemas']['ReviewedItem'];

/**
 * The review, chosen from the policy the response carries (T-6.9).
 *
 * <p>THE POLICY PICKS THE COMPONENT, not a set of flags inside one. `visibility` and `grading`
 * come back on the response because the backend constructs the view from them — see
 * `docs/review.md` on why the answer key is removed from a deep copy rather than filtered out of
 * a shared one. This function is the same decision on this side: one branch per shape, so a shape
 * cannot leak part of another.
 *
 * <p>An unmarked attempt reviews as awaiting whatever its visibility says, because there is
 * nothing to show yet and a score-shaped screen with no score in it is the `passed: null` bug in a
 * different costume.
 *
 * <p><b>WHAT THIS FILE USED TO INVENT.</b> Four values were made up here and passed on as though
 * the server had sent them: a pass mark of `70`, an attempts allowance equal to the attempt
 * NUMBER, a test title of "Your test", and "a moment ago" for a missing timestamp. The `Review`
 * response carries none of the first three (docs/api-surface.md), so a learner was reading this
 * course's pass mark off a literal in a component. They are gone: the two unknowable numbers are
 * simply not shown, and the two sentences come from the catalogue.
 */
type Screen =
  | { status: 'loading' }
  | { status: 'ready'; review: Review }
  | { status: 'failed'; failure: ApiFailure };

/**
 * The verdict on one item, as data.
 *
 * <p>`correct` absent with no award is an item a person still holds: essay and file-upload have no
 * answer key at all, so the machine could not have decided it and did not. Returning `null` here
 * is what makes the row say "with a marker" instead of implying a zero.
 */
function outcomeOf(item: ReviewedItem): Outcome | null {
  if (item.correct === undefined && item.awarded === undefined) {
    return null;
  }
  if (item.correct === true) {
    return { kind: 'correct' };
  }
  if (item.awarded !== undefined && item.points !== undefined) {
    return { kind: 'scored', awarded: item.awarded, points: item.points };
  }
  return { kind: 'not-correct' };
}

export function ReviewScreen() {
  const { attemptId } = useParams();
  const { locale, t } = useLocale();
  const [screen, setScreen] = useState<Screen>({ status: 'loading' });

  const load = useCallback(() => {
    if (!attemptId) {
      return;
    }
    assessment
      .GET('/api/v1/me/attempts/{id}/review', { params: { path: { id: attemptId } } })
      .then(({ data, response, error }) => {
        setScreen(
          data
            ? { status: 'ready', review: data }
            : { status: 'failed', failure: failureFrom(response, error) },
        );
      })
      .catch((unreachable: unknown) => {
        setScreen({ status: 'failed', failure: failureFrom(undefined, unreachable) });
      });
  }, [attemptId]);

  useEffect(() => {
    load();
  }, [load]);

  if (screen.status === 'loading') {
    return <Loading what="loading.result" />;
  }
  if (screen.status === 'failed') {
    return <ErrorState message={screen.failure.message} retry={load} />;
  }

  const { review } = screen;
  const items: MarkedItem[] = (review.items ?? []).map((item, index) => ({
    id: item.formItemId ?? String(index),
    // "Q3", assembled from a number the response carries. `position` absent is a real gap in the
    // payload rather than a question numbered "?", so it falls back to the row's own place.
    label: t('review.question-number', { n: item.position ?? index + 1 }),
    outcome: outcomeOf(item),
  }));

  // There is no test title anywhere on this response and no learner-facing endpoint that would
  // give one for a `testId`. A translated phrase, not an English literal.
  const title = t('review.your-test');

  if (review.grading === 'AWAITING_GRADING') {
    return <AwaitingGradingReview testTitle={title} items={items} />;
  }

  return (
    <ScoreOnlyReview
      testTitle={title}
      percent={review.scorePercent ?? 0}
      // `passed` is optional on the response and `=== true` is deliberate: `undefined` is not a
      // fail, and `!review.passed` would draw one.
      passed={review.passed === true}
      // `formatMoment` and not `toLocaleString()`. The latter formats in the BROWSER's language,
      // which is the wrong one the moment somebody reads in a language their browser is not set
      // to -- Turkish sentences with an English date inside them read as a fault, not a setting.
      submittedAt={review.submittedAt ? formatMoment(locale, review.submittedAt) : t('review.just-now')}
      attemptsUsed={review.attemptNumber ?? 1}
    />
  );
}
