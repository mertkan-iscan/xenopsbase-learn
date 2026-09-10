import { ChevronDown, ChevronUp } from 'lucide-react';
import { useId, type ReactNode } from 'react';
import { Button } from '../shared/design/Button.tsx';
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

/*
 * The shared look for a chooseable row -- a radio, a checkbox, an option in an interstitial. The
 * focus ring is drawn on the LABEL via `has-[:focus-visible]`, because the input inside it is what
 * receives focus and the thing a person needs to see outlined is the whole row.
 */
const optionRow = [
  'flex min-h-tap cursor-pointer items-center gap-3 rounded-lg border px-4 py-2.5 text-sm',
  'transition-colors duration-150',
  'has-[:focus-visible]:outline-2 has-[:focus-visible]:outline-offset-2 has-[:focus-visible]:outline-[var(--focus)]',
].join(' ');

const optionOn = 'border-brand bg-brand-tint font-semibold text-brand';
const optionOff = 'border-hairline hover:border-hairline-strong hover:bg-surface-muted';

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
    <fieldset className="min-w-0 border-0 p-0">
      <legend className="mb-4 font-display text-lg font-bold sm:text-xl">{stem}</legend>
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
            onChange={(raw) => onAnswer(raw.trim() === '' ? {} : { value: Number(raw) })}
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
          <p
            role="note"
            className="rounded-lg border border-dashed border-hairline-strong bg-surface-muted p-4 text-sm text-muted"
          >
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
function choicesOf(body: QuestionBody, type: string, t: (key: MessageKey) => string): Choice[] {
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
    <div className="flex flex-col gap-2">
      {choices.map((choice) => {
        const id = choice.id ?? '';
        const on = chosen.includes(id);
        return (
          <label key={id} className={`${optionRow} ${on ? optionOn : optionOff}`}>
            <input
              type={multiple ? 'checkbox' : 'radio'}
              name="answer"
              checked={on}
              className="size-4 shrink-0 accent-[var(--brand)]"
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

/*
 * The field look, repeated from `shared/design/Field.tsx` rather than imported from it. These
 * inputs cannot use `Input`: that component renders its own label above the control, and an
 * answer field in an exam is labelled by the question's `legend` or by a blank's own prompt. One
 * shared constant would be a component boundary drawn through the middle of two different jobs.
 */
const answerField = [
  'w-full rounded-lg border border-hairline bg-surface-muted px-3 text-sm text-ink',
  'transition-colors duration-150 hover:border-hairline-strong focus:border-brand focus:bg-surface',
].join(' ');

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
  // `useId` and not a literal `"answer"`. A fixed id is a duplicate id the moment two of these
  // render, and a duplicate id silently points every label at the first input.
  const id = useId();
  return (
    <div className="flex flex-col gap-1.5">
      <label className="label-caps" htmlFor={id}>
        {label}
      </label>
      {long ? (
        <textarea
          id={id}
          rows={8}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          className={`${answerField} resize-y py-2 leading-relaxed`}
        />
      ) : (
        <input
          id={id}
          inputMode={inputMode}
          value={value}
          onChange={(event) => onChange(event.target.value)}
          className={`min-h-tap ${answerField}`}
        />
      )}
    </div>
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
  const base = useId();
  return (
    <div className="flex flex-col gap-4">
      {blanks.map((blank) => {
        const id = blank.id ?? '';
        const fieldId = `${base}-${id}`;
        return (
          <div key={id} className="flex flex-col gap-1.5">
            <label className="label-caps" htmlFor={fieldId}>
              {blank.text ?? id}
            </label>
            <input
              id={fieldId}
              value={filled[id] ?? ''}
              className={`min-h-tap ${answerField}`}
              onChange={(event) => {
                const next = { ...filled, [id]: event.target.value };
                if (event.target.value.trim() === '') {
                  delete next[id];
                }
                onChange(next);
              }}
            />
          </div>
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
 *
 * <p>The arrows are drawn icons rather than the `↑` and `↓` characters they used to be. A glyph in
 * a button is at the mercy of whatever font renders it — and both are read aloud by a screen
 * reader as their Unicode name, which is why each button carries a real label naming the ITEM it
 * moves rather than the direction.
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

  const textOf = (id: string) => items.find((item) => item.id === id)?.text ?? id;

  return (
    <ol className="flex flex-col overflow-hidden rounded-lg border border-hairline">
      {current.map((id, index) => (
        <li
          key={id}
          className="flex items-center gap-3 border-b border-hairline bg-surface px-3 py-2 last:border-b-0"
        >
          <span className="w-6 shrink-0 text-center text-xs font-bold text-subtle tabular-nums">
            {index + 1}
          </span>
          <span className="min-w-0 flex-1 text-sm">{textOf(id)}</span>
          <span className="flex shrink-0 gap-1">
            <Button
              voice="secondary"
              size="sm"
              className="px-2"
              onClick={() => move(index, -1)}
              disabled={index === 0}
              aria-label={t('question.move-earlier', { item: textOf(id) })}
            >
              <ChevronUp aria-hidden="true" className="size-4" />
            </Button>
            <Button
              voice="secondary"
              size="sm"
              className="px-2"
              onClick={() => move(index, 1)}
              disabled={index === current.length - 1}
              aria-label={t('question.move-later', { item: textOf(id) })}
            >
              <ChevronDown aria-hidden="true" className="size-4" />
            </Button>
          </span>
        </li>
      ))}
    </ol>
  );
}
