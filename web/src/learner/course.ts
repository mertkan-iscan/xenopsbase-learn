import type { components } from '../shared/api/catalog.d.ts';

type HomeCourse = components['schemas']['HomeCourse'];
type HomeNode = components['schemas']['HomeNode'];

/**
 * The state strings the home endpoint actually sends, in the one place that is allowed to know
 * them.
 *
 * <p>WHY THIS IS HAND-WRITTEN AND THE REST OF THE CLIENT IS GENERATED. `HomeNode.state` is
 * declared in the OpenAPI description as a bare `string` with no enum, because on the server it is
 * a `String` computed in a ternary rather than a Java enum (`HomeService.java`, and `HomeView`'s
 * javadoc lists the four values). So the generated types cannot narrow it, and every screen that
 * compares it is comparing against a magic string the compiler will not check.
 *
 * <p>Holding them here does not make the compiler check the SERVER, but it does mean there is one
 * place to correct when the server's set changes, and one place to look to find out what the set
 * is. <b>It is `COMPLETE`, not `COMPLETED`</b> — the item and node states differ from the
 * `RequiredState` enum they are derived from, and that discrepancy is exactly the sort of thing a
 * screen gets wrong silently: a comparison against `'COMPLETED'` is never true, so a finished
 * course quietly renders as unfinished forever.
 *
 * <p>The honest fix is an enum on the server and a regenerated spec; until then, this.
 */
export const NODE_COMPLETE = 'COMPLETE';
export const NODE_IN_PROGRESS = 'IN_PROGRESS';
export const NODE_AVAILABLE = 'AVAILABLE';
export const NODE_LOCKED = 'LOCKED';

/** `HomeView.state`. Three genuinely different screens, not three shades of empty. */
export const HOME_NOTHING_ASSIGNED = 'NOTHING_ASSIGNED';
export const HOME_ALL_DONE = 'ALL_DONE';

/**
 * Where to send somebody who taps a course.
 *
 * <p>In progress first, then the first thing available — which is the same order
 * `HomeService.nextUp` uses to pick the one item it promotes to the top of Home. Deliberately the
 * same: a learner who taps a course and a learner who taps Resume on Home should land on the same
 * node, and two different orderings of "what is next" is how they stop agreeing.
 *
 * <p>Returns `undefined` when a course has nothing open — every node finished, or every node
 * locked behind a gate. The caller decides what to do about it; there is no sensible node to
 * navigate to, and sending somebody to a locked one would show them a player that refuses.
 */
export function firstResumableNode(course: HomeCourse): string | undefined {
  const nodes = (course.modules ?? []).flatMap((module) => module.nodes ?? []);
  const started = nodes.find((node) => node.state === NODE_IN_PROGRESS);
  const available = nodes.find((node) => node.state === NODE_AVAILABLE);
  return (started ?? available)?.nodeId;
}

/** Every node in a course, flattened, in course order. */
export function nodesOf(course: HomeCourse): HomeNode[] {
  return (course.modules ?? []).flatMap((module) => module.nodes ?? []);
}

/**
 * The facets Discover filters by.
 *
 * <p>DERIVED, NOT FETCHED. There is no learner-facing course search in this API — the whole
 * learner surface is sixteen `/me/` paths and none of them takes a query (docs/api-surface.md). So
 * "Discover" filters the `courses` array the home endpoint already returned, and these are the only
 * facets that can be computed from real fields. A category, a rating, an instructor or a price
 * would each need a column that does not exist.
 */
export type Facet = 'in-progress' | 'due-soon' | 'overdue' | 'complete' | 'locked';

export function facetsOf(course: HomeCourse): Set<Facet> {
  const facets = new Set<Facet>();
  if (course.completed) {
    facets.add('complete');
  }
  if (course.overdue) {
    facets.add('overdue');
  }
  const percent = course.percentComplete ?? 0;
  if (!course.completed && percent > 0) {
    facets.add('in-progress');
  }
  // "Due soon" is the server's own word for it via `summary.dueSoon`, but that is a COUNT and not
  // a flag per course, so the per-course version is computed here: a deadline that exists, has not
  // passed, and is inside a week. The window is stated once, here, rather than in the screen.
  if (!course.completed && !course.overdue && course.dueOn && withinDays(course.dueOn, 7)) {
    facets.add('due-soon');
  }
  if ((course.modules ?? []).some((module) => module.locked)) {
    facets.add('locked');
  }
  return facets;
}

/**
 * Whether an ISO date is today or within the next `days`.
 *
 * <p>Compared as DATES rather than as instants. A deadline in this product is a date, and it
 * expires when that day ends in the LEARNER's own time zone (docs/deadlines.md) — the server owns
 * that decision and reports it as `overdue`. This is only deciding whether to draw a "due soon"
 * chip, so comparing calendar days in the browser's zone is close enough, and it must not be used
 * to decide whether something IS overdue.
 */
function withinDays(iso: string, days: number): boolean {
  const due = new Date(`${iso}T00:00:00`);
  if (Number.isNaN(due.getTime())) {
    return false;
  }
  const today = new Date();
  const midnight = new Date(today.getFullYear(), today.getMonth(), today.getDate());
  const span = (due.getTime() - midnight.getTime()) / 86_400_000;
  return span >= 0 && span <= days;
}
