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
  return (
    <div className="compliance-page">
      <NotEnforcedYet />
      <Empty title="There is no reporting to show.">
        <p>
          The <code>reporting</code> service accepts telemetry and cannot answer a query yet. The
          rollups this screen is built on are T-7.1 to T-7.7.
        </p>
        <p className="u-meta">
          Nothing is hidden here and nothing is loading. This screen had sample figures for a
          company of 5,182 people; they were invented, so they are gone.
        </p>
      </Empty>
    </div>
  );
}
