import { useState, type ReactNode } from 'react';
import type { MessageKey } from '../shared/i18n/messages.en.ts';
import { useT } from '../shared/i18n/useLocale.ts';

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

/**
 * What each content type is called, as a key rather than a word.
 *
 * <p>Two of the four are the same in both languages — "Video" and the acronym "SCORM" — and that is
 * exactly why they go through the catalogue anyway. A list where some entries are translated and
 * some are literals is one where the next person cannot tell which kind they are adding.
 */
const typeNames: Record<ContentType, MessageKey> = {
  video: 'item.type.video',
  scorm: 'item.type.scorm',
  slides: 'item.type.slides',
  test: 'item.type.test',
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
  const t = useT();
  return (
    <div className="item">
      <div className="item__crumb">
        <button type="button" className="btn btn-ghost btn-dense" onClick={onBack}>
          <span aria-hidden="true">←</span> {t('item.back')}
        </button>
        <span className="u-meta">
          {courseTitle} · {moduleTitle} · {t('item.position', { position, of })}
        </span>
      </div>
      {/*
       * The type is named through the catalogue rather than lower-cased from the label. English
       * makes that look like the same thing; Turkish does not have a lower-case `I` that survives
       * `toLowerCase()` in every locale, and this is the exact shape of the classic Turkish-I bug.
       */}
      <p className="item__types" aria-label={t('item.is-a', { type: t(typeNames[type]) })}>
        {(Object.keys(typeNames) as ContentType[]).map((name) => (
          <span key={name} className={name === type ? 'item__type item__type--on' : 'item__type'}>
            {t(typeNames[name])}
          </span>
        ))}
        <span className="item__type item__type--note">{t('item.one-shell')}</span>
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
  const t = useT();
  const [chosen, setChosen] = useState<string | null>(null);

  return (
    <section className="interstitial" aria-labelledby="interstitial-q">
      <p className="interstitial__bar">
        <span className="u-caps">{t('interstitial.bar')}</span>
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
          <p className="u-meta">{t('interstitial.foot', { at: atSecond })}</p>
          <button
            type="button"
            className="btn btn-primary btn-block"
            disabled={chosen === null}
            onClick={() => chosen && onAnswer(chosen)}
          >
            {t('interstitial.answer')}
          </button>
          <button type="button" className="btn btn-ghost" onClick={onSkip}>
            {t('answer.leave-blank')}
          </button>
        </div>
      </div>
    </section>
  );
}
