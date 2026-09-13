import { Check, ChevronDown, CircleHelp, Lock, Play } from 'lucide-react';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router';
import { EmbeddedPlayer } from '../player/EmbeddedPlayer.tsx';
import { catalog } from '../shared/api/client.ts';
import type { components } from '../shared/api/catalog.d.ts';
import { useIsDesktop } from '../shared/design/breakpoint.ts';
import { Progress, StateChip } from '../shared/design/State.tsx';
import { Tabs, type Tab } from '../shared/design/Tabs.tsx';
import { formatDay, formatPercent, formatPosition } from '../shared/i18n/format.ts';
import type { Locale } from '../shared/i18n/locales.ts';
import { useLocale } from '../shared/i18n/useLocale.ts';
import { Empty, ErrorState, Loading } from '../shared/state/States.tsx';
import { NODE_AVAILABLE, NODE_COMPLETE, NODE_IN_PROGRESS, NODE_LOCKED } from './course.ts';
import { ItemShell } from './Item.tsx';
import { PackagePlayer } from './PackagePlayer.tsx';
import { refreshHome, useHome, type HomeView } from './useHome.ts';

/**
 * The content types that open as an uploaded package rather than as a video (T-4.2, T-4.8).
 *
 * <p>The registry's own codes, lower case, exactly as `Item.tsx` holds them — and a set rather
 * than a check for "not video", because the honest default for a type nobody has taught this
 * screen about is the shell saying what it is and not pretending to open it.
 */
const PACKAGE_TYPES = new Set(['scorm', 'cmi5', 'html5', 'slides']);

type PlayerView = components['schemas']['PlayerView'];
type TabId = 'overview' | 'questions' | 'contents';
type HomeCourse = components['schemas']['HomeCourse'];
type HomeModule = components['schemas']['HomeModule'];
type HomeNode = components['schemas']['HomeNode'];

/**
 * Watching one thing (T-3.5), inside the shell every content type shares (T-10.3).
 *
 * <p>Everything around the video comes from `/api/v1/me/home` — the course, the module, which step
 * of how many this is, the type, the due date, and the whole syllabus with its gates. That is not a
 * second request: Home and Discover are built on the same response, shared through
 * {@link useHome}, so arriving here from either is instant.
 *
 * <p><b>THE SYLLABUS IS A PANEL OR A TAB, DEPENDING ON THE ROOM</b>, and it is one element either
 * way. On a desktop it sits beside the video where an administrator's habit of scanning a contents
 * list is worth the width; on a phone it is the third tab, because 320px of syllabus above the
 * video would push the thing the learner came for off the screen. Rendering it twice and hiding one
 * with CSS would put two identical contents lists in the accessibility tree, so this asks the
 * viewport instead — see {@link useIsDesktop}.
 *
 * <p>The pinned questions are real. `GET /api/v1/me/nodes/{nodeId}/interstitials` answers only
 * about the caller, takes no learner id, and returns three things: the markers, WHICH OF THEM THIS
 * LEARNER HAS ALREADY ANSWERED, and the frontier — the second playback is held at until the
 * blocking one before it is answered (T-5.4).
 *
 * <p><b>They are shown and they are not clickable, which is deliberate.</b> `InterstitialView`
 * carries a `questionId` and no question text, and there is no learner-facing endpoint that reads a
 * question outside an attempt or records an interstitial response. This screen used to open a
 * question anyway, with an invented English one hard-coded into it. Listing the real markers — with
 * their seconds, which are blocking, and which are already answered — is information a learner can
 * use; a button that opens a fabricated question is not. See {@link Interstitial} for the component
 * that is waiting for those two endpoints.
 */
export function Watch() {
  const { nodeId } = useParams();
  const state = useHome();

  if (!nodeId) {
    return <NoNode />;
  }
  if (state.status === 'loading') {
    return <Loading what="loading.video" />;
  }
  if (state.status === 'failed') {
    return <ErrorState message={state.failure.message} retry={() => void refreshHome()} />;
  }
  return <WatchScreen home={state.home} nodeId={nodeId} />;
}

function NoNode() {
  const { t } = useLocale();
  return <ErrorState message={t('watch.no-node')} />;
}

/** Where a node sits in its course: enough to draw the header and the syllabus around it. */
type Place = {
  course: HomeCourse;
  module: HomeModule;
  node: HomeNode;
  position: number;
  of: number;
};

/**
 * Find a node in the assigned courses.
 *
 * <p>Returns `undefined` for a node that is not in anything assigned to this learner, which is a
 * real case rather than a defensive one: an old link, a bookmark, an assignment withdrawn since.
 * The screen says so plainly instead of rendering a frame around nothing.
 */
function placeOf(home: HomeView, nodeId: string): Place | undefined {
  for (const course of home.courses ?? []) {
    // Position is counted across the whole COURSE rather than within the module, because that is
    // what the header claims: "3 of 6" is a learner's progress through the course they were
    // assigned, not through whichever module they happen to be in.
    const all = (course.modules ?? []).flatMap((module) => module.nodes ?? []);
    const at = all.findIndex((node) => node.nodeId === nodeId);
    if (at === -1) {
      continue;
    }
    const module = (course.modules ?? []).find((candidate) =>
      (candidate.nodes ?? []).some((node) => node.nodeId === nodeId),
    );
    const node = all[at];
    if (!module || !node) {
      continue;
    }
    return { course, module, node, position: at + 1, of: all.length };
  }
  return undefined;
}

export function WatchScreen({ home, nodeId }: { home: HomeView; nodeId: string }) {
  const { locale, t } = useLocale();
  const navigate = useNavigate();
  const desktop = useIsDesktop();
  const [view, setView] = useState<PlayerView>({});
  const [syllabusOpen, setSyllabusOpen] = useState(true);
  // Declared here with the other hooks rather than beside the tab list it belongs to: there is a
  // conditional return below for a node that is not in this learner's training, and a `useState`
  // after it would run on some renders and not others.
  const [tab, setTab] = useState<TabId>('overview');

  const load = useCallback(() => {
    catalog
      .GET('/api/v1/me/nodes/{nodeId}/interstitials', { params: { path: { nodeId } } })
      .then(({ data }) => setView(data ?? {}))
      // A node with no questions and a node whose questions could not be loaded look the same
      // here, deliberately: neither is a reason to stop a video playing (ADR-0101). What actually
      // holds playback is the server's frontier, checked against progress, not this list.
      .catch(() => setView({}));
  }, [nodeId]);

  useEffect(() => {
    load();
  }, [load]);

  const place = useMemo(() => placeOf(home, nodeId), [home, nodeId]);

  if (!place) {
    return (
      <Empty title={t('item.not-in-your-training')}>
        <p>{t('item.not-in-your-training.body')}</p>
        <Link to="/" className="mt-2 inline-block font-semibold text-brand hover:underline">
          {t('shell.tab.training')}
        </Link>
      </Empty>
    );
  }

  const { course, module, node, position, of } = place;
  const answered = new Set(view.answered ?? []);
  const markers = view.markers ?? [];

  const syllabus = (
    <Syllabus
      course={course}
      currentNodeId={nodeId}
      open={syllabusOpen}
      onToggle={() => setSyllabusOpen((was) => !was)}
      collapsible={desktop}
    />
  );

  const tabs: Tab<TabId>[] = [
    {
      id: 'overview',
      label: t('item.tab.overview'),
      panel: <Overview place={place} locale={locale} />,
    },
    {
      id: 'questions',
      label: t('item.tab.questions'),
      panel: (
        <PinnedQuestions
          markers={markers}
          answered={answered}
          frontierSecond={view.frontierSecond}
        />
      ),
    },
    // The syllabus joins the tabs only when it is not already a panel beside the video. Never both.
    ...(desktop
      ? []
      : [{ id: 'contents' as const, label: t('item.tab.syllabus'), panel: syllabus }]),
  ];

  // Widening the window while the contents tab is open removes that tab, so the selection has to
  // fall back rather than leaving `Tabs` with an `active` id it cannot find.
  const active = tabs.some((candidate) => candidate.id === tab) ? tab : 'overview';

  return (
    <ItemShell
      courseTitle={course.title ?? ''}
      moduleTitle={module.title ?? ''}
      nodeTitle={node.title ?? t('item.type.unknown')}
      position={position}
      of={of}
      type={node.type}
      onBack={() => void navigate('/')}
      aside={desktop ? <div className="w-80 shrink-0">{syllabus}</div> : undefined}
    >
      {/*
        * TWO KINDS OF CONTENT, ONE SHELL, AND THE SHELL IS THE DESIGN (T-10.3).
        *
        * `ItemShell` knows nothing about what it is hosting; this is the one place that decides.
        * A video plays through the embeddable player; an uploaded package opens in an iframe on
        * the tenant's content origin, with a `postMessage` bridge to a runtime the platform stores
        * (T-4.4, ADR-0105). A test is neither and is not reachable from here yet.
        *
        * `contentRef` is the id inside the content item's payload, added to the home response
        * because a learner could not open a package without it: this screen knew the node was a
        * `scorm` and had no way to learn WHICH package.
        */}
      {PACKAGE_TYPES.has(node.type ?? '') && node.contentRef ? (
        <PackagePlayer
          packageId={node.contentRef}
          nodeId={nodeId}
          title={node.title ?? t('item.type.unknown')}
        />
      ) : (
        /* Through the same iframe and the same loader a customer uses (ADR-0110). Rendering the
           player component directly would be one import shorter and would leave the embed path
           exercised by nobody who would notice it break. */
        <div className="overflow-hidden rounded-xl border border-hairline bg-ink shadow-lift">
          <EmbeddedPlayer nodeId={nodeId} title={node.title ?? t('player.untitled')} />
        </div>
      )}

      <Tabs label={t('item.tabs')} tabs={tabs} active={active} onChange={setTab} />
    </ItemShell>
  );
}

/** What is known about this item, from the one call. */
function Overview({ place, locale }: { place: Place; locale: Locale }) {
  const { t } = useLocale();
  const { course, node } = place;
  const percent = node.percent ?? 0;

  return (
    <dl className="flex flex-col gap-4">
      <Row label={t('item.overview.progress')}>
        <div className="flex items-center gap-3">
          <Progress
            percent={percent}
            label={t('home.progress-label', { course: node.title ?? '', percent })}
            dense
          />
          <span className="shrink-0 text-sm font-semibold tabular-nums">
            {formatPercent(locale, percent)}
          </span>
        </div>
        {node.resumeSecond ? (
          <p className="mt-1 text-xs text-muted">
            {t('home.stopped-at', { at: formatPosition(node.resumeSecond) })}
          </p>
        ) : null}
      </Row>

      <Row label={t('item.overview.state')}>
        <div className="flex flex-wrap gap-2">
          {node.state === NODE_COMPLETE ? <StateChip state="passed" /> : null}
          {node.state === NODE_IN_PROGRESS ? <StateChip state="in-progress" /> : null}
          {node.state === NODE_LOCKED ? <StateChip state="locked" /> : null}
          {node.state === NODE_AVAILABLE && course.dueOn && !course.overdue ? (
            <StateChip state="due" detail={formatDay(locale, course.dueOn)} />
          ) : null}
          {course.overdue ? (
            <StateChip
              state="overdue"
              detail={course.dueOn ? formatDay(locale, course.dueOn) : undefined}
            />
          ) : null}
        </div>
        {/* A lock is never drawn without the server's own sentence beside it (T-5.3). */}
        {node.lockedReason ? <p className="mt-2 text-sm text-muted">{node.lockedReason}</p> : null}
      </Row>

      <Row label={t('item.overview.required')}>
        <p className="text-sm">
          {t(node.required ? 'item.overview.is-required' : 'item.overview.is-optional')}
        </p>
      </Row>
    </dl>
  );
}

function Row({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-1.5">
      <dt className="label-caps">{label}</dt>
      <dd className="m-0">{children}</dd>
    </div>
  );
}

/**
 * The questions pinned inside this video, as information.
 *
 * <p>Real, and read-only — see the note on {@link Watch}. The frontier sentence is the one that
 * matters: a learner whose video stops at 06:12 and says nothing has met a bug, and a learner who
 * was told it would stop there has met a feature.
 */
function PinnedQuestions({
  markers,
  answered,
  frontierSecond,
}: {
  markers: components['schemas']['InterstitialView'][];
  answered: Set<string>;
  frontierSecond: number | undefined;
}) {
  const { t } = useLocale();

  if (markers.length === 0) {
    return (
      <Empty title={t('watch.questions.none')}>
        <p>{t('watch.questions.none.body')}</p>
      </Empty>
    );
  }

  return (
    <div className="flex flex-col gap-4">
      <p className="text-sm text-muted">{t('watch.questions.lead')}</p>
      {frontierSecond !== undefined ? (
        <p className="rounded-lg border border-brand-tint-edge bg-brand-tint p-3 text-sm text-brand">
          {t('watch.frontier', { at: clock(frontierSecond) })}
        </p>
      ) : null}
      <ul className="flex flex-col gap-2">
        {[...markers]
          // In the order they occur in the video, which is the order a learner will meet them --
          // the endpoint makes no promise about the order of the array.
          .sort((a, b) => (a.positionSeconds ?? 0) - (b.positionSeconds ?? 0))
          .map((marker) => {
            const done = answered.has(marker.id ?? '');
            return (
              <li
                key={marker.id}
                className="flex items-center gap-3 rounded-lg border border-hairline bg-surface-muted px-3 py-2.5"
              >
                {done ? (
                  <Check aria-hidden="true" className="size-4 shrink-0 text-passed-fg" />
                ) : (
                  <CircleHelp aria-hidden="true" className="size-4 shrink-0 text-subtle" />
                )}
                <span className="text-sm font-semibold tabular-nums">
                  {clock(marker.positionSeconds ?? 0)}
                </span>
                {/*
                 * "answered" as plain text, NOT a `passed` chip. An answered interstitial has not
                 * been passed -- it has been answered, and whether it was right is a marking
                 * question this endpoint says nothing about. Borrowing the passed chip here would
                 * tell a learner they got it right on no evidence at all.
                 */}
                <span className="flex flex-1 flex-wrap gap-2 text-xs text-muted">
                  {done ? t('watch.answered') : null}
                  {!done && marker.blocking ? t('watch.blocking') : null}
                </span>
              </li>
            );
          })}
      </ul>
    </div>
  );
}

/**
 * The course, as a contents list, with the current item marked.
 *
 * <p>`aria-current="step"` on the node being watched: a contents list where the current position is
 * only a background colour is one a screen reader user cannot orient in.
 *
 * <p>A locked node is not a link. It carries its reason as text instead — a padlock that navigates
 * to a player which then refuses is two dead ends where there should be one sentence.
 */
function Syllabus({
  course,
  currentNodeId,
  open,
  onToggle,
  collapsible,
}: {
  course: HomeCourse;
  currentNodeId: string;
  open: boolean;
  onToggle: () => void;
  /** Collapsible beside the video; always open when it is a tab, where collapsing it is absurd. */
  collapsible: boolean;
}) {
  const { locale, t } = useLocale();
  const shown = collapsible ? open : true;

  return (
    <nav aria-label={t('item.syllabus')} className="card overflow-hidden p-0">
      <div className="flex items-center justify-between gap-2 border-b border-hairline px-4 py-3">
        <span className="label-caps">{t('item.syllabus')}</span>
        {collapsible ? (
          <button
            type="button"
            onClick={onToggle}
            aria-expanded={shown}
            className="flex min-h-9 items-center gap-1 rounded-lg px-2 text-xs font-semibold text-muted hover:bg-surface-muted hover:text-ink"
          >
            {t(shown ? 'item.syllabus.hide' : 'item.syllabus.show')}
            <ChevronDown
              aria-hidden="true"
              className={`size-3.5 transition-transform duration-200 ${shown ? '' : '-rotate-90'}`}
            />
          </button>
        ) : null}
      </div>

      {shown ? (
        <div className="flex flex-col">
          <div className="flex items-center gap-3 border-b border-hairline px-4 py-3">
            <Progress
              percent={course.percentComplete ?? 0}
              label={t('home.progress-label', {
                course: course.title ?? '',
                percent: course.percentComplete ?? 0,
              })}
              dense
            />
            <span className="shrink-0 text-xs font-semibold tabular-nums">
              {formatPercent(locale, course.percentComplete ?? 0)}
            </span>
          </div>

          <ol className="flex flex-col">
            {(course.modules ?? []).map((module) => (
              <li key={module.moduleId} className="border-b border-hairline last:border-b-0">
                <p className="flex items-center gap-2 px-4 pt-3 pb-1 text-xs font-bold">
                  {module.title}
                  {module.locked ? (
                    <Lock aria-hidden="true" className="size-3 shrink-0 text-locked-fg" />
                  ) : null}
                </p>
                {/* The module's gate sentence, once, rather than repeated on each of its nodes. */}
                {module.locked && module.lockedReason ? (
                  <p className="px-4 pb-2 text-xs text-muted">{module.lockedReason}</p>
                ) : null}
                <ol className="flex flex-col pb-2">
                  {(module.nodes ?? []).map((node) => {
                    const current = node.nodeId === currentNodeId;
                    const locked = node.state === NODE_LOCKED;
                    const done = node.state === NODE_COMPLETE;
                    const inner = (
                      <>
                        <span className="grid size-5 shrink-0 place-items-center">
                          {done ? (
                            <Check aria-hidden="true" className="size-4 text-passed-fg" />
                          ) : locked ? (
                            <Lock aria-hidden="true" className="size-3.5 text-locked-fg" />
                          ) : current ? (
                            <Play aria-hidden="true" className="size-3.5 text-brand" />
                          ) : (
                            <span className="size-1.5 rounded-full bg-hairline-strong" />
                          )}
                        </span>
                        <span className="min-w-0 flex-1 truncate">{node.title}</span>
                      </>
                    );
                    const shape =
                      'flex min-h-tap w-full items-center gap-2.5 px-4 text-start text-sm';
                    return (
                      <li key={node.nodeId}>
                        {locked ? (
                          <span
                            className={`${shape} cursor-not-allowed text-locked-fg`}
                            title={node.lockedReason ?? undefined}
                          >
                            {inner}
                          </span>
                        ) : (
                          <Link
                            to={`/watch/${node.nodeId ?? ''}`}
                            aria-current={current ? 'step' : undefined}
                            className={[
                              shape,
                              current
                                ? 'bg-brand-tint font-semibold text-brand'
                                : 'text-muted hover:bg-surface-muted hover:text-ink',
                            ].join(' ')}
                          >
                            {inner}
                          </Link>
                        )}
                      </li>
                    );
                  })}
                </ol>
              </li>
            ))}
          </ol>
        </div>
      ) : null}
    </nav>
  );
}

/**
 * A timecode, zero-padded to match a player's own scrubber.
 *
 * <p>`formatPosition` is the shared one and gives `6:12`; a list of markers reads better aligned,
 * so this pads the minutes to `06:12`. The seconds and the arithmetic are not duplicated.
 */
function clock(seconds: number) {
  return formatPosition(seconds).padStart(5, '0');
}
