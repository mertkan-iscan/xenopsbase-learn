/**
 * Which palette this document is being read in (T-10.9).
 *
 * <h2>The same shape as `i18n/locale.ts`, deliberately</h2>
 *
 * <p>A store outside React that components subscribe to, for the same reason that one is: the
 * value has to be written to `document.documentElement` before React renders anything, and code
 * that is not a component has to be able to read it. A context provider would mean the first paint
 * happens in whatever the operating system prefers and then flips — which is the flash this file
 * exists to prevent — and would have to publish to a module variable during render, which is a
 * side effect `react-hooks/globals` rightly rejects.
 *
 * <h2>Three values, and `system` is not the absence of an answer</h2>
 *
 * <p>`light` and `dark` pin the palette. `system` follows the operating system, which is also what
 * happens when somebody has never chosen — and the two are still different facts. `null` means
 * "has not told us", a population identity can find and ask; `system` means "tried dark, went
 * back". `V14__user_preferences.sql` makes the same argument at the column, and nothing on either
 * side may collapse them.
 *
 * <h2>Where the answer comes from, in order</h2>
 *
 * <ol>
 *   <li><b>The server</b>, through `/api/v1/me`. A palette is a property of the person, not of
 *       the machine they are at, so it follows them to a phone and to a shared terminal on a
 *       factory floor. That is the record.
 *   <li><b>This browser's last known answer</b>, in `localStorage`. A cache, and only for the few
 *       hundred milliseconds before `/me` returns — without it, somebody who needs the light theme
 *       gets a dark screen on every reload, which is the specific thing they chose it to avoid.
 *   <li><b>The operating system</b>, through `prefers-color-scheme`, which is what `styles.css`
 *       does when no `data-theme` is set at all.
 * </ol>
 */
const REMEMBERED = 'xenops.theme';

export const THEMES = ['light', 'dark', 'system'] as const;

export type Theme = (typeof THEMES)[number];

export function isTheme(value: string | null | undefined): value is Theme {
  return value !== null && value !== undefined && (THEMES as readonly string[]).includes(value);
}

/**
 * What the server calls it, reduced to what a browser uses.
 *
 * <p>identity stores the enum's own name — `LIGHT`, `DARK`, `SYSTEM` — the way it stores every
 * other closed set. The browser needs the lowercase form, because that is what goes into
 * `data-theme` and what `styles.css` matches on. One place does the conversion so a typo cannot
 * live in two.
 */
export function themeFrom(value: string | null | undefined): Theme | null {
  if (!value) {
    return null;
  }
  const lowered = value.trim().toLowerCase();
  return isTheme(lowered) ? lowered : null;
}

function remembered(): Theme | null {
  try {
    return themeFrom(window.localStorage.getItem(REMEMBERED));
  } catch {
    // Private windows, and browsers set to block site data, throw on the accessor itself. A
    // palette preference is not worth a blank page.
    return null;
  }
}

function remember(theme: Theme) {
  try {
    window.localStorage.setItem(REMEMBERED, theme);
  } catch {
    // See above. The server holds the record; this was only ever the head start.
  }
}

let current: Theme = remembered() ?? 'system';
const listeners = new Set<() => void>();

/** The theme, for code that is not a component. */
export function currentTheme(): Theme {
  return current;
}

export function subscribeToTheme(listener: () => void): () => void {
  listeners.add(listener);
  return () => listeners.delete(listener);
}

/**
 * Puts the document into a palette.
 *
 * <p>`system` REMOVES the attribute rather than setting it to `"system"`. That is the contract
 * `styles.css` is written against: its dark block is `@media (prefers-color-scheme: dark)` guarded
 * by `:root:not([data-theme='light'])`, so no attribute means the operating system decides. An
 * attribute of `data-theme="system"` would match neither the light nor the dark selector and would
 * silently pin everybody to the light palette.
 */
function apply(next: Theme) {
  current = next;
  if (typeof document === 'undefined') {
    return;
  }
  const root = document.documentElement;
  if (next === 'system') {
    root.removeAttribute('data-theme');
  } else {
    root.setAttribute('data-theme', next);
  }
  for (const listener of listeners) {
    listener();
  }
}

/** A deliberate choice made in this browser. The caller is responsible for telling the server. */
export function chooseTheme(next: Theme) {
  remember(next);
  apply(next);
}

/**
 * What `/api/v1/me` said.
 *
 * <p><b>Null is not `system`.</b> Somebody who has never chosen falls through to whatever this
 * browser had already resolved — which, for a person who picked light here five minutes ago and
 * has not yet had it saved, is light. Overwriting that with a default would undo a choice in front
 * of them.
 */
export function adoptServerTheme(next: Theme | null) {
  if (next === null) {
    return;
  }
  remember(next);
  apply(next);
}

/**
 * Puts the remembered theme on the document before React starts.
 *
 * <p>Called from `main.tsx` at module scope, and the timing is the entire point: everything after
 * the first paint is a flash of the wrong palette. It is also called by the embedded player's
 * entry, which has no shell and still has to match the page it is embedded in.
 */
export function applyRememberedTheme() {
  apply(current);
}
