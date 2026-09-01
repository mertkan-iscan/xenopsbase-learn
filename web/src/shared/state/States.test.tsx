import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { expectNoAxeViolations } from '../../test/axe.ts';
import { Empty, ErrorState, Loading } from './States.tsx';

/**
 * The three shared states, asserted for the two things that are easy to get wrong and invisible
 * when you do: whether they are announced, and whether they are accessible at all (T-10.1).
 *
 * <p>An accessibility violation fails this build. That is the point of putting the check on the
 * components everything else is built from rather than on the screens: a violation here would be
 * inherited by every screen written afterwards.
 */
describe('shared states', () => {
  it('announces loading politely rather than leaving the page silent', async () => {
    const { container } = render(<Loading what="your account" />);

    const status = screen.getByRole('status');
    expect(status).toHaveTextContent('Loading your account');
    expect(status).toHaveAttribute('aria-busy', 'true');
    await expectNoAxeViolations(container);
  });

  it('interrupts for a failure, and offers the retry when there is one', async () => {
    const { container } = render(<ErrorState message="Could not reach the service." retry={() => {}} />);

    // role="alert" rather than status: a failure is not an update, it interrupts.
    expect(screen.getByRole('alert')).toHaveTextContent('Could not reach the service.');
    expect(screen.getByRole('button', { name: 'Try again' })).toBeVisible();
    await expectNoAxeViolations(container);
  });

  it('says what to do next, because an empty state without that is a smaller failure', async () => {
    const { container } = render(
      <Empty title="Nothing is assigned to you yet.">
        <p>Courses appear here once somebody assigns one.</p>
      </Empty>,
    );

    expect(screen.getByText('Nothing is assigned to you yet.')).toBeVisible();
    expect(screen.getByText(/Courses appear here/)).toBeVisible();
    await expectNoAxeViolations(container);
  });
});
