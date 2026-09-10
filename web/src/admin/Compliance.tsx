import { formatNumber } from '../shared/i18n/format.ts';
import { useLocale } from '../shared/i18n/useLocale.ts';
import { Empty } from '../shared/state/States.tsx';
import { NotEnforcedYet } from './NotEnforcedYet.tsx';

/**
 * Compliance reporting — designed, and not buildable yet (T-10.6).
 *
 * <p>This screen used to render a company of 5,182 learners from a fixture. It was the most
 * convincing thing in the console and every number on it was invented, which makes it the most
 * misleading: a screen that looks like a report is one somebody quotes.
 *
 * <p><b>`reporting` accepts telemetry and answers no queries at all.</b> Not "there is no endpoint
 * for this report" — there is no endpoint for any report. The rollups that would feed one are
 * T-7.1 to T-7.7, and the design for this screen (counts, a filter, a cursor-paged list, the
 * retention curve) is in docs/design-prompt.md waiting for them.
 */
export function Compliance() {
  const { locale, t } = useLocale();
  return (
    <div className="compliance-page">
      <NotEnforcedYet />
      <Empty title={t('compliance.empty.title')}>
        {/*
         * The service name is interpolated rather than wrapped in <code>, which loses a little
         * typography and buys a sentence that can be reordered. Turkish puts the possessed noun
         * after the possessor ("reporting servisi"), so a name pinned inside markup here would be
         * a name pinned to English word order.
         */}
        <p>{t('compliance.empty.body', { service: 'reporting' })}</p>
        <p className="u-meta">
          {t('compliance.empty.note', { count: formatNumber(locale, 5182) })}
        </p>
      </Empty>
    </div>
  );
}
