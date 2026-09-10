import { render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { expectNoAxeViolations } from '../test/axe.ts';
import { rememberTheAttempt } from '../shared/auth/arrival.ts';
import { parkWork } from '../shared/auth/recovery.ts';
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

  const assign = vi.fn();

  beforeEach(() => {
    window.sessionStorage.clear();
    assign.mockClear();
    // jsdom's location.assign is not implemented and logs a "Not implemented" error; stubbing it
    // is also what lets a test assert WHERE the shell tried to send somebody.
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: { ...window.location, assign },
    });
    signedIn(true);
  });

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
    // The learner's navigation is the tab bar at the bottom of the screen now (T-10.3). Where it
    // sits visually is a design decision; where it sits in the DOM is this assertion, and the two
    // are allowed to differ only in the direction that keeps the skip link first in the tab order.
    const firstNavLink = screen.getByRole('link', { name: 'Training' });

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

  it('sends a first visitor to the sign-in page rather than a panel about it', async () => {
    signedIn(false);
    renderShell();

    // The front door opens the issuer's login page. A panel here would tell somebody what they
    // already know and make them click the only button on it.
    await vi.waitFor(() => expect(assign).toHaveBeenCalledWith('/in'));
    expect(screen.queryByText('A screen')).not.toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'You are signed out' })).not.toBeInTheDocument();
  });

  it('explains rather than redirecting when a session ended over somebody’s work', async () => {
    // The forty-minute exam (T-10.2). Sending this person to the issuer without a word is the
    // reason the panel exists, and it has to survive the panel becoming conditional.
    parkWork('attempt-submission', { answers: ['a'] }, '/review/an-attempt');
    signedIn(false);
    const { container } = renderShell();

    expect(await screen.findByRole('heading', { name: 'Your work is saved' })).toBeInTheDocument();
    expect(screen.getByText(/Nothing was lost/)).toBeInTheDocument();
    // And it did NOT navigate away from the screen saying so.
    expect(assign).not.toHaveBeenCalled();
    await expectNoAxeViolations(container);
  });

  it('stops after one automatic attempt rather than looping between two hosts', async () => {
    rememberTheAttempt();
    signedIn(false);
    const { container } = renderShell();

    expect(await screen.findByRole('heading', { name: 'You are signed out' })).toBeInTheDocument();
    expect(screen.getByText(/Signing in did not complete/)).toBeInTheDocument();
    expect(assign).not.toHaveBeenCalled();
    await expectNoAxeViolations(container);
  });
});
