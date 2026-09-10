import type { ReactNode } from 'react';
import type { MessageKey } from '../shared/i18n/messages.en.ts';
import { useT } from '../shared/i18n/useLocale.ts';

/**
 * One question, rendered from the body the server served (T-6.3).
 *
 * <p>Ten types, and the shape of each body is assessment's `QuestionTypes`. This component knows
 * only how to draw them and how to shape a response; what a response MEANS is the server's, and
 * nothing here scores anything.
 *
 * <p><b>An empty response is an unanswered question, not an error.</b> That is the API's rule and
 * it is honoured literally: every type below can produce an empty answer, and "I don't know" is
 * reachable from every one of them. Refusing it would turn a shrug into a failed request in the
 * middle of an exam.
 *
 * <p><b>The option order is the server's.</b> `optionOrder` records what this learner was shown
 * (T-6.5); re-sorting here would show them something different on a resume, and would make the
 * form's record of what they saw a lie.
 */
export type QuestionBody = {
  type?: string;
  stem?: string;
  options?: Record<string, unknown>;
};

/** The shapes a response takes, one per type. Mirrors assessment's `QuestionTypes`. */
export type Response = Record<string, unknown>;

type Choice = { id?: string; text?: string };

export function Question({
  body,
  optionOrder,
  answer,
  onAnswer,
}: {
  body: QuestionBody;
  optionOrder: Record<string, string[]>;
  answer: Response | undefined;
  onAnswer: (response: Response) => void;
}) {
  const t = useT();
  const type = body.type ?? 'unknown';
  const stem = body.stem ?? '';

  return (
    <fieldset className="question">
      <legend className="u-display question__stem">{stem}</legend>
      {renderBody()}
    </fieldset>
  );

  function renderBody(): ReactNode {
    switch (type) {
      case 'single-choice':
      case 'true-false':
        return (
          <Choices
            choices={ordered('choices', choicesOf(body, type, t))}
            chosen={(answer?.chosen as string[]) ?? []}
            multiple={false}
            onChange={(chosen) => onAnswer({ chosen })}
          />
        );
      case 'multiple-choice':
        return (
          <Choices
            choices={ordered('choices', choicesOf(body, type, t))}
            chosen={(answer?.chosen as string[]) ?? []}
            multiple
            onChange={(chosen) => onAnswer({ chosen })}
          />
        );
      case 'numeric':
        return (
          <Free
            label={t('question.your-answer')}
            value={answer?.value === undefined ? '' : String(answer.value)}
            inputMode="decimal"
            onChange={(raw) =>
              onAnswer(raw.trim() === '' ? {} : { value: Number(raw) })
            }
          />
        );
      case 'essay':
        return (
          <Free
            label={t('question.your-answer')}
            value={(answer?.text as string) ?? ''}
            long
            onChange={(text) => onAnswer(text.trim() === '' ? {} : { text })}
          />
        );
      case 'fill-in':
        return (
          <Blanks
            blanks={ordered('blanks', (body.options?.blanks as Choice[]) ?? [])}
            filled={(answer?.filled as Record<string, string>) ?? {}}
            onChange={(filled) => onAnswer(Object.keys(filled).length ? { filled } : {})}
          />
        );
      case 'ordering':
        return (
          <Ordering
            items={ordered('items', (body.options?.items as Choice[]) ?? [])}
            order={(answer?.order as string[]) ?? []}
            onChange={(order) => onAnswer(order.length ? { order } : {})}
          />
        );
      default:
        // matching, hotspot and file-upload need an interaction this screen does not have yet.
        // Saying so beats a control that looks answerable and submits nothing.
        return (
          <p className="question__unsupported" role="note">
            {t('question.unsupported', { type })}
          </p>
        );
    }
  }

  /** The server's order for a field, falling back to the body's own when it shuffled nothing. */
  function ordered(field: string, all: Choice[]): Choice[] {
    const order = optionOrder[field];
    if (!order || order.length === 0) {
      return all;
    }
    return order
      .map((id) => all.find((one) => one.id === id))
      .filter((one): one is Choice => one !== undefined);
  }
}

/** true-false has no choices in the body: they are ours, and they are the same two every time. */
function choicesOf(
  body: QuestionBody,
  type: string,
  t: (key: MessageKey) => string,
): Choice[] {
  if (type === 'true-false') {
    return [
      { id: 'true', text: t('question.true') },
      { id: 'false', text: t('question.false') },
    ];
  }
  return (body.options?.choices as Choice[]) ?? [];
}

function Choices({
  choices,
  chosen,
  multiple,
  onChange,
}: {
  choices: Choice[];
  chosen: string[];
  multiple: boolean;
  onChange: (chosen: string[]) => void;
}) {
  return (
    <div className="interstitial__options">
      {choices.map((choice) => {
        const id = choice.id ?? '';
        const on = chosen.includes(id);
        return (
          <label key={id} className={on ? 'option option--on' : 'option'}>
            <input
              type={multiple ? 'checkbox' : 'radio'}
              name="answer"
              checked={on}
              // ON CLICK, NOT ON CHANGE, and a test found out why: clicking a radio that is
              // already checked fires no change event, so "choose it again to clear it" silently
              // did nothing. `onClick` fires either way. React wants an onChange beside a
              // controlled `checked`, so there is one and it is deliberately empty.
              onChange={() => {}}
              onClick={() => {
                if (multiple) {
                  onChange(on ? chosen.filter((one) => one !== id) : [...chosen, id]);
                } else {
                  // Choosing the same one again clears it, which is how a single-choice question
                  // gets back to unanswered without a separate control.
                  onChange(on ? [] : [id]);
                }
              }}
            />
            {choice.text}
          </label>
        );
      })}
    </div>
  );
}

function Free({
  label,
  value,
  long = false,
  inputMode,
  onChange,
}: {
  label: string;
  value: string;
  long?: boolean;
  inputMode?: 'decimal';
  onChange: (value: string) => void;
}) {
  return (
    <p className="question__free">
      <label className="u-caps" htmlFor="answer">
        {label}
      </label>
      {long ? (
        <textarea
          id="answer"
          className="input question__long"
          rows={8}
          value={value}
          onChange={(event) => onChange(event.target.value)}
        />
      ) : (
        <input
          id="answer"
          className="input"
          inputMode={inputMode}
          value={value}
          onChange={(event) => onChange(event.target.value)}
        />
      )}
    </p>
  );
}

function Blanks({
  blanks,
  filled,
  onChange,
}: {
  blanks: Choice[];
  filled: Record<string, string>;
  onChange: (filled: Record<string, string>) => void;
}) {
  return (
    <div className="question__blanks">
      {blanks.map((blank) => {
        const id = blank.id ?? '';
        return (
          <p key={id} className="question__free">
            <label className="u-caps" htmlFor={`blank-${id}`}>
              {blank.text ?? id}
            </label>
            <input
              id={`blank-${id}`}
              className="input"
              value={filled[id] ?? ''}
              onChange={(event) => {
                const next = { ...filled, [id]: event.target.value };
                if (event.target.value.trim() === '') {
                  delete next[id];
                }
                onChange(next);
              }}
            />
          </p>
        );
      })}
    </div>
  );
}

/**
 * Ordering, by moving one item at a time.
 *
 * <p>Buttons rather than drag: T-10.8 requires every interaction to be keyboard operable, and a
 * drag list that is only usable with a mouse fails that on the screen where it matters most.
 */
function Ordering({
  items,
  order,
  onChange,
}: {
  items: Choice[];
  order: string[];
  onChange: (order: string[]) => void;
}) {
  const t = useT();
  const current = order.length ? order : items.map((item) => item.id ?? '');

  function move(index: number, by: number) {
    const next = [...current];
    const to = index + by;
    if (to < 0 || to >= next.length) {
      return;
    }
    [next[index], next[to]] = [next[to] as string, next[index] as string];
    onChange(next);
  }

  return (
    <ol className="question__ordering panel">
      {current.map((id, index) => (
        <li key={id}>
          <span>{items.find((item) => item.id === id)?.text ?? id}</span>
          <span className="question__ordering-moves">
            <button
              type="button"
              className="btn btn-secondary btn-dense"
              onClick={() => move(index, -1)}
              disabled={index === 0}
              aria-label={t('question.move-earlier', {
                item: items.find((item) => item.id === id)?.text ?? id,
              })}
            >
              ↑
            </button>
            <button
              type="button"
              className="btn btn-secondary btn-dense"
              onClick={() => move(index, 1)}
              disabled={index === current.length - 1}
              aria-label={t('question.move-later', {
                item: items.find((item) => item.id === id)?.text ?? id,
              })}
            >
              ↓
            </button>
          </span>
        </li>
      ))}
    </ol>
  );
}
