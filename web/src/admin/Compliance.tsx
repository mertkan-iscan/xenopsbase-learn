import { useState } from 'react';
import { StateChip, type StateName } from '../shared/design/State.tsx';

/**
 * Compliance for a company of five thousand, and the retention curve (T-10.6, T-7.6, T-7.4).
 *
 * <p>A compliance view IS NOT A TABLE OF EVERYTHING. It is a small number of counts, a filter, and
 * a list you page through — which is also the only shape that works against a cursor-paged API
 * that deliberately will not hand anybody a whole company.
 *
 * <p>Two things on this screen are decisions rather than layout.
 *
 * <p><b>AWAITING GRADING gets its own count, next to the failures and not among them.</b> A
 * hundred and forty-eight attempts waiting on a person are not a hundred and forty-eight problems,
 * and a compliance officer who reads them as failures chases people who have already done the
 * work.
 *
 * <p><b>The export is the async job it is.</b> Progress, a clear finish, and a link that expires —
 * not a button that freezes while five thousand rows are assembled behind it.
 *
 * <p>NO REPORTING ENDPOINT YET: `reporting` today answers telemetry ingest and nothing else, and
 * the rollups these counts come from are T-7.1 to T-7.7. The figures below are a fixture. The
 * paging, the filter and the chart's shape are not — they are what the API will be asked for.
 */
type Row = {
  id: string;
  learner: string;
  group: string;
  state: StateName;
  detail?: string;
  dueOn: string;
  lastSeen: string;
};

const rows: Row[] = [
  { id: '1', learner: 'M. Kowalski', group: 'Warehouse · Nights', state: 'overdue', detail: '3d', dueOn: '4 Sep', lastSeen: '1 Sep' },
  { id: '2', learner: 'R. Adeyemi', group: 'Warehouse · Days', state: 'overdue', detail: '3d', dueOn: '4 Sep', lastSeen: 'Today' },
  { id: '3', learner: 'T. Bianchi', group: 'Legal', state: 'awaiting', dueOn: '4 Sep', lastSeen: '6 Sep' },
  { id: '4', learner: 'S. Nakamura', group: 'Fleet', state: 'overdue', detail: '9d', dueOn: '29 Aug', lastSeen: '—' },
  { id: '5', learner: 'P. Novák', group: 'Warehouse · Nights', state: 'not-passed', dueOn: '4 Sep', lastSeen: '5 Sep' },
  { id: '6', learner: 'A. Haddad', group: 'Fleet', state: 'overdue', detail: '1d', dueOn: '6 Sep', lastSeen: 'Today' },
];

const filters = ['Overdue', 'Awaiting grading', 'All'] as const;

export function Compliance() {
  const [only, setOnly] = useState<(typeof filters)[number]>('Overdue');

  const shown = rows.filter((row) =>
    only === 'All'
      ? true
      : only === 'Overdue'
        ? row.state === 'overdue'
        : row.state === 'awaiting',
  );

  return (
    <div className="compliance panel">
      <div className="compliance__head">
        <div>
          <h1 className="u-display">Fire Safety Refresher</h1>
          <p className="u-meta">Cycle 3 · closes 30 Sep · figures as of 09:41 today</p>
        </div>
        <button type="button" className="btn btn-secondary">
          Export this view
        </button>
      </div>

      <div className="counts">
        <Count label="Assigned" value="5,182" />
        <Count label="Passed" value="3,904" />
        <Count label="In progress" value="712" />
        <Count
          label="Awaiting grading"
          value="148"
          accent
          note="Not failures. Waiting on a person."
        />
        <Count label="Overdue" value="418" />
      </div>

      <div className="compliance__filter">
        <label className="u-caps" htmlFor="learner-filter">
          Name or email
        </label>
        <input id="learner-filter" className="input input-dense" placeholder="Name or email" />
        <span className="seg" role="group" aria-label="Show only">
          {filters.map((name) => (
            <button
              key={name}
              type="button"
              className="seg-opt"
              aria-pressed={only === name}
              onClick={() => setOnly(name)}
            >
              {name}
            </button>
          ))}
        </span>
        <span className="u-meta compliance__count">418 match · showing 1–50</span>
      </div>

      <div className="u-scroll-x">
        <table className="table">
          <thead>
            <tr>
              <th scope="col">Learner</th>
              <th scope="col">Group</th>
              <th scope="col">State</th>
              <th scope="col">Due</th>
              <th scope="col">Last seen</th>
            </tr>
          </thead>
          <tbody>
            {shown.map((row) => (
              <tr key={row.id}>
                <td>{row.learner}</td>
                <td>{row.group}</td>
                <td>
                  <StateChip state={row.state} detail={row.detail} />
                </td>
                <td>{row.dueOn}</td>
                <td>{row.lastSeen}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="compliance__paging">
        <span className="u-meta">Cursor-paged. Nothing here loads the whole company.</span>
        <button type="button" className="btn btn-secondary btn-dense">
          Next 50 <span aria-hidden="true">→</span>
        </button>
      </div>

      {/*
       * The job, not a frozen button. `aria-live` off: a progress bar that announces every
       * percentage is a progress bar nobody can work beside.
       */}
      <div className="export">
        <span className="u-caps">Export · CSV</span>
        <span
          className="progress export__bar"
          role="progressbar"
          aria-valuenow={74}
          aria-valuemin={0}
          aria-valuemax={100}
          aria-label="Building the export"
        >
          <span className="progress__fill" style={{ width: '74%' }} />
        </span>
        <span className="u-meta">Building — 3,840 of 5,182 rows</span>
        <span className="u-meta export__note">
          You can leave this page. The link expires 24 hours after it is ready.
        </span>
      </div>

      <RetentionCurve />
    </div>
  );
}

function Count({
  label,
  value,
  note,
  accent = false,
}: {
  label: string;
  value: string;
  note?: string;
  accent?: boolean;
}) {
  return (
    <div className={accent ? 'count count--accent' : 'count'}>
      <span className="u-caps">{label}</span>
      <span className="u-display count__value">{value}</span>
      {note ? <span className="u-meta">{note}</span> : null}
    </div>
  );
}

/**
 * Where people stop watching (T-7.4) — the one chart in this product with real analytical value.
 *
 * <p>Drawn rather than charted: a line, an area under it, and two rules where the interstitials
 * sit. A library would add a hundred kilobytes to an admin bundle to produce this, and the thing
 * that makes it readable is not the plotting — it is that the markers are on the same axis as the
 * drops, so the sentence underneath is something the reader can check.
 *
 * <p>The colours come from the tokens, so the chart is legible in both themes rather than in the
 * one it was drawn in.
 */
function RetentionCurve() {
  const points =
    '0,10 120,18 240,34 330,42 340,96 460,110 620,124 780,142 860,150 870,186 980,196 1120,214';

  return (
    <section className="retention" aria-labelledby="retention">
      <div className="retention__head">
        <h2 id="retention">Where people stop watching — Lawful bases, 18:40</h2>
        <span className="u-meta">4,612 viewers · the marked rules are the pinned questions</span>
      </div>
      <div className="panel retention__plot">
        <svg
          viewBox="0 0 1120 260"
          role="img"
          aria-label="Retention falls from 100 per cent to 30 per cent over eighteen minutes, with a 21 per cent drop at 6 minutes 12 and a 14 per cent drop at 13 minutes 15 — both at pinned questions."
        >
          <g stroke="var(--chart-grid)" strokeWidth="1">
            <line x1="0" y1="52" x2="1120" y2="52" />
            <line x1="0" y1="104" x2="1120" y2="104" />
            <line x1="0" y1="156" x2="1120" y2="156" />
            <line x1="0" y1="208" x2="1120" y2="208" />
          </g>
          <polygon points={`${points} 1120,260 0,260`} fill="var(--chart-fill)" />
          <polyline points={points} fill="none" stroke="var(--chart-line)" strokeWidth="3" />
          <line x1="336" y1="0" x2="336" y2="260" stroke="var(--chart-marker)" strokeWidth="3" />
          <line x1="866" y1="0" x2="866" y2="260" stroke="var(--chart-marker)" strokeWidth="3" />
          <rect x="336" y="8" width="118" height="20" fill="var(--chart-marker)" />
          <text x="344" y="23" fill="var(--color-bg)" fontSize="12" fontWeight="700">
            06:12 · −21%
          </text>
          <rect x="866" y="8" width="118" height="20" fill="var(--chart-marker)" />
          <text x="874" y="23" fill="var(--color-bg)" fontSize="12" fontWeight="700">
            13:15 · −14%
          </text>
          <text x="6" y="46" fill="var(--color-neutral-700)" fontSize="11">
            100%
          </text>
          <text x="6" y="150" fill="var(--color-neutral-700)" fontSize="11">
            60%
          </text>
          <text x="6" y="228" fill="var(--color-neutral-700)" fontSize="11">
            30%
          </text>
        </svg>
      </div>
      <p className="retention__reading">
        Both drops sit exactly where a question interrupts. A fifth of the audience leaves at 06:12
        — that is the interstitial’s placement to answer for, not the video’s.
      </p>
    </section>
  );
}
