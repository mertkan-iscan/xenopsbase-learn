import { useCallback, useEffect, useState } from 'react';
import { catalog, failureFrom, type ApiFailure } from '../shared/api/client.ts';
import type { components } from '../shared/api/catalog.d.ts';
import type { MessageKey } from '../shared/i18n/messages.en.ts';
import { formatNumber } from '../shared/i18n/format.ts';
import { useLocale, useT } from '../shared/i18n/useLocale.ts';
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
  const t = useT();
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
    return <Loading what="loading.progress" />;
  }
  if (screen.status === 'failed') {
    return <ErrorState message={screen.failure.message} retry={load} />;
  }

  const { summary } = screen;
  if ((summary.assigned ?? 0) === 0) {
    return (
      <Empty title={t('progress.empty.title')}>
        <p className="u-meta">{t('progress.empty.body')}</p>
      </Empty>
    );
  }

  return (
    <div className="progress-screen">
      <h1 className="u-display">{t('progress.title')}</h1>
      <dl className="counts counts--learner">
        <Figure label="progress.assigned" value={summary.assigned} />
        <Figure label="progress.completed" value={summary.completed} />
        <Figure label="progress.in-progress" value={summary.inProgress} />
        <Figure label="progress.due-soon" value={summary.dueSoon} />
        <Figure label="progress.overdue" value={summary.overdue} />
      </dl>
    </div>
  );
}

/**
 * One count and its name.
 *
 * <p>`label` is a message key rather than a word, so the five call sites above read as a list of
 * what is counted rather than as a list of English nouns — and a sixth count cannot be added
 * without a Turkish name for it.
 */
function Figure({ label, value }: { label: MessageKey; value: number | undefined }) {
  const { locale, t } = useLocale();
  return (
    <div className="count">
      <dt className="u-caps">{t(label)}</dt>
      <dd className="u-display count__value">{formatNumber(locale, value ?? 0)}</dd>
    </div>
  );
}
