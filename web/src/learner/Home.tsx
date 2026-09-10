import { useCallback, useEffect, useState } from 'react';
import { Link } from 'react-router';
import { catalog, failureFrom, type ApiFailure } from '../shared/api/client.ts';
import type { components } from '../shared/api/catalog.d.ts';
import { Progress, StateChip } from '../shared/design/State.tsx';
import { Empty, ErrorState, Loading } from '../shared/state/States.tsx';

type HomeView = components['schemas']['HomeView'];

/**
 * Home — the screen the product is judged on (T-10.3, T-5.8).
 *
 * <p>It answers three questions and nothing else: what is due, what is in progress, what is next.
 * It is the most-hit authenticated screen in the product, and it is built on ONE endpoint — the
 * design was written against that constraint, so nothing on it needs a second call.
 *
 * <p>Split in two on purpose. {@link Home} does the request and owns the three states;
 * {@link HomeScreen} is given a `HomeView` and draws it. That is what lets every state on this
 * screen — overdue, resumable, locked, awaiting grading, empty — be rendered in a test without a
 * network, which matters more here than anywhere else because most of them are hard to arrange
 * against a real service and all of them are easy to get wrong.
 */
type Screen =
  | { status: 'loading' }
  | { status: 'ready'; home: HomeView }
  | { status: 'failed'; failure: ApiFailure };

export function Home() {
  const [screen, setScreen] = useState<Screen>({ status: 'loading' });

  // Nothing here sets state synchronously: the effect starts the request and every transition
  // happens in a callback. That is what keeps the render loop from cascading, and React's lint
  // rule enforces it rather than trusting anybody to remember.
  const load = useCallback(() => {
    catalog
      .GET('/api/v1/me/home')
      .then(({ data, response, error }) => {
        setScreen(
          data
            ? { status: 'ready', home: data }
            : { status: 'failed', failure: failureFrom(response, error) },
        );
      })
      .catch((unreachable: unknown) => {
        setScreen({ status: 'failed', failure: failureFrom(undefined, unreachable) });
      });
  }, []);

  useEffect(() => {
    load();
  }, [load]);

  function retry() {
    setScreen({ status: 'loading' });
    load();
  }

  if (screen.status === 'loading') {
    return <Loading what="your training" />;
  }
  if (screen.status === 'failed') {
    return <ErrorState message={screen.failure.message} retry={retry} />;
  }
  return <HomeScreen home={screen.home} />;
}

function minutes(seconds: number) {
  const whole = Math.floor(seconds / 60);
  const rest = Math.floor(seconds % 60);
  return `${whole}:${String(rest).padStart(2, '0')}`;
}

export function HomeScreen({ home }: { home: HomeView }) {
  const next = home.nextUp;
  const summary = home.summary ?? {};
  const courses = home.courses ?? [];

  // Started or not is what decides which of the two cards `nextUp` becomes -- the API returns one
  // "next up" and the design shows DUE and IN PROGRESS as different things, which they are: one
  // needs a Start and the other needs a Resume with a second in it.
  const started = (next?.percent ?? 0) > 0 || (next?.resumeSecond ?? 0) > 0;

  // The first locked node anywhere in the assigned courses. One is enough: a learner needs to know
  // what is next and why, not an inventory of everything shut.
  const firstLocked = courses
    .flatMap((course) => course.modules ?? [])
    .flatMap((module) => module.nodes ?? [])
    .find((node) => node.state === 'LOCKED');

  if (!next && courses.length === 0) {
    return (
      <Empty title="Nothing is assigned to you.">
        <p className="u-meta">
          When your manager assigns training it appears here. Nothing to do today.
        </p>
      </Empty>
    );
  }

  return (
    <div className="home">
      <h1 className="u-display home__title">Your training</h1>

      {next && !started ? (
        <section className="home__section" aria-labelledby="due">
          <h2 id="due" className="u-caps">
            Due
          </h2>
          {/*
           * OVERDUE ALWAYS CARRIES ITS DATE. "Overdue" on its own is a scolding; "overdue, was due
           * 4 Sep" is information somebody can act on, and it is the difference between a screen
           * that nags and one that helps.
           */}
          {next.overdue ? <StateChip state="overdue" detail={due(next.dueOn)} /> : null}
          {!next.overdue && next.dueOn ? <StateChip state="due" detail={due(next.dueOn)} /> : null}
          <p className="home__course u-display">{next.courseTitle ?? next.title}</p>
          {next.title && next.courseTitle ? <p className="u-meta">{next.title}</p> : null}
          <Link className="btn btn-primary btn-block" to={`/watch/${next.nodeId ?? ''}`}>
            Start
          </Link>
        </section>
      ) : null}

      {next && started ? (
        <section className="home__section" aria-labelledby="in-progress">
          <h2 id="in-progress" className="u-caps">
            In progress
          </h2>
          <p className="home__course u-display">{next.courseTitle ?? next.title}</p>
          <p className="home__progress">
            <Progress
              percent={next.percent ?? 0}
              label={`${next.courseTitle ?? 'This course'}, ${next.percent ?? 0} per cent complete`}
            />
            <span className="home__percent">{next.percent ?? 0}%</span>
          </p>
          <p className="u-meta">
            {next.title}
            {next.resumeSecond ? ` — you stopped at ${minutes(next.resumeSecond)}` : ''}
          </p>
          {/*
           * The second in this label is the SERVER's record, not this tab's. A learner who resumes
           * on their phone after starting on a desktop gets the same number, which is the whole
           * point of not keeping it here.
           */}
          <Link className="btn btn-secondary btn-block" to={`/watch/${next.nodeId ?? ''}`}>
            {next.resumeSecond ? `Resume at ${minutes(next.resumeSecond)}` : 'Resume'}
          </Link>
        </section>
      ) : null}

      {/*
       * NEXT — the locked node and the gate's own sentence.
       *
       * This used to say the endpoint did not carry it. It always did: `HomeNode.lockedReason` is
       * right there, and the generated client could not see it because four records collided by
       * name during spec generation and the home shapes lost. The comment that was here is a fair
       * record of how convincing a wrong type is.
       */}
      {firstLocked ? (
        <section className="home__section" aria-labelledby="next-up">
          <h2 id="next-up" className="u-caps">
            Next
          </h2>
          <LockedNext
            title={firstLocked.title ?? 'The next item'}
            reason={firstLocked.lockedReason ?? 'It unlocks when the item before it is finished.'}
          />
        </section>
      ) : null}

      {courses.length > 0 ? (
        <section className="home__section" aria-labelledby="your-courses">
          <h2 id="your-courses" className="u-caps">
            Your courses
          </h2>
          <ul className="home__list">
            {courses.map((course) => (
              <li key={course.courseId} className="home__row">
                <Link className="home__course-link" to={`/course/${course.courseId ?? ''}`}>
                  {course.title}
                </Link>
                <span className="home__row-meta">
                  {course.completed ? (
                    <StateChip state="passed" detail="complete" />
                  ) : course.overdue ? (
                    <StateChip state="overdue" detail={due(course.dueOn)} />
                  ) : (
                    <Progress
                      percent={course.percentComplete ?? 0}
                      label={`${course.title ?? 'This course'}, ${course.percentComplete ?? 0} per cent complete`}
                      dense
                    />
                  )}
                </span>
              </li>
            ))}
          </ul>
        </section>
      ) : null}

      <p className="u-meta home__generated">
        {summary.assigned ?? 0} assigned · {summary.completed ?? 0} completed ·{' '}
        {summary.overdue ?? 0} overdue
      </p>
    </div>
  );
}

function due(dueOn: string | undefined) {
  if (!dueOn) {
    return undefined;
  }
  // One place for dates, and the viewer's locale rather than ours (T-10.8). `undefined` as the
  // locale means the browser's, which is the only one that is right for the person reading it.
  return new Date(dueOn).toLocaleDateString(undefined, { day: 'numeric', month: 'short' });
}

/**
 * A locked node and the sentence a learner reads instead of a padlock (T-5.3).
 *
 * <p>Exported and unused by {@link HomeScreen} on purpose — see the comment there. It exists so
 * that the reason is a required prop from the first day rather than an optional one added later,
 * which is how a lock ends up drawn without one.
 */
export function LockedNext({ title, reason }: { title: string; reason: string }) {
  return (
    <div className="locked-box">
      <StateChip state="locked" />
      <p className="u-display locked-box__title">{title}</p>
      <p>{reason}</p>
    </div>
  );
}
