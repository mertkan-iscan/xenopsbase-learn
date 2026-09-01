import { render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it } from 'vitest';
import { expectNoAxeViolations } from '../test/axe.ts';
import { Shell } from './Shell.tsx';

/**
 * The frame, checked for the structural half of accessibility (T-10.1) — the half a component
 * inside the page cannot fix later: a main landmark, a labelled navigation, and a skip link that
 * comes first in the tab order.
 */
describe('the application shell', () => {
  function renderShell() {
    const router = createMemoryRouter(
      [{ path: '/', Component: Shell, children: [{ index: true, element: <h1>A screen</h1> }] }],
      { initialEntries: ['/'] },
    );
    return render(<RouterProvider router={router} />);
  }

  it('has a main landmark and a labelled navigation', async () => {
    const { container } = renderShell();

    expect(screen.getByRole('main')).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Main' })).toBeInTheDocument();
    await expectNoAxeViolations(container);
  });

  it('offers the skip link before the navigation, which is the only place it helps', () => {
    renderShell();

    const skip = screen.getByRole('link', { name: 'Skip to content' });
    const firstNavLink = screen.getByRole('link', { name: 'My learning' });

    // Node.compareDocumentPosition: FOLLOWING means the nav link comes after the skip link, which
    // is what makes the skip link usable by somebody tabbing through.
    expect(skip.compareDocumentPosition(firstNavLink) & Node.DOCUMENT_POSITION_FOLLOWING)
      .toBeTruthy();
    expect(skip.getAttribute('href')).toBe('#main');
  });
});
