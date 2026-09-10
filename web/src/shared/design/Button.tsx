/**
 * The one button, in four voices and two densities.
 *
 * <p>WHY THE CLASSES ARE EXPORTED SEPARATELY. Half the things that look like buttons in this
 * product are links — `NavLink`, `Link`, and an `<a>` to a download the gateway signs — and a
 * polymorphic `as` prop that accepts either is a component whose type signature nobody can read.
 * {@link buttonClasses} lets a link borrow the look without this file learning about the router.
 *
 * <p>THE HEIGHT IS A CONFORMANCE FLOOR, NOT A LOOK. `md` is `min-h-tap`, which is 44px
 * (docs/design-prompt.md): a learner is assumed to be on a mid-range phone, and a control smaller
 * than a fingertip is a control they miss. `sm` exists for the console, where an administrator is
 * on a desktop with a pointer and spends six hours in a dense table — there, 44px rows would mean
 * scrolling past half the information on the screen.
 */
import type { ButtonHTMLAttributes, ReactNode, Ref } from 'react';

export type ButtonVoice = 'primary' | 'secondary' | 'ghost' | 'danger';
export type ButtonSize = 'md' | 'sm';

/*
 * `shadow-glow` appears on the primary voice and nowhere else in this system. That is what makes
 * it mean "this is the action" rather than "this is a button" — a glow on every control is a glow
 * on nothing.
 */
const voices: Record<ButtonVoice, string> = {
  primary:
    'border-transparent bg-brand text-brand-on shadow-glow hover:bg-brand-hover active:bg-brand-active',
  secondary: 'border-hairline bg-surface text-ink shadow-soft hover:bg-surface-muted',
  ghost: 'border-transparent bg-transparent text-muted hover:bg-surface-muted hover:text-ink',
  danger: 'border-overdue-edge bg-overdue-bg text-overdue-fg hover:border-overdue-fg',
};

const sizes: Record<ButtonSize, string> = {
  md: 'min-h-tap px-4 text-sm',
  sm: 'min-h-9 px-3 text-[0.8125rem]',
};

/**
 * The look, without the element. For a `Link`, a `NavLink`, or an anchor.
 *
 * <p>`disabled:` is included even here: an anchor cannot be disabled, but `aria-disabled` with
 * these classes is how a link that is not currently available is drawn, and having one source for
 * that means the two cannot diverge.
 */
export function buttonClasses(voice: ButtonVoice = 'secondary', size: ButtonSize = 'md'): string {
  return [
    'inline-flex items-center justify-center gap-2 rounded-lg border font-semibold',
    // 150ms and colours only. Nothing here moves or scales: a button that jumps under a fingertip
    // on a slow phone reads as a misfire rather than as polish.
    'transition-colors duration-150',
    'disabled:pointer-events-none disabled:opacity-50 aria-disabled:pointer-events-none aria-disabled:opacity-50',
    voices[voice],
    sizes[size],
  ].join(' ');
}

export function Button({
  voice = 'secondary',
  size = 'md',
  className = '',
  children,
  ref,
  ...rest
}: {
  voice?: ButtonVoice;
  size?: ButtonSize;
  children: ReactNode;
  /*
   * Declared rather than inherited. React 19 treats `ref` as an ordinary prop on a function
   * component, but it is not part of `ButtonHTMLAttributes`, so without this line a caller passing
   * one is a type error and the ref silently never reaches the element.
   *
   * The caller that needs it is the shell's menu button: a drawer has to give focus BACK to the
   * control that opened it, and it cannot discover that control by reading `document.activeElement`
   * -- see Drawer.tsx.
   */
  ref?: Ref<HTMLButtonElement>;
} & ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    // `type` defaults to "submit" in HTML, which is the wrong default for most of this product:
    // a button inside a form that was meant to open a drawer submitting it instead is a bug that
    // only appears once there is a form around it. Overridable, and explicit here.
    <button
      type="button"
      ref={ref}
      className={`${buttonClasses(voice, size)} ${className}`}
      {...rest}
    >
      {children}
    </button>
  );
}
