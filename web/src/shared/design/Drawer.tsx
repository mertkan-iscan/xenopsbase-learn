/**
 * The navigation drawer, and what a drawer owes a keyboard.
 *
 * <p>NOT A NATIVE `<dialog>`, and the reason is a test rather than a preference: jsdom 29 ships
 * neither `showModal` nor `inert`, so a native dialog is untestable in this suite — the shell's own
 * accessibility test (T-10.8) renders this component, and a control that throws in jsdom cannot be
 * asserted on at all. So the four things `showModal` would have given for free are done here,
 * explicitly, and each one is a bug that has shipped in somebody's drawer:
 *
 * <ol>
 *   <li><b>Escape closes it.</b> A panel with no keyboard exit is a trap.</li>
 *   <li><b>Focus moves in on open</b> — to the panel, not to the first link, so a screen reader
 *       reads the drawer's name before its contents.</li>
 *   <li><b>Focus returns to the trigger on close</b>, and the trigger is PASSED IN rather than
 *       discovered. Reading `document.activeElement` in this component's effect looks equivalent
 *       and is not: the caller applies `inert` to the page in the same commit that mounts this
 *       drawer, `inert` blurs whatever was focused inside it, and effects run after the DOM is
 *       mutated — so by the time this component looked, the answer was already `<body>`. The
 *       drawer closed and focus went to the top of the document. Found by opening it in a
 *       browser; jsdom implements neither `inert` nor its blurring, so no test could have caught
 *       it.</li>
 *   <li><b>The page behind it is `inert`</b>, applied by the caller — see {@link Shell}. That is
 *       what keeps Tab inside the drawer without a hand-rolled focus cycle, and it is why there
 *       isn't one here: a manual trap and a real `inert` disagree at the edges, and the browser is
 *       the one that is right.</li>
 * </ol>
 *
 * <p>Rendered only when open, so nothing behind the backdrop is in the tab order even in a browser
 * that does not honour `inert`.
 */
import { useEffect, useRef, type ReactNode, type RefObject } from 'react';

export function Drawer({
  label,
  onClose,
  returnFocusTo,
  children,
}: {
  /** Names the drawer for a screen reader — it is what gets read when focus lands. */
  label: string;
  onClose: () => void;
  /** The control that opened it. See the fourth point above for why this is not optional. */
  returnFocusTo: RefObject<HTMLElement | null>;
  children: ReactNode;
}) {
  const panel = useRef<HTMLDivElement>(null);

  useEffect(() => {
    panel.current?.focus();

    // Captured here, not in the cleanup. A ref read during cleanup is a ref read at an unknown
    // later time, which lint flags and which would be a real bug if the trigger were swapped while
    // the drawer was open. The menu button is the same element for as long as this is mounted.
    const back = returnFocusTo.current;

    return () => {
      // `isConnected` rather than a bare call: a route change while the drawer was open can have
      // removed the trigger from the document, and focusing a detached node silently does nothing
      // rather than throwing -- which would leave focus lost with no sign of why.
      if (back?.isConnected) {
        back.focus();
      }
    };
  }, [returnFocusTo]);

  useEffect(() => {
    function onKeyDown(event: KeyboardEvent) {
      if (event.key === 'Escape') {
        // Stopped as well as handled: an Escape that also reaches a screen underneath would close
        // the drawer AND cancel whatever that screen thought was in progress.
        event.stopPropagation();
        onClose();
      }
    }
    // On the document rather than the panel: focus may legitimately be on the backdrop or on
    // nothing at all, and Escape has to work from either.
    document.addEventListener('keydown', onKeyDown);
    return () => document.removeEventListener('keydown', onKeyDown);
  }, [onClose]);

  return (
    <div className="fixed inset-0 z-50 desk:hidden">
      {/*
       * The backdrop is a plain div and not a button, with the close affordance duplicated as a
       * real button inside the panel. A full-screen button is announced as one enormous control,
       * and "close" is already reachable by Escape and by a labelled button.
       */}
      <div
        onClick={onClose}
        className="absolute inset-0 bg-ink/40 motion-safe:animate-[fade_180ms_ease-out]"
        aria-hidden="true"
      />
      <div
        ref={panel}
        role="dialog"
        aria-modal="true"
        aria-label={label}
        tabIndex={-1}
        className="glass absolute inset-y-0 start-0 flex w-[min(20rem,85vw)] flex-col border-e shadow-float focus:outline-none motion-safe:animate-[drawer-in_220ms_cubic-bezier(0.22,1,0.36,1)]"
      >
        {children}
      </div>
    </div>
  );
}
