/**
 * Form controls that cannot be built without their label.
 *
 * <p>THAT IS THE WHOLE DESIGN DECISION HERE. `label` is a required prop on every control in this
 * file, and each one generates its own id and wires `htmlFor` itself. An unlabelled input is the
 * single most common accessibility violation in an admin console, it is invisible to everyone who
 * does not use a screen reader, and the build gate (T-10.8) catches it only on the screens that
 * have a test. Making the label impossible to omit catches it everywhere.
 *
 * <p>`hidden-label` exists for the one honest exception — a search box whose purpose is obvious
 * from the icon and the placeholder beside it — and it still renders the label, to the
 * accessibility tree, rather than dropping it.
 */
import { useId, type InputHTMLAttributes, type ReactNode, type SelectHTMLAttributes } from 'react';

/*
 * The field look, in one place: a sunken surface rather than a bordered box, so a form reads as
 * something you write INTO rather than as a stack of outlines. The focus ring is the accent here
 * and only here — see `--focus` in styles.css for why it is not the accent everywhere.
 */
const control = [
  'w-full rounded-lg border border-hairline bg-surface-muted px-3 text-sm text-ink',
  'placeholder:text-subtle',
  'transition-colors duration-150 hover:border-hairline-strong',
  'focus:border-brand focus:bg-surface',
  'disabled:cursor-not-allowed disabled:opacity-60',
].join(' ');

/**
 * The field look, without the label machinery.
 *
 * <p>For the call sites that cannot use {@link Input}: a cell in a table whose column header is
 * already the label, a control inside a `fieldset` whose `legend` names it, or a form that writes
 * its own label because the sentence is longer than a caption. Exported so those do not each
 * invent a border.
 *
 * <p>`dense` is the console's size — 36px rather than the 44px floor a learner's thumb needs. An
 * administrator is on a desktop with a pointer, and a form of 44px rows is a form they scroll.
 */
export function fieldClasses(dense = false): string {
  return `${dense ? 'min-h-9' : 'min-h-tap'} ${control}`;
}

function Shell({
  id,
  label,
  hint,
  hintId,
  hideLabel,
  children,
}: {
  id: string;
  label: string;
  hint?: string | undefined;
  hintId: string;
  hideLabel?: boolean | undefined;
  children: ReactNode;
}) {
  return (
    <div className="flex flex-col gap-1.5">
      <label htmlFor={id} className={hideLabel ? 'sr-only' : 'label-caps'}>
        {label}
      </label>
      {children}
      {hint ? (
        <p id={hintId} className="text-xs text-muted">
          {hint}
        </p>
      ) : null}
    </div>
  );
}

export function Input({
  label,
  hint,
  hideLabel,
  className = '',
  ...rest
}: {
  label: string;
  /** A sentence explaining the field, wired to it with `aria-describedby`. */
  hint?: string;
  hideLabel?: boolean;
} & InputHTMLAttributes<HTMLInputElement>) {
  const id = useId();
  const hintId = `${id}-hint`;
  return (
    <Shell id={id} label={label} hint={hint} hintId={hintId} hideLabel={hideLabel}>
      <input
        id={id}
        aria-describedby={hint ? hintId : undefined}
        className={`min-h-tap ${control} ${className}`}
        {...rest}
      />
    </Shell>
  );
}

export function Select({
  label,
  hint,
  hideLabel,
  className = '',
  children,
  ...rest
}: {
  label: string;
  hint?: string;
  hideLabel?: boolean;
  children: ReactNode;
} & SelectHTMLAttributes<HTMLSelectElement>) {
  const id = useId();
  const hintId = `${id}-hint`;
  return (
    <Shell id={id} label={label} hint={hint} hintId={hintId} hideLabel={hideLabel}>
      <select
        id={id}
        aria-describedby={hint ? hintId : undefined}
        className={`min-h-tap ${control} ${className}`}
        {...rest}
      >
        {children}
      </select>
    </Shell>
  );
}

/**
 * A row of choices that behaves like one control.
 *
 * <p>A `fieldset` and a `legend`, not a div and a heading: a group of radio buttons or filter
 * chips is announced as a group with a name, and that name is the question being asked. Without
 * it, a screen reader reads five options and never says what they are options for.
 */
export function FieldGroup({
  legend,
  hideLegend,
  children,
}: {
  legend: string;
  hideLegend?: boolean;
  children: ReactNode;
}) {
  return (
    <fieldset className="min-w-0 border-0 p-0">
      <legend className={hideLegend ? 'sr-only' : 'label-caps mb-2'}>{legend}</legend>
      {children}
    </fieldset>
  );
}
