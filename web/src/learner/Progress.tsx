import { useCallback, useEffect, useState } from 'react';
import { catalog, failureFrom, type ApiFailure } from '../shared/api/client.ts';
import type { components } from '../shared/api/catalog.d.ts';
import { Empty, ErrorState, Loading } from '../shared/state/States.tsx';

type Summary = components['schemas']['Summary'];

/**
 * The learner's second tab (T-10.3).
 *
 * <p>Small on purpose. It reads the same `/api/v1/me/home` the first tab does and shows the half
 * that tab does not: the counts. The alternative — a transcript — is T-7.5 and belongs to the
 * reporting surface, so this is what there is honest data for today.
 *
 * <p>Counts and nothing else means no chart. Five numbers a person checks twice a year do not
 * become clearer as a donut.
 */
type Screen =
  | { status: 'loading' }
  | { status: 'ready'; summary: Summary }
  | { status: 'failed'; failure: ApiFailure };

export function Progress() {
  const [screen, setScreen] = useState<Screen>({ status: 'loading' });

  const load = useCallback(() => {
    catalog
      .GET('/api/v1/me/home')
      .then(({ data, response, error }) => {
        setScreen(
          data
            ? { status: 'ready', summary: data.summary ?? {} }
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
    return <Loading what="your progress" />;
  }
  if (screen.status === 'failed') {
    return <ErrorState message={screen.failure.message} retry={load} />;
  }

  const { summary } = screen;
  if ((summary.assigned ?? 0) === 0) {
    return (
      <Empty title="Nothing has been assigned to you yet.">
        <p className="u-meta">There is nothing to show progress against. Nothing to do today.</p>
      </Empty>
    );
  }

  return (
    <div className="progress-screen">
      <h1 className="u-display">Your progress</h1>
      <dl className="counts counts--learner">
        <Figure label="Assigned" value={summary.assigned} />
        <Figure label="Completed" value={summary.completed} />
        <Figure label="In progress" value={summary.inProgress} />
        <Figure label="Due soon" value={summary.dueSoon} />
        <Figure label="Overdue" value={summary.overdue} />
      </dl>
    </div>
  );
}

function Figure({ label, value }: { label: string; value: number | undefined }) {
  return (
    <div className="count">
      <dt className="u-caps">{label}</dt>
      <dd className="u-display count__value">{value ?? 0}</dd>
    </div>
  );
}
