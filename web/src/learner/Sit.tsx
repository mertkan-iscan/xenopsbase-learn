import { ChevronLeft, ChevronRight, CircleCheck, Clock3, ShieldAlert } from 'lucide-react';
import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { assessment, failureFrom, type ApiFailure } from '../shared/api/client.ts';
import type { components } from '../shared/api/assessment.d.ts';
import { withSessionRecovery } from '../shared/auth/recovery.ts';
import { Button } from '../shared/design/Button.tsx';
import { Card } from '../shared/design/Surface.tsx';
import { formatNumber, formatPosition } from '../shared/i18n/format.ts';
import { useLocale, useT } from '../shared/i18n/useLocale.ts';
import { ErrorState, Loading } from '../shared/state/States.tsx';
import { Question, type QuestionBody, type Response } from './Question.tsx';

type SittingView = components['schemas']['SittingView'];
type MonitoringView = components['schemas']['MonitoringView'];

/**
 * Sitting a test (T-6.6, T-6.8, T-10.3).
 *
 * <p>The screen the product exists for, and the one with the most rules that are not negotiable.
 * Each of these is the server's, and this screen's job is to not contradict it:
 *
 * <ul>
 *   <li><b>The clock is the server's.</b> `secondsRemaining` arrives with the attempt and is
 *       counted down locally only so the number moves; it is never the authority, and a browser
 *       whose clock is wrong changes nothing. The server refuses a late answer with 409.
 *   <li><b>Starting is resuming.</b> `POST .../attempts` returns the open attempt if there is one
 *       and does not move its deadline. There is no separate resume, and a second tab is the same
 *       attempt.
 *   <li><b>An empty response is an unanswered question.</b> Every answer is sent as it changes,
 *       including back to empty. "I don't know" is a state, not a failure.
 *   <li><b>The disclosure comes before the first question.</b> `monitoring` rides on the attempt
 *       precisely so a player has it before it can render anything (T-6.8), and this screen will
 *       not show a question until it has been seen.
 *   <li><b>Submitting goes through session recovery.</b> A forty-minute exam submitted thirty
 *       seconds after a session ended is what `recovery.ts` was written for, and until now nothing
 *       used it.
 * </ul>
 */
type Screen =
  | { status: 'loading' }
  | { status: 'sitting'; attempt: SittingView }
  | { status: 'submitted'; attemptId: string }
  | { status: 'failed'; failure: ApiFailure };

export function Sit() {
  const { locale, t, plural } = useLocale();
  const { testId } = useParams();
  const navigate = useNavigate();
  const [screen, setScreen] = useState<Screen>({ status: 'loading' });
  const [disclosed, setDisclosed] = useState(false);
  const [at, setAt] = useState(0);
  const [answers, setAnswers] = useState<Record<string, Response>>({});

  const start = useCallback(() => {
    if (!testId) {
      return;
    }
    assessment
      .POST('/api/v1/me/tests/{testId}/attempts', { params: { path: { testId } } })
      .then(({ data, response, error }) => {
        if (data) {
          setScreen({ status: 'sitting', attempt: data });
          setAnswers((data.answers ?? {}) as Record<string, Response>);
        } else {
          setScreen({ status: 'failed', failure: failureFrom(response, error) });
        }
      })
      .catch((unreachable: unknown) => {
        setScreen({ status: 'failed', failure: failureFrom(undefined, unreachable) });
      });
  }, [testId]);

  useEffect(() => {
    start();
  }, [start]);

  const attemptId = screen.status === 'sitting' ? screen.attempt.attemptId : undefined;
  useSignals(attemptId, disclosed);

  if (screen.status === 'loading') {
    return <Loading what="loading.test" />;
  }
  if (screen.status === 'failed') {
    return <ErrorState message={screen.failure.message} retry={start} />;
  }
  if (screen.status === 'submitted') {
    return (
      <Card className="mx-auto flex max-w-lg flex-col items-start gap-4 p-6">
        <CircleCheck aria-hidden="true" className="size-8 text-passed-fg" strokeWidth={2} />
        <h1 className="font-display text-xl font-bold">{t('sit.submitted.title')}</h1>
        <p className="text-sm text-muted">{t('sit.submitted.body')}</p>
        <Button voice="primary" onClick={() => void navigate(`/review/${screen.attemptId}`)}>
          {t('sit.see-result')}
        </Button>
      </Card>
    );
  }

  const { attempt } = screen;
  const items = attempt.items ?? [];
  const item = items[at];

  if (!disclosed) {
    return (
      <Disclosure
        monitoring={attempt.monitoring}
        onUnderstood={() => setDisclosed(true)}
        onLeave={() => void navigate('/')}
      />
    );
  }

  async function answer(formItemId: string, response: Response) {
    setAnswers((was) => ({ ...was, [formItemId]: response }));
    if (!attempt.attemptId) {
      return;
    }
    await assessment.PUT('/api/v1/me/attempts/{id}/answers/{formItemId}', {
      params: { path: { id: attempt.attemptId, formItemId } },
      body: { response },
    });
  }

  async function submit() {
    if (!attempt.attemptId) {
      return;
    }
    const id = attempt.attemptId;
    // THE ONE CALL THAT MUST NOT LOSE ITS PAYLOAD. Signing in again is a full navigation, so the
    // answers are parked before the person is sent away and replayed when they come back.
    const outcome = await withSessionRecovery(
      { label: 'attempt-submission', payload: { attemptId: id, answers } },
      () =>
        assessment
          .POST('/api/v1/me/attempts/{id}/submit', { params: { path: { id } } })
          .then(({ response }) => ({ response })),
    );
    if (outcome.kind === 'done') {
      setScreen({ status: 'submitted', attemptId: id });
    } else if (outcome.kind === 'session-ended') {
      window.location.assign('/');
    } else {
      setScreen({
        status: 'failed',
        failure: failureFrom(outcome.response, undefined),
      });
    }
  }

  const unanswered = items.filter(
    (one) => !one.formItemId || !answers[one.formItemId] || isEmpty(answers[one.formItemId]),
  ).length;

  return (
    /*
     * `pb-28` leaves room for the sticky submit bar, which is fixed and would otherwise cover the
     * last question's own controls -- the one thing on this screen that must never be unreachable.
     */
    <div className="mx-auto flex max-w-3xl flex-col gap-5 pb-28">
      <Clock key={attempt.attemptId} secondsRemaining={attempt.secondsRemaining} />

      <p className="text-sm text-muted">
        {t('sit.where', {
          position: formatNumber(locale, at + 1),
          of: formatNumber(locale, items.length),
        })}
        {' · '}
        {unanswered > 0
          ? t('sit.not-answered', { count: formatNumber(locale, unanswered) })
          : t('sit.all-answered')}
      </p>

      {item ? (
        <Question
          body={(item.body ?? {}) as QuestionBody}
          optionOrder={(item.optionOrder ?? {}) as Record<string, string[]>}
          answer={item.formItemId ? answers[item.formItemId] : undefined}
          onAnswer={(response) => item.formItemId && void answer(item.formItemId, response)}
        />
      ) : null}

      <div className="flex flex-wrap items-center gap-2">
        <Button
          voice="secondary"
          onClick={() => setAt((was) => Math.max(0, was - 1))}
          disabled={at === 0}
        >
          <ChevronLeft aria-hidden="true" className="size-4" />
          {t('sit.back')}
        </Button>
        {/*
         * Leaving it blank is a button, not an absence. The API treats an empty response as
         * unanswered rather than as an error, and a screen that only offers "answer and continue"
         * has quietly made that unreachable.
         */}
        <Button
          voice="ghost"
          onClick={() => {
            if (item?.formItemId) {
              void answer(item.formItemId, {});
            }
            setAt((was) => Math.min(items.length - 1, was + 1));
          }}
        >
          {t('answer.leave-blank')}
        </Button>
        <Button
          voice="secondary"
          onClick={() => setAt((was) => Math.min(items.length - 1, was + 1))}
          disabled={at >= items.length - 1}
        >
          {t('sit.next')}
          <ChevronRight aria-hidden="true" className="size-4" />
        </Button>
      </div>

      {/*
       * STICKY, AND GLASS. Submit is the one control on this screen that must be reachable at
       * every scroll position -- a learner who has finished and cannot find the button assumes the
       * exam is broken. Glass here is one of the three places it is allowed (styles.css): content
       * scrolls underneath it, so a solid bar would read as a hole in the page.
       */}
      <div className="glass fixed inset-x-0 bottom-0 z-30 border-t">
        <div className="mx-auto flex max-w-3xl flex-wrap items-center justify-between gap-3 px-4 py-3">
          <p className="min-w-0 flex-1 text-sm text-muted">
            {unanswered === 0
              ? t('sit.all-answered-note')
              : plural('sit.blank-note', unanswered, {
                  of: formatNumber(locale, items.length),
                })}
          </p>
          <Button voice="primary" onClick={() => void submit()}>
            {t('sit.submit')}
          </Button>
        </div>
      </div>
    </div>
  );
}

function isEmpty(response: Response | undefined) {
  return response === undefined || Object.keys(response).length === 0;
}

/**
 * The clock, counted down here and owned by the server.
 *
 * <p>`secondsRemaining` is what the server said when the attempt was fetched. This ticks it so the
 * number moves; it is a DISPLAY of a deadline it does not own. When it reaches zero nothing is
 * submitted automatically — the server refuses a late answer and expires the attempt on its own
 * schedule, and a browser deciding "time is up" would be a browser deciding an exam.
 */
function Clock({ secondsRemaining }: { secondsRemaining: number | undefined }) {
  const t = useT();
  const [left, setLeft] = useState(secondsRemaining);

  // Only the interval. The initial value comes from the prop through useState, and the component
  // is keyed on the attempt at the call site -- so a new attempt remounts it rather than syncing
  // the prop into state here, which is the pattern React's lint refuses and is right to.
  useEffect(() => {
    if (secondsRemaining === undefined) {
      return;
    }
    const tick = window.setInterval(() => {
      setLeft((was) => (was === undefined ? was : Math.max(0, was - 1)));
    }, 1000);
    return () => window.clearInterval(tick);
  }, [secondsRemaining]);

  const shape =
    'flex items-center gap-2 self-start rounded-full border px-3 py-1.5 text-xs font-bold tabular-nums';

  if (left === undefined) {
    return (
      <p className={`${shape} border-hairline bg-surface-muted text-muted`}>
        <Clock3 aria-hidden="true" className="size-3.5" />
        {t('sit.no-time-limit')}
      </p>
    );
  }
  return (
    /*
     * `role="timer"` with `aria-live="off"`. A live region announcing every second would make this
     * screen unusable with a screen reader, and the number is a DISPLAY of a deadline the server
     * owns rather than the authority on it.
     *
     * Under five minutes it takes the overdue tint. That is the one moment the colour is worth
     * spending: a learner who has not noticed the clock is the learner it exists for.
     */
    <p
      role="timer"
      aria-live="off"
      className={`${shape} ${
        left <= 300
          ? 'border-overdue-edge bg-overdue-bg text-overdue-fg'
          : 'border-hairline bg-surface-muted text-muted'
      }`}
    >
      <Clock3 aria-hidden="true" className="size-3.5" />
      {left === 0 ? t('sit.time-up') : t('sit.time-left', { at: formatPosition(left) })}
    </p>
  );
}

/**
 * What is recorded while somebody sits a test, before they sit it (T-6.8).
 *
 * <p>The list is generated from the same enum the recorder accepts, so a signal cannot be
 * collected without appearing here. A server cannot make a client display this; what it can do is
 * hand it over before the first question, which is what it does. Throwing it away would be lying
 * to the person, and no API shape prevents that — so this screen does not render a question until
 * it has been shown.
 */
function Disclosure({
  monitoring,
  onUnderstood,
  onLeave,
}: {
  monitoring: MonitoringView | undefined;
  onUnderstood: () => void;
  onLeave: () => void;
}) {
  const { locale, t, plural } = useLocale();
  return (
    <Card className="mx-auto max-w-2xl p-6">
      <section aria-labelledby="disclosure" className="flex flex-col gap-4">
        <ShieldAlert aria-hidden="true" className="size-7 text-brand" strokeWidth={2} />
        <h1 id="disclosure" className="font-display text-xl font-bold">
          {t('sit.disclosure.title')}
        </h1>
      {monitoring?.collects?.length ? (
        <>
          {/*
           * The list under this sentence is the SERVER's, generated from the same enum the
           * recorder accepts (T-6.8) — so it arrives translated because the request carried
           * `Accept-Language`, and a signal still cannot be collected without appearing here.
           */}
          <p className="text-sm">{t('sit.disclosure.we-record')}</p>
          <ul className="flex list-disc flex-col gap-1.5 ps-5 text-sm text-muted">
            {monitoring.collects.map((one) => (
              <li key={one}>{one}</li>
            ))}
          </ul>
        </>
      ) : (
        <p className="text-sm">{t('sit.disclosure.nothing')}</p>
      )}
      {monitoring?.usedFor ? <p className="text-sm">{monitoring.usedFor}</p> : null}
      {monitoring?.neverUsedFor ? (
        /*
         * THE SENTENCE ABOUT WHAT IS NOT DONE, IN FULL WEIGHT. This product is not proctoring --
         * no camera, no microphone, no keystroke content -- and a learner who does not read this
         * line is a learner who feels watched by a machine that is not watching them.
         */
        <p className="rounded-lg border border-passed-edge bg-passed-bg p-3 text-sm font-semibold text-passed-fg">
          {monitoring.neverUsedFor}
        </p>
      ) : null}
      {monitoring?.keptForDays ? (
        <p className="text-xs text-muted">
          {plural('sit.disclosure.kept', monitoring.keptForDays, {
            count: formatNumber(locale, monitoring.keptForDays),
          })}
        </p>
      ) : null}
        <div className="flex flex-wrap gap-2">
          <Button voice="primary" onClick={onUnderstood}>
            {t('sit.disclosure.start')}
          </Button>
          <Button voice="ghost" onClick={onLeave}>
            {t('sit.disclosure.not-now')}
          </Button>
        </div>
      </section>
    </Card>
  );
}

/**
 * The signals the disclosure named, reported as they happen (T-6.8).
 *
 * <p>Only kinds the server accepts, and never any content: `detail` carries no pasted text, which
 * is the difference between corroboration and surveillance. Reported after the disclosure has been
 * seen and not before — recording somebody before telling them is the thing the disclosure exists
 * to prevent.
 */
function useSignals(attemptId: string | undefined, armed: boolean) {
  const last = useRef<string>('');

  useEffect(() => {
    if (!attemptId || !armed) {
      return;
    }
    const send = (kind: string) => {
      // The same kind twice running is one event as far as a marker is concerned, and a tab
      // switched back and forth twenty times is noise that buries the one that mattered.
      if (last.current === kind) {
        return;
      }
      last.current = kind;
      void assessment.POST('/api/v1/me/attempts/{id}/signals', {
        params: { path: { id: attemptId } },
        body: { kind, reportedAt: new Date().toISOString() },
      });
    };
    const onVisibility = () => send(document.hidden ? 'TAB_HIDDEN' : 'TAB_VISIBLE');
    const onBlur = () => send('FOCUS_LOST');
    const onFocus = () => send('FOCUS_REGAINED');
    const onPaste = () => {
      last.current = '';
      send('PASTE');
    };

    document.addEventListener('visibilitychange', onVisibility);
    window.addEventListener('blur', onBlur);
    window.addEventListener('focus', onFocus);
    document.addEventListener('paste', onPaste);
    return () => {
      document.removeEventListener('visibilitychange', onVisibility);
      window.removeEventListener('blur', onBlur);
      window.removeEventListener('focus', onFocus);
      document.removeEventListener('paste', onPaste);
    };
  }, [attemptId, armed]);
}
