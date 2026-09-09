import { render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it } from 'vitest';
import { expectNoAxeViolations } from '../test/axe.ts';
import type { components } from '../shared/api/catalog.d.ts';
import { HomeScreen, LockedNext } from './Home.tsx';

type HomeView = components['schemas']['HomeView'];

/**
 * Home, in the states that are hard to arrange against a real service and easy to draw wrongly
 * (T-10.3).
 *
 * <p>{@link HomeScreen} takes a `HomeView` rather than fetching one, which is what makes this
 * possible: every branch on the most-hit screen in the product can be rendered here without a
 * network, including the two nobody would think to check by hand.
 */
function draw(home: HomeView) {
  const router = createMemoryRouter([{ path: '/', element: <HomeScreen home={home} /> }], {
    initialEntries: ['/'],
  });
  return render(<RouterProvider router={router} />);
}

describe('home', () => {
  it('says what is due with the date it passed, not just that it passed', async () => {
    const { container } = draw({
      nextUp: {
        courseTitle: 'Fire Safety Refresher',
        title: '4 items',
        overdue: true,
        dueOn: '2026-09-04',
        nodeId: 'n1',
      },
      summary: { assigned: 4, completed: 1, overdue: 1 },
    });

    // "Overdue" alone is a scolding. The date is what a person can act on.
    //
    // Asserted as "there is a date" rather than as "4 Sep", because the date is formatted in the
    // VIEWER's locale (T-10.8) and pinning the string here would pin the test to whichever locale
    // the machine running it happens to have — passing in CI and failing on a German laptop, for
    // a screen that is behaving correctly in both.
    const chip = screen.getByText((_, element) => element?.className === 'chip chip--overdue');
    expect(chip.textContent).toMatch(/^Overdue · .+/);
    expect(screen.getByRole('link', { name: 'Start' })).toBeVisible();
    await expectNoAxeViolations(container);
  });

  it('offers a resume carrying the second, and the second is the server’s', async () => {
    const { container } = draw({
      nextUp: {
        courseTitle: 'Data Protection 2026',
        title: 'Module 2 · Lawful bases',
        percent: 62,
        resumeSecond: 860,
        nodeId: 'n2',
      },
      summary: { assigned: 4 },
    });

    expect(screen.getByRole('link', { name: 'Resume at 14:20' })).toBeVisible();
    expect(
      screen.getByRole('progressbar', { name: /Data Protection 2026, 62 per cent/ }),
    ).toHaveAttribute('aria-valuenow', '62');
    // Started means "resume", never "start": offering Start to somebody 62% through a video reads
    // as an offer to lose their place.
    expect(screen.queryByRole('link', { name: 'Start' })).not.toBeInTheDocument();
    await expectNoAxeViolations(container);
  });

  it('says nothing is assigned rather than showing an empty frame', async () => {
    const { container } = draw({ summary: {} });

    expect(screen.getByText('Nothing is assigned to you.')).toBeVisible();
    expect(screen.getByText(/Nothing to do today/)).toBeVisible();
    await expectNoAxeViolations(container);
  });

  it('never draws a lock without the reason beside it', async () => {
    const { container } = render(
      <LockedNext
        title="Handling Personal Data — Assessment"
        reason="Unlocks when you finish Data Protection 2026, Module 2."
      />,
    );

    // The reason is a required prop, so this cannot regress into a padlock and nothing -- but the
    // assertion is here because "required prop" and "rendered" are different claims.
    expect(screen.getByText('Locked')).toBeVisible();
    expect(screen.getByText(/Unlocks when you finish/)).toBeVisible();
    await expectNoAxeViolations(container);
  });
});
