import { useState, type ReactNode } from 'react';

/**
 * The item shell — one screen for every content type (T-10.3).
 *
 * <p>A video, an uploaded SCORM package, a set of slides and a test are four different things
 * inside one frame, and the frame is the design: a new content type must not mean a new screen.
 * {@link ItemShell} is that frame, and it knows nothing about what it is hosting beyond its name.
 *
 * <p>The type strip is not navigation. It says which of the four is on screen, because a learner
 * halfway through a course is entitled to know whether the thing that just failed to load was a
 * video or a package.
 */
export type ContentType = 'video' | 'scorm' | 'slides' | 'test';

const typeNames: Record<ContentType, string> = {
  video: 'Video',
  scorm: 'SCORM',
  slides: 'Slides',
  test: 'Test',
};

export function ItemShell({
  courseTitle,
  moduleTitle,
  position,
  of,
  type,
  onBack,
  children,
}: {
  courseTitle: string;
  moduleTitle: string;
  position: number;
  of: number;
  type: ContentType;
  onBack?: () => void;
  children: ReactNode;
}) {
  return (
    <div className="item">
      <div className="item__crumb">
        <button type="button" className="btn btn-ghost btn-dense" onClick={onBack}>
          <span aria-hidden="true">←</span> Back
        </button>
        <span className="u-meta">
          {courseTitle} · {moduleTitle} · {position} of {of}
        </span>
      </div>
      <p className="item__types" aria-label={`This item is a ${typeNames[type].toLowerCase()}`}>
        {(Object.keys(typeNames) as ContentType[]).map((name) => (
          <span key={name} className={name === type ? 'item__type item__type--on' : 'item__type'}>
            {typeNames[name]}
          </span>
        ))}
        <span className="item__type item__type--note">one shell</span>
      </p>
      {children}
    </div>
  );
}

/**
 * A question pinned to a second inside a video, taking the screen (T-5.4).
 *
 * <p>Three things here are decisions rather than layout.
 *
 * <p><b>The video's place is held on the server.</b> The sentence saying so is on the screen
 * because it is the answer to what a learner is actually worried about when a video stops: that
 * closing the tab costs them the six minutes they just watched. It does not.
 *
 * <p><b>Leaving it blank is an option, and it is a button.</b> An empty response is an unanswered
 * question, not an error — the API is explicit about that, because refusing it would turn "I do
 * not know" into a failed request in the middle of an exam. A design that only offers "answer and
 * continue" has quietly made the API's tolerance unreachable.
 *
 * <p><b>The options are radios in a real fieldset.</b> Not divs with a click handler: keyboard
 * operation of every interaction is a build gate here (T-10.8), and arrow-key movement between
 * options is something the platform already does correctly and we would have to reimplement badly.
 */
export function Interstitial({
  question,
  options,
  atSecond,
  onAnswer,
  onSkip,
}: {
  question: string;
  options: { id: string; text: string }[];
  atSecond: string;
  onAnswer: (chosen: string) => void;
  onSkip: () => void;
}) {
  const [chosen, setChosen] = useState<string | null>(null);

  return (
    <section className="interstitial" aria-labelledby="interstitial-q">
      <p className="interstitial__bar">
        <span className="u-caps">Question in the video · 1 of 1</span>
        <span className="u-caps">{atSecond}</span>
      </p>
      <div className="interstitial__body">
        <fieldset className="interstitial__fieldset">
          <legend id="interstitial-q" className="u-display interstitial__question">
            {question}
          </legend>
          <div className="interstitial__options">
            {options.map((option) => (
              <label
                key={option.id}
                className={chosen === option.id ? 'option option--on' : 'option'}
              >
                <input
                  type="radio"
                  name="interstitial"
                  value={option.id}
                  checked={chosen === option.id}
                  onChange={() => setChosen(option.id)}
                />
                {option.text}
              </label>
            ))}
          </div>
        </fieldset>
        <div className="interstitial__foot">
          <p className="u-meta">
            Answer to carry on. The video resumes at {atSecond} — your place is held on the server,
            not in this tab.
          </p>
          <button
            type="button"
            className="btn btn-primary btn-block"
            disabled={chosen === null}
            onClick={() => chosen && onAnswer(chosen)}
          >
            Answer and continue
          </button>
          <button type="button" className="btn btn-ghost" onClick={onSkip}>
            I don’t know — leave it blank
          </button>
        </div>
      </div>
    </section>
  );
}
