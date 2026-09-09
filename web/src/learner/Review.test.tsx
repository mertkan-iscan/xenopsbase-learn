import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { expectNoAxeViolations } from '../test/axe.ts';
import { AwaitingGradingReview, ScoreOnlyReview } from './Review.tsx';

/**
 * Review, in two of its policy shapes (T-6.9).
 *
 * <p>The assertions below are about wording rather than layout, and deliberately so. Both screens
 * fail in the same way — by letting a learner read a verdict the API did not give — and the words
 * are where that happens.
 */
describe('review', () => {
  it('explains that a score-only test is a policy, not a fault', async () => {
    const { container } = render(
      <ScoreOnlyReview
        testTitle="Fire Safety Refresher · Test"
        percent={82}
        passMark={70}
        passed
        submittedAt="8 Sep, 14:06"
        attemptsUsed={1}
        attemptsAllowed={2}
      />,
    );

    // Without this sentence a learner assumes the questions failed to load, and support hears
    // about it instead of the policy doing its job.
    expect(screen.getByText(/that is this course’s policy, not a fault/)).toBeVisible();
    expect(screen.getByText('Passed')).toBeVisible();
    await expectNoAxeViolations(container);
  });

  it('shows an unmarked attempt as unresolved, never as a fail and never as a number', async () => {
    const { container } = render(
      <AwaitingGradingReview
        testTitle="Anti-Bribery · Test"
        items={[
          { id: '1', label: 'Q1 · Single choice', outcome: 'Correct' },
          { id: '2', label: 'Q3 · Ordering', outcome: '2 of 3' },
          { id: '3', label: 'Q4 · Written', outcome: null },
          { id: '4', label: 'Q5 · Uploaded file', outcome: null },
        ]}
      />,
    );

    expect(screen.getByText('Awaiting grading')).toBeVisible();
    expect(screen.getByText('2 answers are with a person')).toBeVisible();
    // The sentence that keeps `passed: null` from being read as a verdict.
    expect(screen.getByText(/not a pass, not a fail/)).toBeVisible();
    // And there is no percentage anywhere: a partial score shown as though it were the result is
    // the same bug wearing a different hat.
    expect(container.textContent).not.toMatch(/\d+%/);
    await expectNoAxeViolations(container);
  });

  it('says one answer rather than 1 answers', () => {
    render(
      <AwaitingGradingReview
        testTitle="Anti-Bribery · Test"
        items={[{ id: '1', label: 'Q4 · Written', outcome: null }]}
      />,
    );

    expect(screen.getByText('One answer is with a person')).toBeVisible();
  });

  it('carries the integrity disclosure when there is one, rather than hiding what was recorded', () => {
    render(
      <AwaitingGradingReview
        testTitle="Anti-Bribery · Test"
        items={[{ id: '1', label: 'Q4 · Written', outcome: null }]}
        disclosure="While you sat this test we recorded that the tab lost focus twice."
      />,
    );

    expect(screen.getByText(/the tab lost focus twice/)).toBeVisible();
  });
});
