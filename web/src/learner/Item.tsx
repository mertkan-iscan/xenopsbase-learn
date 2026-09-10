import { ArrowLeft } from 'lucide-react';
import { useState, type ReactNode } from 'react';
import { Button } from '../shared/design/Button.tsx';
import type { MessageKey } from '../shared/i18n/messages.en.ts';
import { useT } from '../shared/i18n/useLocale.ts';

/**
 * The item shell — one screen for every content type (T-10.3).
 *
 * <p>A video, an uploaded SCORM or cmi5 package, a set of slides and a test are five different
 * things inside one frame, and the frame is the design: a new content type must not mean a new
 * screen. {@link ItemShell} is that frame, and it knows nothing about what it is hosting beyond
 * its name.
 *
 * <p><b>WHAT THIS USED TO DO, AND WHY IT WAS WRONG.</b> Every value in this header was a literal
 * typed at the call site — `courseTitle="Data Protection 2026"`, `position={3}`, `of={6}` — so
 * every learner watching every video was told they were on step 3 of 6 of a course that may not
 * have been theirs. The lint rule that forbids English in a component did not catch it, because
 * these arrived as JSX ATTRIBUTES rather than as text between tags. They come from
 * `/api/v1/me/home` now, which is the only endpoint that knows the answer.
 */

/**
 * The five content types the product ships with (T-5.1, `BuiltInContentTypes.java`).
 *
 * <p>These are the registry's own codes and they are LOWER CASE — `video`, not `VIDEO`. The
 * OpenAPI description types `HomeNode.type` as a bare `string` because on the server it is a
 * registry lookup rather than an enum, so nothing checks this mapping at compile time; getting the
 * case wrong means every item silently renders as an unknown type.
 *
 * <p>The registry is extensible by design, so an unrecognised code is a NORMAL case rather than an
 * error: a sixth type registered on the server reaches this screen before this map knows about it,
 * and the honest answer then is "an item", not a crash and not a blank.
 */
const typeNames: Record<string, MessageKey> = {
  video: 'item.type.video',
  scorm: 'item.type.scorm',
  cmi5: 'item.type.cmi5',
  slides: 'item.type.slides',
  test: 'item.type.test',
};

export function ItemShell({
  courseTitle,
  moduleTitle,
  nodeTitle,
  position,
  of,
  type,
  onBack,
  aside,
  children,
}: {
  courseTitle: string;
  moduleTitle: string;
  nodeTitle: string;
  position: number;
  of: number;
  /** The registry code, or `null` when the content item behind the node has gone missing. */
  type: string | null | undefined;
  onBack?: () => void;
  /** The syllabus, on a screen wide enough to keep it beside the content. */
  aside?: ReactNode;
  children: ReactNode;
}) {
  const t = useT();
  const typeKey = type ? typeNames[type] : undefined;
  const typeName = typeKey ? t(typeKey) : t('item.type.unknown');

  return (
    <div className="flex flex-col gap-5">
      <div className="flex flex-col gap-3">
        <div className="flex items-center gap-2">
          {onBack ? (
            <Button voice="ghost" size="sm" onClick={onBack} className="px-2">
              <ArrowLeft aria-hidden="true" className="size-4" />
              {t('item.back')}
            </Button>
          ) : null}
        </div>

        <div className="flex flex-col gap-1">
          {/*
           * The breadcrumb is text rather than links. Every level above this node -- the course,
           * the module -- has no screen of its own: a learner navigates a course through this
           * shell and the syllabus beside it, so a link would have nowhere to go.
           */}
          <p className="text-xs text-muted">
            <span className="font-semibold text-ink">{courseTitle}</span>
            {' · '}
            {moduleTitle}
            {' · '}
            {t('item.position', { position, of })}
          </p>
          <h1 className="font-display text-xl font-bold sm:text-2xl">{nodeTitle}</h1>
          {/*
           * The type is named through the catalogue rather than lower-cased from a label. English
           * makes those look like the same thing; Turkish has no lower-case `I` that survives
           * `toLowerCase()` in every locale, and this is the exact shape of the classic Turkish-I
           * bug.
           */}
          <p className="label-caps">{t('item.is-a', { type: typeName })}</p>
        </div>
      </div>

      {/*
       * The content and the syllabus, side by side when there is room. `flex-col` first and
       * `desk:flex-row` after, so the phone layout is the one that needs no override.
       */}
      <div className="flex flex-col gap-6 desk:flex-row desk:items-start">
        <div className="flex min-w-0 flex-1 flex-col gap-5">{children}</div>
        {aside}
      </div>
    </div>
  );
}

/**
 * A question pinned to a second inside a video, taking the screen (T-5.4).
 *
 * <p><b>NOT WIRED, AND NOT FAKED.</b> This component is complete and it is not reachable from
 * {@link Watch}, because the data to fill it does not exist: `InterstitialView` carries an id, a
 * `questionId`, a second and whether it blocks — and no question text and no options
 * (docs/api-surface.md). There is no learner-facing endpoint that reads a question outside an
 * attempt, and none that records an interstitial response. It previously WAS reachable, filled
 * with an invented English question about emailing a customer list, which is the kind of placeholder
 * that survives to production because it looks finished.
 *
 * <p>So it stays here, exported and covered by its own test, because the design of the interruption
 * is a deliverable in its own right (docs/design-prompt.md asks for the item shell "showing a video
 * with an interstitial firing") and because the shape of it is what makes the two missing pieces
 * obvious rather than theoretical. Each is one endpoint.
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
    <section
      aria-labelledby="interstitial-q"
      className="card overflow-hidden p-0 shadow-float"
    >
      <p className="flex items-center justify-between gap-3 border-b border-hairline bg-surface-muted px-5 py-3">
        <span className="label-caps">{t('interstitial.bar')}</span>
        <span className="label-caps tabular-nums">{atSecond}</span>
      </p>
      <div className="flex flex-col gap-5 p-5">
        <fieldset className="min-w-0 border-0 p-0">
          <legend id="interstitial-q" className="mb-4 font-display text-lg font-bold">
            {question}
          </legend>
          <div className="flex flex-col gap-2">
            {options.map((option) => (
              <label
                key={option.id}
                className={[
                  'flex min-h-tap cursor-pointer items-center gap-3 rounded-lg border px-4 py-2 text-sm transition-colors duration-150',
                  'has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-offset-2 has-[:focus-visible]:outline-[var(--focus)]',
                  chosen === option.id
                    ? 'border-brand bg-brand-tint font-semibold text-brand'
                    : 'border-hairline hover:border-hairline-strong hover:bg-surface-muted',
                ].join(' ')}
              >
                <input
                  type="radio"
                  name="interstitial"
                  value={option.id}
                  checked={chosen === option.id}
                  onChange={() => setChosen(option.id)}
                  className="size-4 shrink-0 accent-[var(--brand)]"
                />
                {option.text}
              </label>
            ))}
          </div>
        </fieldset>
        <div className="flex flex-col gap-3">
          <p className="text-sm text-muted">{t('interstitial.foot', { at: atSecond })}</p>
          <Button
            voice="primary"
            disabled={chosen === null}
            onClick={() => chosen && onAnswer(chosen)}
          >
            {t('interstitial.answer')}
          </Button>
          <Button voice="ghost" onClick={onSkip}>
            {t('answer.leave-blank')}
          </Button>
        </div>
      </div>
    </section>
  );
}
