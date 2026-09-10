import { Lock, Search } from 'lucide-react';
import { useMemo, useState } from 'react';
import { Link } from 'react-router';
import { buttonClasses } from '../shared/design/Button.tsx';
import { FieldGroup, Input } from '../shared/design/Field.tsx';
import { Progress, StateChip } from '../shared/design/State.tsx';
import { Card } from '../shared/design/Surface.tsx';
import { formatDay, formatPercent } from '../shared/i18n/format.ts';
import type { MessageKey } from '../shared/i18n/messages.en.ts';
import { useLocale } from '../shared/i18n/useLocale.ts';
import { Empty, ErrorState, Loading } from '../shared/state/States.tsx';
import { facetsOf, firstResumableNode, nodesOf, type Facet } from './course.ts';
import { refreshHome, useHome, type HomeView } from './useHome.ts';

/**
 * Discover — browsing the training that is assigned to you.
 *
 * <p><b>THIS IS NOT A CATALOGUE, AND THE DIFFERENCE IS THE PRODUCT.</b> The screen this pattern
 * usually names shows every course a platform sells, with a price, a rating and an Enrol button.
 * None of those exist here and none of them can: XenOpsBase Learn is enterprise training that a
 * company ASSIGNS (docs/design-prompt.md), there is no self-enrolment endpoint, no price column, no
 * ratings table, and no instructor field anywhere in the 151 endpoints. Offering a learner a course
 * they cannot enrol in would be a screen that only disappoints.
 *
 * <p>So what this is: search and filter over the courses already assigned to you — which is the
 * question a learner with eleven mandatory courses actually has. It is built on the SAME
 * `/api/v1/me/home` response Home used, filtered in the browser, because the learner-facing API has
 * no course search to call (docs/api-surface.md). That is why moving between Home and here is
 * instant and costs no request: see {@link useHome}.
 *
 * <p>The thumbnail is a monogram rather than an image. There is no image field on a course, and a
 * stock photograph of people in a meeting room is the exact house style this product is designed
 * against — so the tile is the course's own initial, which is honest about being generated.
 */
export function Discover() {
  const state = useHome();

  if (state.status === 'loading') {
    return <Loading what="loading.courses" />;
  }
  if (state.status === 'failed') {
    return <ErrorState message={state.failure.message} retry={() => void refreshHome()} />;
  }
  return <DiscoverScreen home={state.home} />;
}

const filters: { id: Facet | 'all'; label: MessageKey }[] = [
  { id: 'all', label: 'discover.filter.all' },
  { id: 'in-progress', label: 'discover.filter.in-progress' },
  { id: 'due-soon', label: 'discover.filter.due-soon' },
  { id: 'overdue', label: 'discover.filter.overdue' },
  { id: 'complete', label: 'discover.filter.complete' },
  { id: 'locked', label: 'discover.filter.locked' },
];

export function DiscoverScreen({ home }: { home: HomeView }) {
  const { locale, t, plural } = useLocale();
  const [query, setQuery] = useState('');
  const [filter, setFilter] = useState<Facet | 'all'>('all');

  // `home.courses ?? []` written outside the memo is a NEW array every render, so it would make
  // the memo recompute on every one -- which lint catches and which would also defeat the point.
  const courses = useMemo(() => home.courses ?? [], [home.courses]);

  const shown = useMemo(() => {
    // `toLocaleLowerCase` with the reader's locale, not `toLowerCase`. Turkish is one of the two
    // languages here and its dotted/dotless i does not fold the way the invariant rules assume:
    // searching "İş" would not match "iş", which is a search box that looks broken to the person
    // most likely to type it.
    const needle = query.trim().toLocaleLowerCase(locale);
    return courses.filter((course) => {
      const matchesQuery =
        needle === '' || (course.title ?? '').toLocaleLowerCase(locale).includes(needle);
      const matchesFilter = filter === 'all' || facetsOf(course).has(filter);
      return matchesQuery && matchesFilter;
    });
  }, [courses, query, filter, locale]);

  if (courses.length === 0) {
    return (
      <Empty title={t('home.empty.title')}>
        <p>{t('home.empty.body')}</p>
      </Empty>
    );
  }

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-col gap-1">
        <h1 className="font-display text-2xl font-bold sm:text-3xl">{t('discover.title')}</h1>
        {/*
         * `aria-live` so the count is announced when the filter changes. A screen reader user
         * typing into the search box otherwise has no way to know whether it narrowed to nine
         * results or to none -- the grid simply changes underneath them silently.
         */}
        <p aria-live="polite" className="text-sm text-muted">
          {plural('discover.count', shown.length)}
        </p>
      </div>

      <div className="flex flex-col gap-4">
        <div className="relative">
          <Search
            aria-hidden="true"
            className="pointer-events-none absolute start-3 top-1/2 z-10 size-4 -translate-y-1/2 text-subtle"
          />
          <Input
            type="search"
            label={t('discover.search')}
            hideLabel
            placeholder={t('discover.search')}
            hint={t('discover.search.hint')}
            value={query}
            onChange={(event) => setQuery(event.target.value)}
            className="ps-9"
          />
        </div>

        {/*
         * RADIO BUTTONS, NOT BUTTONS. A row of filter chips is one control with six options, and
         * radios in a fieldset are what say so: the group is announced with its question, arrow
         * keys move between the options, and the chosen one is reported as chosen. Six buttons
         * with a class on the active one give a keyboard user six tab stops and no idea which is
         * current.
         */}
        <FieldGroup legend={t('discover.filter')}>
          <div className="scroll-x -mx-1 flex gap-2 px-1 pb-1">
            {filters.map(({ id, label }) => (
              <label
                key={id}
                className={[
                  'inline-flex min-h-9 shrink-0 cursor-pointer items-center rounded-full border px-3.5 text-sm font-semibold whitespace-nowrap transition-colors duration-150',
                  // The ring is drawn on the label because the input itself is `sr-only`; without
                  // this, tabbing to the group would move focus somewhere invisible.
                  'has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-offset-2 has-[:focus-visible]:outline-[var(--focus)]',
                  filter === id
                    ? 'border-brand bg-brand text-brand-on'
                    : 'border-hairline bg-surface text-muted hover:border-hairline-strong hover:text-ink',
                ].join(' ')}
              >
                <input
                  type="radio"
                  name="discover-filter"
                  className="sr-only"
                  checked={filter === id}
                  onChange={() => setFilter(id)}
                />
                {t(label)}
              </label>
            ))}
          </div>
        </FieldGroup>
      </div>

      {shown.length === 0 ? (
        <Empty title={t('discover.empty.title')}>
          <p>{t('discover.empty.body')}</p>
          <button
            type="button"
            onClick={() => {
              setQuery('');
              setFilter('all');
            }}
            className="mt-2 font-semibold text-brand hover:underline"
          >
            {t('discover.clear')}
          </button>
        </Empty>
      ) : (
        <ul className="grid gap-4 sm:grid-cols-2 desk:grid-cols-3">
          {shown.map((course) => {
            const resumable = firstResumableNode(course);
            const modules = course.modules ?? [];
            const locked = modules.some((module) => module.locked);
            const percent = course.percentComplete ?? 0;
            const title = course.title ?? '';
            return (
              <li key={course.courseId}>
                <Card interactive className="flex h-full flex-col gap-4 p-4">
                  <div className="flex items-start gap-3">
                    {/* The monogram. `aria-hidden` because the title is right beside it -- an
                        initial announced before the word it abbreviates is noise. */}
                    <span
                      aria-hidden="true"
                      className="grid size-11 shrink-0 place-items-center rounded-lg bg-brand-tint font-display text-lg font-bold text-brand"
                    >
                      {[...title][0] ?? '·'}
                    </span>
                    <div className="flex min-w-0 flex-col gap-1">
                      <h2 className="text-sm leading-snug font-semibold">{title}</h2>
                      <p className="text-xs text-muted">
                        {plural('discover.modules', modules.length)}
                        {/* The node count is the honest substitute for "12 lessons · 4h 30m": the
                            duration of a course is not a field this API has. */}
                        {' · '}
                        {formatPercent(locale, percent)}
                      </p>
                    </div>
                  </div>

                  {!course.completed ? (
                    <Progress
                      percent={percent}
                      label={t('home.progress-label', {
                        course: title || t('home.this-course'),
                        percent,
                      })}
                      dense
                    />
                  ) : null}

                  <div className="flex flex-wrap items-center gap-2">
                    {course.completed ? <StateChip state="passed" /> : null}
                    {!course.completed && course.overdue ? (
                      <StateChip state="overdue" detail={dueDay(locale, course.dueOn)} />
                    ) : null}
                    {!course.completed && !course.overdue && course.dueOn ? (
                      <StateChip state="due" detail={dueDay(locale, course.dueOn)} />
                    ) : null}
                    {!course.completed && percent > 0 ? <StateChip state="in-progress" /> : null}
                  </div>

                  {/* `mt-auto` so every button in the grid sits on the same line however long the
                      titles above them are. */}
                  <div className="mt-auto flex items-center justify-between gap-2">
                    {resumable ? (
                      <Link
                        to={`/watch/${resumable}`}
                        className={`${buttonClasses(percent > 0 ? 'primary' : 'secondary', 'sm')} relative`}
                      >
                        {percent > 0 ? t('home.resume') : t('discover.open')}
                      </Link>
                    ) : (
                      // Nothing open: every node finished, or every one behind a gate. Said in
                      // words rather than shown as a disabled button nobody can explain.
                      <span className="text-xs text-muted">
                        {course.completed ? '' : t('discover.nothing-open')}
                      </span>
                    )}
                    {locked ? (
                      <span
                        title={t('discover.filter.locked')}
                        className="flex items-center gap-1 text-xs text-locked-fg"
                      >
                        <Lock aria-hidden="true" className="size-3.5" />
                        <span className="sr-only">{t('discover.filter.locked')}</span>
                        {nodesOf(course).filter((node) => node.lockedReason).length || ''}
                      </span>
                    ) : null}
                  </div>
                </Card>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

function dueDay(locale: Parameters<typeof formatDay>[0], dueOn: string | undefined) {
  return dueOn ? formatDay(locale, dueOn) : undefined;
}
