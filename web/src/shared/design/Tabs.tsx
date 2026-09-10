/**
 * Tabs, with the keyboard behaviour the pattern actually owes.
 *
 * <p>WHY THE PANEL IS PASSED IN RATHER THAN WIRED UP BY THE CALLER. A tab list is four ARIA
 * relationships — `aria-selected`, `aria-controls`, `aria-labelledby`, and a roving tab index —
 * and every one of them is invisible when it is wrong. A component that renders the list and
 * leaves the caller to render the panel is a component whose accessibility depends on six call
 * sites remembering six ids. This one owns both halves, so there is one place for that to be
 * right.
 *
 * <p>ARROW KEYS MOVE BETWEEN TABS, AND TAB LEAVES THE LIST. That is the whole point of the roving
 * tab index below: without it, a keyboard user tabs through every tab to reach the panel, which on
 * the item shell means three presses to get past a control strip they were not aiming for.
 * Activation follows focus, which is correct here because every panel is already loaded — there is
 * nothing to fetch, so there is no cost to selecting as you arrow past.
 */
import { useId, useRef, type KeyboardEvent, type ReactNode } from 'react';

export type Tab<Id extends string> = {
  id: Id;
  label: string;
  panel: ReactNode;
};

export function Tabs<Id extends string>({
  label,
  tabs,
  active,
  onChange,
}: {
  /** Names the tab list for a screen reader: "course material", not "tabs". */
  label: string;
  tabs: Tab<Id>[];
  active: Id;
  onChange: (id: Id) => void;
}) {
  const base = useId();
  const list = useRef<HTMLDivElement>(null);

  const tabId = (id: Id) => `${base}-tab-${id}`;
  const panelId = (id: Id) => `${base}-panel-${id}`;

  function onKeyDown(event: KeyboardEvent<HTMLDivElement>) {
    const keys = ['ArrowRight', 'ArrowLeft', 'Home', 'End'];
    if (!keys.includes(event.key)) {
      return;
    }
    const at = tabs.findIndex((tab) => tab.id === active);
    // `ArrowRight` means "the next tab in reading order", which in a right-to-left layout is the
    // one to the LEFT. Reading the direction off the element rather than assuming it is what makes
    // the RTL layout work rather than merely look translated.
    const rtl = list.current ? getComputedStyle(list.current).direction === 'rtl' : false;
    const forward = rtl ? 'ArrowLeft' : 'ArrowRight';
    const next =
      event.key === 'Home'
        ? 0
        : event.key === 'End'
          ? tabs.length - 1
          : // Wrapping, which the pattern expects: arrowing past the last tab returns to the first.
            (at + (event.key === forward ? 1 : -1) + tabs.length) % tabs.length;

    event.preventDefault();
    const chosen = tabs[next];
    if (chosen) {
      onChange(chosen.id);
      // Focus has to follow the selection, or the roving tab index leaves focus on an element that
      // is now `tabIndex={-1}` and the next arrow press does nothing.
      list.current?.querySelector<HTMLButtonElement>(`#${CSS.escape(tabId(chosen.id))}`)?.focus();
    }
  }

  const current = tabs.find((tab) => tab.id === active) ?? tabs[0];

  return (
    <div>
      <div
        ref={list}
        role="tablist"
        aria-label={label}
        onKeyDown={onKeyDown}
        className="scroll-x -mb-px flex gap-1 border-b border-hairline"
      >
        {tabs.map((tab) => {
          const selected = tab.id === active;
          return (
            <button
              key={tab.id}
              type="button"
              role="tab"
              id={tabId(tab.id)}
              aria-selected={selected}
              aria-controls={panelId(tab.id)}
              tabIndex={selected ? 0 : -1}
              onClick={() => onChange(tab.id)}
              className={[
                'min-h-tap shrink-0 border-b-2 px-4 text-sm font-semibold whitespace-nowrap transition-colors duration-150',
                selected
                  ? 'border-brand text-brand'
                  : 'border-transparent text-muted hover:border-hairline-strong hover:text-ink',
              ].join(' ')}
            >
              {tab.label}
            </button>
          );
        })}
      </div>

      {current ? (
        // `tabIndex={0}` so the panel itself is reachable: the content may be a paragraph with no
        // focusable element in it, and a panel a keyboard cannot land on is a panel a screen reader
        // user has to hunt for.
        <div
          role="tabpanel"
          id={panelId(current.id)}
          aria-labelledby={tabId(current.id)}
          tabIndex={0}
          className="rise pt-4"
        >
          {current.panel}
        </div>
      ) : null}
    </div>
  );
}
