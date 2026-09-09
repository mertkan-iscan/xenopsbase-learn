import { useCallback, useEffect, useState } from 'react';
import { useParams } from 'react-router';
import { assessment, failureFrom, type ApiFailure } from '../shared/api/client.ts';
import type { components } from '../shared/api/assessment.d.ts';
import { ErrorState, Loading } from '../shared/state/States.tsx';
import { AwaitingGradingReview, ScoreOnlyReview, type MarkedItem } from './Review.tsx';

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
 */
type Screen =
  | { status: 'loading' }
  | { status: 'ready'; review: Review }
  | { status: 'failed'; failure: ApiFailure };

/** Human wording for one marked item, and the one case that must not be a number. */
function outcomeOf(item: ReviewedItem): string | null {
  // `correct` absent with no award is an item a person still holds: essay and file-upload have no
  // answer key at all, so the machine could not have decided it and did not. Returning null here
  // is what makes the row say "with a marker" instead of implying a zero.
  if (item.correct === undefined && item.awarded === undefined) {
    return null;
  }
  if (item.correct === true) {
    return 'Correct';
  }
  if (item.awarded !== undefined && item.points !== undefined) {
    return `${item.awarded} of ${item.points}`;
  }
  return 'Not correct';
}

function label(item: ReviewedItem) {
  return `Q${item.position ?? '?'}`;
}

export function ReviewScreen() {
  const { attemptId } = useParams();
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
    return <Loading what="your result" />;
  }
  if (screen.status === 'failed') {
    return <ErrorState message={screen.failure.message} retry={load} />;
  }

  const { review } = screen;
  const items: MarkedItem[] = (review.items ?? []).map((item, index) => ({
    id: item.formItemId ?? String(index),
    label: label(item),
    outcome: outcomeOf(item),
  }));

  if (review.grading === 'AWAITING_GRADING') {
    return <AwaitingGradingReview testTitle="Your test" items={items} />;
  }

  return (
    <ScoreOnlyReview
      testTitle="Your test"
      percent={review.scorePercent ?? 0}
      passMark={70}
      // `passed` is optional on the response and `=== true` is deliberate: `undefined` is not a
      // fail, and `!review.passed` would draw one.
      passed={review.passed === true}
      submittedAt={
        review.submittedAt ? new Date(review.submittedAt).toLocaleString() : 'a moment ago'
      }
      attemptsUsed={review.attemptNumber ?? 1}
      attemptsAllowed={review.attemptNumber ?? 1}
    />
  );
}
