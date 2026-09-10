import { useCallback, useEffect, useState } from 'react';
import { assessment, failureFrom, type ApiFailure } from '../shared/api/client.ts';
import type { components } from '../shared/api/assessment.d.ts';
import { StateChip } from '../shared/design/State.tsx';
import { formatNumber, formatPercent, formatWaited } from '../shared/i18n/format.ts';
import type { Locale } from '../shared/i18n/locales.ts';
import { useLocale } from '../shared/i18n/useLocale.ts';
import { Empty, ErrorState, Loading } from '../shared/state/States.tsx';
import { NotEnforcedYet } from './NotEnforcedYet.tsx';

type WaitingView = components['schemas']['WaitingView'];
type MarkView = components['schemas']['MarkView'];

/**
 * The marking queue (T-10.4, T-6.7).
 *
 * <p>This is the screen that turns `passed: null` into a verdict. Everything the learner-facing
 * design says about AWAITING GRADING — that it is its own state, never a fail, and that a learner
 * is waiting on a person rather than on a machine — is only true because somebody works this
 * queue.
 *
 * <p>Two things about it are the API's shape rather than a choice:
 *
 * <ul>
 *   <li><b>One response at a time.</b> Marking posts a single answer and the server recomputes the
 *       whole attempt, so the verdict can flip on any mark. The recomputed attempt comes back in
 *       the response and is shown immediately.
 *   <li><b>Nothing is claimed.</b> There is no lock on a queue item, so two markers can open the
 *       same attempt. The screen says so rather than pretending the list is a work allocation.
 * </ul>
 */
type Screen =
  | { status: 'loading' }
  | { status: 'ready'; waiting: WaitingView[] }
  | { status: 'failed'; failure: ApiFailure };

export function Grading() {
  const { locale, t } = useLocale();
  const [screen, setScreen] = useState<Screen>({ status: 'loading' });
  const [open, setOpen] = useState<WaitingView | null>(null);

  const load = useCallback(() => {
    assessment
      .GET('/api/v1/grading/queue')
      .then(({ data, response, error }) => {
        setScreen(
          data
            ? { status: 'ready', waiting: data }
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

  if (screen.status === 'loading') {
    return <Loading what="loading.marking" />;
  }
  if (screen.status === 'failed') {
    return <ErrorState message={screen.failure.message} retry={load} />;
  }

  return (
    <div className="grading">
      <NotEnforcedYet />
      {screen.waiting.length === 0 ? (
        <Empty title={t('grading.empty.title')}>
          <p className="u-meta">{t('grading.empty.body')}</p>
        </Empty>
      ) : (
        <section aria-labelledby="queue">
          <h2 id="queue" className="u-caps">
            {t('grading.queue.title')}
          </h2>
          <p className="u-meta">{t('grading.queue.note')}</p>
          <div className="u-scroll-x">
            <table className="table">
              <thead>
                <tr>
                  <th scope="col">{t('grading.col.test')}</th>
                  <th scope="col">{t('grading.col.attempt')}</th>
                  <th scope="col">{t('grading.col.outstanding')}</th>
                  <th scope="col">{t('grading.col.waiting')}</th>
                  <th scope="col" />
                </tr>
              </thead>
              <tbody>
                {screen.waiting.map((one) => (
                  <tr key={one.attemptId}>
                    <td>{one.testTitle}</td>
                    <td>#{one.attemptNumber}</td>
                    <td>{one.outstanding}</td>
                    <td>{waitingFor(locale, one.waitingSeconds)}</td>
                    <td>
                      <button
                        type="button"
                        className="btn btn-secondary btn-dense"
                        onClick={() => setOpen(one)}
                      >
                        {t('grading.mark')}
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {open ? <MarkAttempt waiting={open} onGraded={load} /> : null}
    </div>
  );
}

/**
 * How long somebody has been waiting, in the units a person thinks in.
 *
 * <p>Shown because it is the only thing on this screen that argues for one attempt over another:
 * the queue has no priority and no claim, so "who has waited longest" is the whole ordering story.
 */
function waitingFor(locale: Locale, seconds: number | undefined) {
  return seconds === undefined ? '—' : formatWaited(locale, seconds);
}

function MarkAttempt({ waiting, onGraded }: { waiting: WaitingView; onGraded: () => void }) {
  const { locale, t } = useLocale();
  const [marks, setMarks] = useState<MarkView[]>([]);
  const [verdict, setVerdict] = useState<components['schemas']['AttemptGradingView'] | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  const attemptId = waiting.attemptId;

  const load = useCallback(() => {
    if (!attemptId) {
      return;
    }
    void assessment
      .GET('/api/v1/grading/attempts/{attemptId}/marks', { params: { path: { attemptId } } })
      .then(({ data }) => setMarks(data ?? []));
  }, [attemptId]);

  useEffect(() => {
    load();
  }, [load]);

  async function mark(responseId: string, awarded: number, comment: string) {
    if (!attemptId) {
      return;
    }
    const { data, response, error } = await assessment.POST(
      '/api/v1/grading/attempts/{attemptId}/answers/{responseId}',
      { params: { path: { attemptId, responseId } }, body: { awarded, comment } },
    );
    if (!data) {
      setProblem(failureFrom(response, error).message);
      return;
    }
    // The whole attempt is rescored on every mark, so the verdict here is the current one and not
    // a running total this screen kept.
    setVerdict(data);
    setProblem(null);
    load();
    onGraded();
  }

  return (
    <section className="grading__attempt panel" aria-labelledby="marking">
      <h2 id="marking" className="u-caps">
        {t('grading.attempt-heading', {
          test: waiting.testTitle ?? '',
          number: waiting.attemptNumber ?? '',
        })}
      </h2>

      {verdict ? (
        <p className="grading__verdict">
          <StateChip
            state={
              verdict.grading === 'AWAITING_GRADING'
                ? 'awaiting'
                : verdict.passed === true
                  ? 'passed'
                  : 'not-passed'
            }
          />
          <span>
            {verdict.grading === 'AWAITING_GRADING'
              ? t('grading.still-waiting')
              : t(verdict.passed ? 'grading.verdict.passed' : 'grading.verdict.not-passed', {
                  percent: formatPercent(locale, verdict.scorePercent ?? 0),
                })}
          </span>
        </p>
      ) : null}

      {problem ? (
        <p className="authoring__short" role="alert">
          {problem}
        </p>
      ) : null}

      <ul className="grading__marks">
        {marks.map((one) => (
          <li key={one.responseId} className="panel grading__mark">
            <span className="u-caps">
              {one.graded ? t('grading.marked') : t('grading.not-marked')}
              {' · '}
              {t('grading.out-of', { available: formatNumber(locale, one.available ?? 0) })}
            </span>
            {one.graded ? (
              <p className="u-meta">
                {t('grading.awarded', {
                  awarded: formatNumber(locale, one.awarded ?? 0),
                  available: formatNumber(locale, one.available ?? 0),
                })}
                {one.comment ? ` — ${one.comment}` : ''}
              </p>
            ) : (
              <MarkOne
                available={one.available ?? 0}
                onMark={(awarded, comment) =>
                  one.responseId && void mark(one.responseId, awarded, comment)
                }
              />
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}

function MarkOne({
  available,
  onMark,
}: {
  available: number;
  onMark: (awarded: number, comment: string) => void;
}) {
  const { locale, t } = useLocale();
  const [awarded, setAwarded] = useState('');
  const [comment, setComment] = useState('');
  const value = Number(awarded);
  const sensible = awarded !== '' && Number.isFinite(value) && value >= 0 && value <= available;

  return (
    <form
      className="grading__form"
      onSubmit={(event) => {
        event.preventDefault();
        if (sensible) {
          onMark(value, comment);
        }
      }}
    >
      <label className="u-caps" htmlFor="awarded">
        {t('grading.award')}
      </label>
      <input
        id="awarded"
        className="input input-dense"
        inputMode="decimal"
        value={awarded}
        onChange={(event) => setAwarded(event.target.value)}
        placeholder={t('grading.range', { available: formatNumber(locale, available) })}
      />
      <label className="u-caps" htmlFor="comment">
        {t('grading.comment')}
      </label>
      <input
        id="comment"
        className="input input-dense"
        value={comment}
        onChange={(event) => setComment(event.target.value)}
      />
      <button type="submit" className="btn btn-primary" disabled={!sensible}>
        {t('grading.mark')}
      </button>
    </form>
  );
}
