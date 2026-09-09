import { useState } from 'react';
import { StateChip } from '../shared/design/State.tsx';

/**
 * Authoring — a test in the order, or pinned to a second (T-10.5, T-5.4).
 *
 * <p>The interaction that carries the product. A test can sit between two nodes or be pinned
 * inside a video; underneath they are ONE MODEL, and this screen is where that either becomes
 * obvious or becomes two confusing features. The order list on the left shows the pinned test as
 * an indented child of the video it lives in, so the relationship is visible without a second
 * screen explaining it.
 *
 * <p><b>There is no field to type a second into.</b> The marker is dragged, or nudged with the
 * arrow keys, against a frame that updates — because a number typed into a box is a number nobody
 * checked against the video, and the first person to check is a learner interrupted mid-sentence.
 * Keyboard operation is not the fallback here; it is the precise one.
 *
 * <p>NO AUTHORING ENDPOINT YET (T-10.5 is open). The content below is a fixture. What is real is
 * the arithmetic: `asks > matches` is the check that has to happen before a save, and it is
 * written here rather than waited for.
 */
const DURATION = 18 * 60 + 40;

function clock(seconds: number) {
  return `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`;
}

export function Authoring() {
  const [pinned, setPinned] = useState(6 * 60 + 12);

  // Arrow keys move a second at a time; with shift, ten. A marker that can only be dragged is a
  // marker that cannot be placed exactly, and "exactly" is the entire feature.
  function nudge(event: React.KeyboardEvent<HTMLDivElement>) {
    const step = event.shiftKey ? 10 : 1;
    if (event.key === 'ArrowLeft') {
      event.preventDefault();
      setPinned((at) => Math.max(0, at - step));
    }
    if (event.key === 'ArrowRight') {
      event.preventDefault();
      setPinned((at) => Math.min(DURATION, at + step));
    }
  }

  const asks = 20;
  const matches = 12;

  return (
    <div className="authoring panel">
      <div className="authoring__head">
        <div className="authoring__title">
          {/* Both states, always, so nobody edits a live course by accident. */}
          <StateChip state="draft" />
          <span className="authoring__course">Data Protection 2026 · Module 2</span>
          <span className="u-meta">Published version 4 is what learners have now</span>
        </div>
        <div className="authoring__actions">
          <button type="button" className="btn btn-secondary">
            Preview as learner
          </button>
          <button type="button" className="btn btn-primary">
            Publish version 5
          </button>
        </div>
      </div>

      <div className="authoring__split">
        <section className="authoring__order" aria-labelledby="order">
          <h2 id="order" className="u-caps">
            Order
          </h2>
          <ol className="panel authoring__nodes">
            <li className="node">
              1 · Welcome <span className="u-meta">slides</span>
            </li>
            <li className="node node--on">
              2 · Lawful bases <span className="node__meta">video · 18:40</span>
            </li>
            <li className="node node--pinned">↳ pinned at {clock(pinned)} · 1 question</li>
            <li className="node">
              3 · Handling data <span className="u-meta">test · in the order</span>
            </li>
            <li className="node">
              4 · Wrap-up <span className="u-meta">SCORM</span>
            </li>
          </ol>
          <p className="u-meta">
            Both tests above are the same object. One sits between nodes; one is pinned to a
            second. Drag either into the other place.
          </p>
        </section>

        <section className="authoring__timeline" aria-labelledby="timeline">
          <h2 id="timeline" className="u-caps">
            Timeline
          </h2>
          <div className="authoring__frame">
            <div className="authoring__still">Frame at {clock(pinned)}</div>
            <div className="authoring__playhead">
              <span className="u-caps">Playhead</span>
              <p className="u-display authoring__at">{clock(pinned)}</p>
              <p className="u-meta">
                Drag the marker or nudge with ← →. There is no field to type a number into.
              </p>
            </div>
          </div>

          {/*
           * A slider in the accessibility tree, whatever it looks like. `aria-valuetext` is what
           * makes it usable: without it a screen reader reads "372", and nobody places a question
           * at three hundred and seventy-two.
           */}
          <div
            className="track"
            role="slider"
            tabIndex={0}
            aria-label="Position of the pinned question"
            aria-valuemin={0}
            aria-valuemax={DURATION}
            aria-valuenow={pinned}
            aria-valuetext={`${clock(pinned)} of ${clock(DURATION)}`}
            onKeyDown={nudge}
          >
            <span className="track__played" style={{ width: `${(pinned / DURATION) * 100}%` }} />
            <span className="track__marker" style={{ left: `${(pinned / DURATION) * 100}%` }}>
              <span className="track__flag">{clock(pinned)}</span>
            </span>
            <span className="track__other" style={{ left: `${((13 * 60 + 15) / DURATION) * 100}%` }}>
              <span className="u-meta track__other-label">13:15 · 1 question</span>
            </span>
          </div>
          <p className="track__scale u-meta">
            <span>00:00</span>
            <span>04:40</span>
            <span>09:20</span>
            <span>14:00</span>
            <span>18:40</span>
          </p>

          <div className="gate">
            <h3 className="u-caps">Gate on node 3 — built from choices</h3>
            <p className="gate__rules">
              <span className="tag">Requires</span>
              <span className="tag">Lawful bases · watched 90%</span>
              <span className="tag">and</span>
              <span className="tag">Pinned question · answered</span>
              <button type="button" className="btn btn-ghost btn-dense">
                + condition
              </button>
            </p>
            {/*
             * THE SENTENCE, AS IT IS BUILT. The author is choosing conditions; the learner will
             * read one sentence. Showing it here is what stops a gate from being correct and
             * incomprehensible at the same time.
             */}
            <div className="gate__sentence">
              <span className="u-caps">The learner will read</span>
              <p>
                “Unlocks when you have watched Lawful bases to the end and answered the question
                inside it.”
              </p>
            </div>
          </div>
        </section>

        <aside className="authoring__pinned" aria-labelledby="pinned-question">
          <h2 id="pinned-question" className="u-caps">
            Pinned question · {clock(pinned)}
          </h2>
          <div className="panel authoring__preview">
            <span className="u-caps">Live learner preview · single-choice</span>
            <p className="authoring__q">
              A colleague asks you to email a customer list to their personal address. What do you
              do?
            </p>
            <ul className="authoring__options">
              <li>Send it — they are a colleague</li>
              <li className="authoring__correct">Refuse and refer them to the data owner ✓</li>
              <li>Send it with the names removed</li>
            </ul>
          </div>
          <div className="authoring__pool">
            <span className="u-caps">Drawn from a pool</span>
            <p className="authoring__tags">
              <span className="tag tag-outline">tag: gdpr</span>
              <span className="tag tag-outline">difficulty: 2</span>
            </p>
            {/*
             * BEFORE SAVING, NOT AT SITTING. A section asking for twenty from a pool of twelve is a
             * broken exam, and the only person who currently finds out is a learner halfway
             * through one. role="alert" because it is a refusal, not a hint.
             */}
            {asks > matches ? (
              <p className="authoring__short" role="alert">
                <strong>
                  Asks for {asks} · pool matches {matches}.
                </strong>{' '}
                {asks - matches} questions would be missing at sitting. Widen the tags or lower the
                count before you save.
              </p>
            ) : null}
          </div>
        </aside>
      </div>
    </div>
  );
}
