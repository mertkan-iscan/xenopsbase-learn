import { render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { expectNoAxeViolations } from '../test/axe.ts';
import { Shell } from './Shell.tsx';

/**
 * The frame, checked for the structural half of accessibility (T-10.1) — the half a component
 * inside the page cannot fix later: a main landmark, a labelled navigation, and a skip link that
 * comes first in the tab order.
 */
describe('the application shell', () => {
  function signedIn(answer: boolean) {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({ signedIn: answer, name: answer ? 'ayse' : null, signInUrl: '/in' }),
          { headers: { 'content-type': 'application/json' } },
        ),
      ),
    );
  }

  beforeEach(() => signedIn(true));

  afterEach(() => vi.unstubAllGlobals());

  function renderShell() {
    const router = createMemoryRouter(
      [{ path: '/', Component: Shell, children: [{ index: true, element: <h1>A screen</h1> }] }],
      { initialEntries: ['/'] },
    );
    return render(<RouterProvider router={router} />);
  }

  async function renderShellAndWait() {
    const rendered = renderShell();
    // The session is asked for on mount, and until it answers the shell shows neither the screen
    // nor a sign-in prompt -- flashing "you are signed out" at somebody who is signed in is worse
    // than a moment of nothing.
    await screen.findByRole('navigation', { name: 'Main' });
    return rendered;
  }

  it('has a main landmark and a labelled navigation', async () => {
    const { container } = await renderShellAndWait();

    expect(screen.getByRole('main')).toBeInTheDocument();
    expect(screen.getByRole('navigation', { name: 'Main' })).toBeInTheDocument();
    await expectNoAxeViolations(container);
  });

  it('offers the skip link before the navigation, which is the only place it helps', async () => {
    await renderShellAndWait();

    const skip = screen.getByRole('link', { name: 'Skip to content' });
    const firstNavLink = screen.getByRole('link', { name: 'My learning' });

    // Node.compareDocumentPosition: FOLLOWING means the nav link comes after the skip link, which
    // is what makes the skip link usable by somebody tabbing through.
    expect(skip.compareDocumentPosition(firstNavLink) & Node.DOCUMENT_POSITION_FOLLOWING)
      .toBeTruthy();
    expect(skip.getAttribute('href')).toBe('#main');
  });

  it('shows the screen and a way out once somebody is signed in (T-10.2)', async () => {
    renderShell();

    expect(await screen.findByText('A screen')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument();
  });

  it('says so plainly when nobody is, rather than a screen of failed calls', async () => {
    signedIn(false);
    const { container } = renderShell();

    expect(await screen.findByRole('heading', { name: 'You are signed out' })).toBeInTheDocument();
    expect(screen.queryByText('A screen')).not.toBeInTheDocument();
    // And the promise that makes the exam case survivable is on the screen, not only in the code.
    expect(screen.getByText(/still here when you come back/)).toBeInTheDocument();
    await expectNoAxeViolations(container);
  });
});
