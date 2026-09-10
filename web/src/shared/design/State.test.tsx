import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { expectNoAxeViolations } from '../../test/axe.ts';
import { en } from '../i18n/messages.en.ts';
import { Progress, StateChip, type StateName } from './State.tsx';

/**
 * The semantic states, asserted for the two properties that make them a system rather than nine
 * class names (docs/design-prompt.md).
 *
 * <p>An accessibility violation fails this build, and it is checked here for the same reason it is
 * checked on the shared states: these are the pieces every screen is built from, so a violation
 * here is inherited by every screen written afterwards.
 */
describe('the semantic states', () => {
  const all: StateName[] = [
    'due',
    'overdue',
    'in-progress',
    'locked',
    'awaiting',
    'passed',
    'not-passed',
    'draft',
    'published',
  ];

  it('names every state in words, so a screenshot in a support ticket is still readable', async () => {
    const { container } = render(
      <>
        {all.map((state) => (
          <StateChip key={state} state={state} />
        ))}
      </>,
    );

    // Colour alone is not a state here. Each chip says what it is, and the CSS adds a border style
    // on top of the hue -- neither of which a test in jsdom can see, which is exactly why the word
    // is the thing asserted.
    expect(screen.getByText(en['state.awaiting'])).toBeVisible();
    expect(screen.getByText(en['state.not-passed'])).toBeVisible();
    expect(screen.getByText(en['state.locked'])).toBeVisible();
    await expectNoAxeViolations(container);
  });

  it('never renders awaiting grading as a failure', () => {
    render(<StateChip state="awaiting" />);

    // The bug this guards is cheap to introduce and expensive to find: `passed: null` drawn as a
    // fail locks a learner out of a course they may well have passed, and nothing reports a fault.
    // So the word "failed" must not appear, and neither must the not-passed styling.
    // Asserted on `data-state` rather than on a class name: the tint is Tailwind utilities that
    // will be edited, and what must not change by accident is which state the chip CLAIMS to be.
    const chip = screen.getByText(en['state.awaiting']).closest('[data-state]');
    expect(chip).toHaveAttribute('data-state', 'awaiting');
    expect(chip?.textContent).not.toMatch(/fail/i);
  });

  it('carries its detail, because "overdue" with no date is a scolding', () => {
    render(<StateChip state="overdue" detail="3 days" />);

    expect(screen.getByText(`${en['state.overdue']} · 3 days`)).toBeVisible();
  });

  it('reports progress to the accessibility tree, not only to the eye', () => {
    render(<Progress percent={62} label="Data Protection 2026, 62 per cent complete" />);

    const bar = screen.getByRole('progressbar', {
      name: 'Data Protection 2026, 62 per cent complete',
    });
    expect(bar).toHaveAttribute('aria-valuenow', '62');
  });

  it('clamps a percentage the server should never have sent, rather than drawing past the end', () => {
    render(<Progress percent={140} label="Over" />);

    expect(screen.getByRole('progressbar', { name: 'Over' })).toHaveAttribute(
      'aria-valuenow',
      '100',
    );
  });
});
