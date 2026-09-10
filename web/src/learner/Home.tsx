import { ArrowRight, Lock, Play } from 'lucide-react';
import { Link } from 'react-router';
import { useShellContext } from '../app/shellContext.ts';
import { buttonClasses } from '../shared/design/Button.tsx';
import { Progress, StateChip } from '../shared/design/State.tsx';
import { Card, Section, StatTile } from '../shared/design/Surface.tsx';
import { formatDay, formatPercent, formatPosition } from '../shared/i18n/format.ts';
import type { Locale } from '../shared/i18n/locales.ts';
import { useLocale } from '../shared/i18n/useLocale.ts';
import { Empty, ErrorState, Loading } from '../shared/state/States.tsx';
import { firstResumableNode, HOME_ALL_DONE, NODE_LOCKED } from './course.ts';
import { refreshHome, useHome, type HomeView } from './useHome.ts';

/**
 * Home — the screen the product is judged on (T-10.3, T-5.8).
 *
 * <p>It answers three questions and nothing else: what is due, what is in progress, what is next.
 * It is the most-hit authenticated screen in the product, and it is built on ONE endpoint — the
 * design was written against that constraint, so nothing on it needs a second call.
 *
 * <p><b>WHAT IS NOT ON IT, AND WHY.</b> A dashboard of this kind usually opens with hours learned,
 * a streak, and a certificate count. None of those exist: the learner-facing API is a closed set of
 * sixteen `/me/` paths and not one of them aggregates watch time (docs/api-surface.md). The five
 * tiles below are `summary`, exactly as the server sends it. Inventing a sixth would mean a number
 * on a compliance screen that no system can reproduce, which is worse than an absent one.
 *
 * <p>Split in two on purpose. {@link Home} owns the three states; {@link HomeScreen} is given a
 * `HomeView` and draws it. That is what lets every state on this screen — overdue, resumable,
 * locked, awaiting grading, empty — be rendered in a test without a network, which matters more
 * here than anywhere else because most of them are hard to arrange against a real service and all
 * of them are easy to get wrong.
 */
export function Home() {
  const state = useHome();
  const { name } = useShellContext();

  if (state.status === 'loading') {
    return <Loading what="loading.training" />;
  }
  if (state.status === 'failed') {
    return <ErrorState message={state.failure.message} retry={() => void refreshHome()} />;
  }
  return <HomeScreen home={state.home} name={name} />;
}

export function HomeScreen({ home, name }: { home: HomeView; name?: string | null }) {
  const { locale, t } = useLocale();
  const next = home.nextUp;
  const summary = home.summary ?? {};
  const courses = home.courses ?? [];
  /*
   * ASSIGNMENTS THAT ARE NOT WHOLE COURSES, and until now nothing rendered them.
   *
   * `courses` is only the obligations whose reference is a COURSE. A company can equally assign a
   * single module, a single node, or one content item, and those arrive here in `items` instead.
   * Found against the live stack: the learner had `assigned: 2` and `overdue: 1` in the summary,
   * one course in `courses`, and the overdue thing -- a content item called "Fire safety
   * refresher" -- was an `items` entry that appeared on no screen at all. A compliance product
   * that counts an overdue obligation in a tile and then shows it nowhere is worse than one that
   * never counted it.
   */
  const items = home.items ?? [];

  // Started or not is what decides which of the two shapes `nextUp` becomes -- the API returns one
  // "next up" and the design shows DUE and IN PROGRESS as different things, which they are: one
  // needs a Start and the other needs a Resume with a second in it.
  const started = (next?.percent ?? 0) > 0 || (next?.resumeSecond ?? 0) > 0;

  // The first locked node anywhere in the assigned courses. One is enough: a learner needs to know
  // what is next and why, not an inventory of everything shut.
  const firstLocked = courses
    .flatMap((course) => course.modules ?? [])
    .flatMap((module) => module.nodes ?? [])
    .find((node) => node.state === NODE_LOCKED);

  // `name` is a display name, which is "Ayşe Demir" -- a greeting wants the first word of it. Split
  // on whitespace rather than taking a `firstName` field, because there isn't one: /api/v1/me
  // carries `displayName` and nothing finer.
  const firstName = name?.trim().split(/\s+/)[0];

  if (!next && courses.length === 0 && items.length === 0) {
    return (
      // Two genuinely different empty states, because the server distinguishes them and a learner
      // certainly does: ALL_DONE means finished, NOTHING_ASSIGNED means nobody has given you
      // anything. Drawing "you are up to date" at somebody with no assignments is a lie, and
      // drawing "nothing is assigned" at somebody who just finished everything erases their work.
      <Empty
        title={t(home.state === HOME_ALL_DONE ? 'home.done.title' : 'home.empty.title')}
      >
        <p>{t(home.state === HOME_ALL_DONE ? 'home.done.body' : 'home.empty.body')}</p>
      </Empty>
    );
  }

  return (
    <div className="flex flex-col gap-8">
      <h1 className="font-display text-2xl font-bold sm:text-3xl">
        {firstName ? t('home.welcome.named', { name: firstName }) : t('home.welcome')}
      </h1>

      {next ? (
        <Section title={t(started ? 'home.continue' : 'home.due')}>
          {/*
           * THE ONE CARD ON THIS SCREEN THAT IS ALLOWED TO BE BIG. It is distinct by elevation and
           * type size rather than by a coloured stripe down its edge -- the house style this
           * product is explicitly designed against (docs/design-prompt.md, "what to avoid").
           */}
          <Card className="flex flex-col gap-4 p-5 shadow-lift sm:p-6">
            <div className="flex flex-wrap items-center gap-2">
              {/*
               * OVERDUE ALWAYS CARRIES ITS DATE. "Overdue" on its own is a scolding; "overdue,
               * was due 4 Sep" is information somebody can act on, and it is the difference
               * between a screen that nags and one that helps.
               */}
              {next.overdue ? <StateChip state="overdue" detail={day(locale, next.dueOn)} /> : null}
              {!next.overdue && next.dueOn ? (
                <StateChip state="due" detail={day(locale, next.dueOn)} />
              ) : null}
              {started ? <StateChip state="in-progress" /> : null}
            </div>

            <div className="flex flex-col gap-1">
              <p className="font-display text-lg font-bold sm:text-xl">
                {next.courseTitle ?? next.title}
              </p>
              {next.title && next.courseTitle ? (
                <p className="text-sm text-muted">{next.title}</p>
              ) : null}
            </div>

            {started ? (
              <div className="flex items-center gap-3">
                <Progress
                  percent={next.percent ?? 0}
                  label={t('home.progress-label', {
                    course: next.courseTitle ?? t('home.this-course'),
                    percent: next.percent ?? 0,
                  })}
                />
                <span className="text-sm font-semibold tabular-nums">
                  {formatPercent(locale, next.percent ?? 0)}
                </span>
              </div>
            ) : null}

            {started && next.resumeSecond ? (
              <p className="text-sm text-muted">
                {t('home.stopped-at', { at: formatPosition(next.resumeSecond) })}
              </p>
            ) : null}

            {/*
             * The second in this label is the SERVER's record, not this tab's. A learner who
             * resumes on their phone after starting on a desktop gets the same number, which is
             * the whole point of not keeping it here.
             */}
            <Link
              className={`${buttonClasses('primary')} w-full sm:w-auto sm:self-start`}
              to={`/watch/${next.nodeId ?? ''}`}
            >
              <Play aria-hidden="true" className="size-4" />
              {started
                ? next.resumeSecond
                  ? t('home.resume-at', { at: formatPosition(next.resumeSecond) })
                  : t('home.resume')
                : t('home.start')}
            </Link>
          </Card>
        </Section>
      ) : null}

      {/*
       * `summary`, and nothing derived from it. Five counts the server computes, so a learner and
       * a compliance report can never disagree about how many things are overdue.
       */}
      <Section title={t('home.at-a-glance')}>
        <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 desk:grid-cols-5">
          <StatTile value={summary.assigned ?? 0} label={t('home.stat.assigned')} />
          <StatTile
            value={summary.inProgress ?? 0}
            label={t('home.stat.in-progress')}
            tone="brand"
          />
          <StatTile value={summary.completed ?? 0} label={t('home.stat.completed')} />
          <StatTile value={summary.dueSoon ?? 0} label={t('home.stat.due-soon')} />
          <StatTile
            value={summary.overdue ?? 0}
            label={t('home.stat.overdue')}
            tone={(summary.overdue ?? 0) > 0 ? 'alert' : 'plain'}
          />
        </div>
      </Section>

      {firstLocked ? (
        <Section title={t('home.next')}>
          {/*
           * `lockedReason` is the SERVER's sentence, and it arrives in the reader's language
           * because the request carried `Accept-Language` (shared/api/client.ts). The fallback
           * beside it is ours, for a gate that could not say why — see T-5.3 on a padlock with no
           * sentence being a support ticket.
           */}
          <LockedNext
            title={firstLocked.title ?? t('home.locked.fallback-title')}
            reason={firstLocked.lockedReason ?? t('home.locked.fallback-reason')}
          />
        </Section>
      ) : null}

      {items.length > 0 ? (
        <Section title={t('home.also-assigned')}>
          <ul className="flex flex-col gap-2">
            {items.map((item) => {
              const percent = item.percent ?? 0;
              /*
               * ONLY A NODE HAS A SCREEN. `referenceType` is MODULE, NODE or CONTENT_ITEM
               * (`HomeService.itemView`), and the router has a route for a node and nothing for
               * the other two -- a content item is not a node, so `/watch/<contentItemId>` would
               * be a link to a player that cannot resolve it. The row is plain text in that case
               * rather than a link that fails.
               */
              const openable = item.referenceType === 'NODE' ? item.referenceId : undefined;
              return (
                <li key={`${item.referenceType ?? ''}-${item.referenceId ?? ''}`}>
                  <Card className="relative flex items-center gap-4 p-4">
                    <div className="flex min-w-0 flex-1 flex-col gap-2">
                      {openable ? (
                        <Link
                          to={`/watch/${openable}`}
                          className="truncate font-semibold after:absolute after:inset-0 hover:text-brand"
                        >
                          {item.title}
                        </Link>
                      ) : (
                        <span className="truncate font-semibold">{item.title}</span>
                      )}
                      {percent > 0 ? (
                        <div className="flex items-center gap-2">
                          <Progress
                            percent={percent}
                            label={t('home.progress-label', {
                              course: item.title ?? t('home.this-course'),
                              percent,
                            })}
                            dense
                          />
                          <span className="text-xs text-muted tabular-nums">
                            {formatPercent(locale, percent)}
                          </span>
                        </div>
                      ) : null}
                    </div>
                    <span className="shrink-0">
                      {item.overdue ? (
                        <StateChip state="overdue" detail={day(locale, item.dueOn)} />
                      ) : item.dueOn ? (
                        <StateChip state="due" detail={day(locale, item.dueOn)} />
                      ) : null}
                    </span>
                  </Card>
                </li>
              );
            })}
          </ul>
        </Section>
      ) : null}

      {courses.length > 0 ? (
        <Section
          title={t('home.courses')}
          action={
            <Link
              to="/discover"
              className="inline-flex items-center gap-1 text-sm font-semibold text-brand hover:underline"
            >
              {t('home.see-all')}
              <ArrowRight aria-hidden="true" className="size-3.5" />
            </Link>
          }
        >
          <ul className="flex flex-col gap-2">
            {courses.map((course) => {
              const resumable = firstResumableNode(course);
              return (
                <li key={course.courseId}>
                  <Card interactive className="relative flex items-center gap-4 p-4">
                    <div className="flex min-w-0 flex-1 flex-col gap-2">
                      {/*
                       * The whole row is the target, achieved by stretching the link over the card
                       * rather than by putting an onClick on a div: it stays a real link, so it is
                       * announced as one, focusable, and openable in a new tab.
                       */}
                      <Link
                        to={resumable ? `/watch/${resumable}` : '/discover'}
                        className="truncate font-semibold after:absolute after:inset-0 hover:text-brand"
                      >
                        {course.title}
                      </Link>
                      {!course.completed ? (
                        <div className="flex items-center gap-2">
                          <Progress
                            percent={course.percentComplete ?? 0}
                            label={t('home.progress-label', {
                              course: course.title ?? t('home.this-course'),
                              percent: course.percentComplete ?? 0,
                            })}
                            dense
                          />
                          <span className="text-xs text-muted tabular-nums">
                            {formatPercent(locale, course.percentComplete ?? 0)}
                          </span>
                        </div>
                      ) : null}
                    </div>
                    <span className="shrink-0">
                      {course.completed ? (
                        <StateChip state="passed" detail={t('home.complete')} />
                      ) : course.overdue ? (
                        <StateChip state="overdue" detail={day(locale, course.dueOn)} />
                      ) : course.dueOn ? (
                        <StateChip state="due" detail={day(locale, course.dueOn)} />
                      ) : null}
                    </span>
                  </Card>
                </li>
              );
            })}
          </ul>
        </Section>
      ) : null}
    </div>
  );
}

/**
 * A due date, short.
 *
 * <p>The locale is passed in rather than left to the browser. This used to be
 * `toLocaleDateString(undefined, …)`, and `undefined` there means the BROWSER's language — which
 * is the wrong one the moment somebody reads in a language their browser is not set to. It
 * produces one specific half-translated page: Turkish sentences with English month names inside
 * them, which reads as a fault rather than as a setting.
 */
function day(locale: Locale, dueOn: string | undefined) {
  return dueOn ? formatDay(locale, dueOn) : undefined;
}

/**
 * A locked node and the sentence a learner reads instead of a padlock (T-5.3).
 *
 * <p>The reason is a REQUIRED prop, from the first day rather than added later, which is how a
 * lock ends up drawn without one.
 */
export function LockedNext({ title, reason }: { title: string; reason: string }) {
  return (
    <div className="flex gap-4 rounded-xl border border-dashed border-locked-edge bg-locked-bg p-5">
      <Lock aria-hidden="true" className="mt-0.5 size-5 shrink-0 text-locked-fg" />
      <div className="flex flex-col gap-2">
        <StateChip state="locked" />
        <p className="font-display font-semibold">{title}</p>
        <p className="text-sm text-muted">{reason}</p>
      </div>
    </div>
  );
}
