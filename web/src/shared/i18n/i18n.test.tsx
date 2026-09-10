import { render, screen } from '@testing-library/react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { afterEach, describe, expect, it } from 'vitest';
import { HomeScreen } from '../../learner/Home.tsx';
import { formatDay, formatPercent, formatWaited } from './format.ts';
import { forceLocale } from './locale.ts';
import { LOCALES, type Locale } from './locales.ts';
import { en, type MessageKey } from './messages.en.ts';
import { tr } from './messages.tr.ts';
import { plural, translate } from './t.ts';

/**
 * The catalogue, and the one screen rendered in the other language.
 *
 * <p>What the type system already guarantees is not asserted here: `tr` is declared
 * `Record<MessageKey, string>`, so a MISSING Turkish sentence does not compile and a test for it
 * would be testing TypeScript. What it cannot see is a sentence that is present and empty, a
 * placeholder that was dropped in translation, and whether a screen actually speaks Turkish when
 * it is asked to — which is what is below.
 */
describe('the catalogue', () => {
  const keys = Object.keys(en) as MessageKey[];

  it.each(LOCALES)('has nothing blank in %s', (locale: Locale) => {
    const blank = keys.filter((key) => translate(locale, key).trim() === '');
    expect(blank).toEqual([]);
  });

  it('keeps every placeholder a sentence was given', () => {
    // A `{name}` dropped in translation is the failure this catches, and it is invisible in
    // review: the Turkish reads perfectly and simply never says the course title. An EXTRA one is
    // just as bad -- `fill` leaves an unmatched placeholder visible on purpose, so a typo like
    // `{cours}` would ship as literal braces on the screen.
    //
    // `{count}` IS EXEMPT, and finding out why is what this test was for. English's singular is
    // "One answer is with a person" -- the number is spelled as a word and there is no placeholder
    // at all -- while Turkish needs "{count} cevap...", because it has no separate singular to
    // spell out. Both are right. `plural` always supplies `count`, so a language may take it or
    // leave it, and only the OTHER placeholders have to match.
    const wrong = keys
      .map((key) => ({ key, en: holes(en[key]), tr: holes(tr[key]) }))
      .filter((row) => row.en.join() !== row.tr.join());

    expect(wrong).toEqual([]);
  });

  it('has a Turkish sentence for everything, and it is not the English one', () => {
    // Not every entry can differ: "Video", "SCORM", "uuid" and `resource:action` are the same
    // string in both languages on purpose (each is commented where it is defined). The assertion
    // is that the OVERWHELMING majority differ -- a Turkish catalogue that was 90% English would
    // be one somebody pasted and never finished, and that is the failure worth catching.
    const identical = keys.filter((key) => en[key] === tr[key]);

    expect(identical.length / keys.length).toBeLessThan(0.1);
  });
});

describe('a screen in Turkish', () => {
  afterEach(() => forceLocale('en'));

  it('renders Home without a word of English left in it', () => {
    forceLocale('tr');
    // Through a router, because the screen's Start and Resume are `<Link>`s. Same shape as
    // Home.test.tsx's `draw`, kept local rather than exported: that file is about the screen's
    // states and this one is about its language.
    const router = createMemoryRouter(
      [
        {
          path: '/',
          element: (
            <HomeScreen
              home={{
                nextUp: {
                  courseTitle: 'Veri Koruma 2026',
                  title: 'Modül 2',
                  percent: 62,
                  nodeId: 'n1',
                },
                summary: { assigned: 3, completed: 1, overdue: 1 },
                courses: [],
              }}
            />
          ),
        },
      ],
      { initialEntries: ['/'] },
    );
    render(<RouterProvider router={router} />);

    expect(screen.getByRole('heading', { name: tr['home.welcome'] })).toBeVisible();
    expect(screen.getByRole('heading', { name: tr['home.continue'] })).toBeVisible();

    // The headings a reader would notice first, asserted as ABSENT. Rendering the Turkish is only
    // half the claim; the other half is that no English survived beside it, which is what a
    // half-extracted screen looks like.
    expect(screen.queryByText(en['home.welcome'])).not.toBeInTheDocument();
    expect(screen.queryByText(en['home.continue'])).not.toBeInTheDocument();
    expect(screen.queryByText(en['home.at-a-glance'])).not.toBeInTheDocument();
  });

  it('puts the percent sign where Turkish puts it', () => {
    // The smallest thing on the screen and the one most likely to be typed by hand: English writes
    // "62%" and Turkish writes "%62". If this ever reads "62%" in Turkish, somebody has replaced a
    // formatter with a template string.
    expect(formatPercent('en', 62)).toBe('62%');
    expect(formatPercent('tr', 62)).toBe('%62');
  });

  it('formats dates and durations in the chosen language, not the browser’s', () => {
    // `toLocaleDateString(undefined, …)` -- what these replaced -- reads the BROWSER's language.
    // Under this test runner that is English, so a regression to `undefined` would still pass an
    // English assertion and fail this one.
    expect(formatDay('tr', '2026-09-04T00:00:00Z')).not.toBe(formatDay('en', '2026-09-04T00:00:00Z'));
    expect(formatWaited('tr', 90 * 60)).not.toBe(formatWaited('en', 90 * 60));
  });

  it('does not invent an English plural rule for Turkish', () => {
    // English chooses between two forms; Turkish has one, and `Intl.PluralRules` is what knows
    // that. A hand-written `n === 1` would make these two disagree.
    expect(plural('en', 'review.with-a-person', 1)).not.toBe(plural('en', 'review.with-a-person', 2));
    expect(plural('tr', 'review.with-a-person', 1)).toBe(
      plural('tr', 'review.with-a-person', 2).replace('2', '1'),
    );
  });
});

/** The `{placeholders}` a sentence carries, in order. */
function holes(sentence: string): string[] {
  return [...sentence.matchAll(/\{(\w+)\}/g)]
    .map((match) => match[1] ?? '')
    .filter((name) => name !== 'count')
    .sort();
}
