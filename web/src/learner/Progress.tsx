import { StatTile } from '../shared/design/Surface.tsx';
import { formatNumber, formatPercent } from '../shared/i18n/format.ts';
import { useLocale } from '../shared/i18n/useLocale.ts';
import { Empty, ErrorState, Loading } from '../shared/state/States.tsx';
import { refreshHome, useHome, type HomeView } from './useHome.ts';

/**
 * The learner's third tab (T-10.3).
 *
 * <p>Small on purpose. It reads the same `/api/v1/me/home` Home and Discover do — through the
 * shared store, so it is not a third request — and shows the half they do not put first: the
 * counts, with the per-course progress underneath.
 *
 * <p>Counts and nothing else means NO CHART. Five numbers a person checks twice a year do not
 * become clearer as a donut, and a dashboard of donuts nobody reads is named in
 * `docs/design-prompt.md` as the house style this product is designed against. A transcript — the
 * thing that would genuinely add to this — is T-7.5 and belongs to the reporting surface.
 */
export function Progress() {
  const state = useHome();

  if (state.status === 'loading') {
    return <Loading what="loading.progress" />;
  }
  if (state.status === 'failed') {
    return <ErrorState message={state.failure.message} retry={() => void refreshHome()} />;
  }
  return <ProgressScreen home={state.home} />;
}

export function ProgressScreen({ home }: { home: HomeView }) {
  const { locale, t } = useLocale();
  const summary = home.summary ?? {};
  const courses = home.courses ?? [];

  if ((summary.assigned ?? 0) === 0) {
    return (
      <Empty title={t('progress.empty.title')}>
        <p>{t('progress.empty.body')}</p>
      </Empty>
    );
  }

  return (
    <div className="flex flex-col gap-8">
      <h1 className="font-display text-2xl font-bold sm:text-3xl">{t('progress.title')}</h1>

      {/*
       * A `dl`, because each tile is genuinely a term and its value. `StatTile` renders spans, so
       * the pairing is stated here where the list is -- a grid of divs would read to a screen
       * reader as five unrelated numbers.
       */}
      <dl className="m-0 grid grid-cols-2 gap-3 sm:grid-cols-3 desk:grid-cols-5">
        <StatTile value={formatNumber(locale, summary.assigned ?? 0)} label={t('progress.assigned')} />
        <StatTile
          value={formatNumber(locale, summary.inProgress ?? 0)}
          label={t('progress.in-progress')}
          tone="brand"
        />
        <StatTile
          value={formatNumber(locale, summary.completed ?? 0)}
          label={t('progress.completed')}
        />
        <StatTile value={formatNumber(locale, summary.dueSoon ?? 0)} label={t('progress.due-soon')} />
        <StatTile
          value={formatNumber(locale, summary.overdue ?? 0)}
          label={t('progress.overdue')}
          tone={(summary.overdue ?? 0) > 0 ? 'alert' : 'plain'}
        />
      </dl>

      {courses.length > 0 ? (
        <section aria-labelledby="per-course" className="flex flex-col gap-3">
          <h2 id="per-course" className="text-base font-semibold">
            {t('progress.per-course')}
          </h2>
          {/*
           * A table rather than a list of cards. This is the one learner screen that is genuinely
           * tabular -- a course, a number, a state -- and a card each would be five boxes saying
           * one thing. It scrolls inside its own box on a phone; the page never scrolls sideways.
           */}
          <div className="scroll-x rounded-xl border border-hairline bg-surface">
            <table className="w-full border-collapse text-sm">
              <thead>
                <tr className="border-b border-hairline">
                  <th scope="col" className="label-caps px-4 py-3 text-start">
                    {t('progress.course')}
                  </th>
                  <th scope="col" className="label-caps px-4 py-3 text-end">
                    {t('progress.done')}
                  </th>
                </tr>
              </thead>
              <tbody>
                {courses.map((course) => (
                  <tr key={course.courseId} className="border-b border-hairline last:border-b-0">
                    <th scope="row" className="px-4 py-3 text-start font-semibold">
                      {course.title}
                    </th>
                    {/* `formatPercent`, which already puts the sign where the language wants it:
                        "62%" in English and "%62" in Turkish. Gluing a literal sign on here would
                        be a fragment assembled in English order, which is the one thing the
                        catalogue's own rules forbid. */}
                    <td className="px-4 py-3 text-end tabular-nums">
                      {formatPercent(locale, course.percentComplete ?? 0)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      ) : null}
    </div>
  );
}
