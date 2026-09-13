import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { parkWork } from '../shared/auth/recovery.ts';
import { en } from '../shared/i18n/messages.en.ts';
import { expectNoAxeViolations } from '../test/axe.ts';
import { Login } from './Login.tsx';

/**
 * The sign-in screen (T-10.9).
 *
 * <p>The two things worth asserting here are the ones a screenshot would not catch: that this page
 * never navigates anybody anywhere on its own, and that the appearance controls work while signed
 * out — which is the entire reason they are on this page rather than only in the signed-in header.
 */
describe('the sign-in screen', () => {
  const assign = vi.fn();

  function session(signedIn: boolean) {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({ signedIn, name: signedIn ? 'ayse' : null, signInUrl: '/in' }),
          { headers: { 'content-type': 'application/json' } },
        ),
      ),
    );
  }

  beforeEach(() => {
    window.sessionStorage.clear();
    window.localStorage.clear();
    document.documentElement.removeAttribute('data-theme');
    assign.mockClear();
    Object.defineProperty(window, 'location', {
      configurable: true,
      value: { ...window.location, assign },
    });
    session(false);
  });

  afterEach(() => vi.unstubAllGlobals());

  function renderLogin(state?: unknown) {
    const router = createMemoryRouter([{ path: '/login', Component: Login }], {
      initialEntries: [{ pathname: '/login', state }],
    });
    return render(<RouterProvider router={router} />);
  }

  it('offers sign-in and does not take anybody there on its own', async () => {
    const { container } = renderLogin();

    expect(
      await screen.findByRole('button', { name: en['login.button'] }),
    ).toBeInTheDocument();
    // THE PROPERTY THAT MATTERS. Somebody looking at a page must not be sent away from it: the
    // automatic bounce to the issuer belongs to the shell and to arrival.ts, and a second one
    // here would make the "you are signed out" message unreadable.
    expect(assign).not.toHaveBeenCalled();
    await expectNoAxeViolations(container);
  });

  it('leaves for the issuer only when somebody asks it to', async () => {
    renderLogin();
    await userEvent.click(await screen.findByRole('button', { name: en['login.button'] }));
    expect(assign).toHaveBeenCalledWith('/in');
  });

  it('says why they are here when the shell handed over a reason', async () => {
    renderLogin({ because: 'signed-out' });

    expect(
      await screen.findByRole('heading', { name: en['signed-out.deliberate.title'] }),
    ).toBeInTheDocument();
  });

  it('tells a learner their exam answers are safe, even on a reload', async () => {
    // The forty-minute exam (T-10.2). The shell hands the reason over on the render that
    // redirected; this asserts the page finds it again by itself, because `hasParkedWork` is a
    // pure read and a reload must not lose the sentence.
    parkWork('attempt-submission', { answers: ['a'] }, '/review/an-attempt');
    renderLogin();

    expect(
      await screen.findByRole('heading', { name: en['signed-out.parked.title'] }),
    ).toBeInTheDocument();
  });

  it('lets a signed-out person change the theme before signing in', async () => {
    const { container } = renderLogin();
    await screen.findByRole('button', { name: en['login.button'] });

    await userEvent.click(screen.getByRole('radio', { name: en['prefs.theme.light'] }));

    // The whole argument for putting these controls on the signed-out page: somebody who cannot
    // read the interface cannot be asked to sign in first in order to fix it.
    expect(document.documentElement.getAttribute('data-theme')).toBe('light');
    expect(window.localStorage.getItem('xenops.theme')).toBe('light');
    await expectNoAxeViolations(container);
  });

  it('does not pretend a signed-in person needs to sign in', async () => {
    session(true);
    renderLogin();

    expect(await screen.findByText(en['login.already'])).toBeInTheDocument();
    expect(
      screen.queryByRole('button', { name: en['login.button'] }),
    ).not.toBeInTheDocument();
  });
});
