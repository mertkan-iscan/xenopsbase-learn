import { useCallback, useEffect, useRef, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { assessment, failureFrom, type ApiFailure } from '../shared/api/client.ts';
import type { components } from '../shared/api/assessment.d.ts';
import { withSessionRecovery } from '../shared/auth/recovery.ts';
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
      <section className="sit sit__done panel">
        <h1 className="u-display">{t('sit.submitted.title')}</h1>
        <p>{t('sit.submitted.body')}</p>
        <button
          type="button"
          className="btn btn-primary"
          onClick={() => void navigate(`/review/${screen.attemptId}`)}
        >
          {t('sit.see-result')}
        </button>
      </section>
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
    <div className="sit">
      <Clock key={attempt.attemptId} secondsRemaining={attempt.secondsRemaining} />

      <p className="sit__where u-meta">
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

      <div className="sit__moves">
        <button
          type="button"
          className="btn btn-secondary"
          onClick={() => setAt((was) => Math.max(0, was - 1))}
          disabled={at === 0}
        >
          {t('sit.back')}
        </button>
        {/*
         * Leaving it blank is a button, not an absence. The API treats an empty response as
         * unanswered rather than as an error, and a screen that only offers "answer and continue"
         * has quietly made that unreachable.
         */}
        <button
          type="button"
          className="btn btn-ghost"
          onClick={() => {
            if (item?.formItemId) {
              void answer(item.formItemId, {});
            }
            setAt((was) => Math.min(items.length - 1, was + 1));
          }}
        >
          {t('answer.leave-blank')}
        </button>
        <button
          type="button"
          className="btn btn-secondary"
          onClick={() => setAt((was) => Math.min(items.length - 1, was + 1))}
          disabled={at >= items.length - 1}
        >
          {t('sit.next')}
        </button>
      </div>

      <div className="sit__submit panel">
        <p>
          {unanswered === 0
            ? t('sit.all-answered-note')
            : plural('sit.blank-note', unanswered, {
                of: formatNumber(locale, items.length),
              })}
        </p>
        <button type="button" className="btn btn-primary" onClick={() => void submit()}>
          {t('sit.submit')}
        </button>
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

  if (left === undefined) {
    return <p className="sit__clock u-caps">{t('sit.no-time-limit')}</p>;
  }
  return (
    <p className="sit__clock u-caps" role="timer" aria-live="off">
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
    <section className="sit__disclosure panel" aria-labelledby="disclosure">
      <h1 id="disclosure" className="u-display">
        {t('sit.disclosure.title')}
      </h1>
      {monitoring?.collects?.length ? (
        <>
          {/*
           * The list under this sentence is the SERVER's, generated from the same enum the
           * recorder accepts (T-6.8) — so it arrives translated because the request carried
           * `Accept-Language`, and a signal still cannot be collected without appearing here.
           */}
          <p>{t('sit.disclosure.we-record')}</p>
          <ul>
            {monitoring.collects.map((one) => (
              <li key={one}>{one}</li>
            ))}
          </ul>
        </>
      ) : (
        <p>{t('sit.disclosure.nothing')}</p>
      )}
      {monitoring?.usedFor ? <p>{monitoring.usedFor}</p> : null}
      {monitoring?.neverUsedFor ? (
        <p>
          <strong>{monitoring.neverUsedFor}</strong>
        </p>
      ) : null}
      {monitoring?.keptForDays ? (
        <p className="u-meta">
          {plural('sit.disclosure.kept', monitoring.keptForDays, {
            count: formatNumber(locale, monitoring.keptForDays),
          })}
        </p>
      ) : null}
      <div className="sit__moves">
        <button type="button" className="btn btn-primary" onClick={onUnderstood}>
          {t('sit.disclosure.start')}
        </button>
        <button type="button" className="btn btn-ghost" onClick={onLeave}>
          {t('sit.disclosure.not-now')}
        </button>
      </div>
    </section>
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
