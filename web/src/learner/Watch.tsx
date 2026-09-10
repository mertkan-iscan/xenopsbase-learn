import { useCallback, useEffect, useState } from 'react';
import { useNavigate, useParams } from 'react-router';
import { EmbeddedPlayer } from '../player/EmbeddedPlayer.tsx';
import { catalog } from '../shared/api/client.ts';
import type { components } from '../shared/api/catalog.d.ts';
import { formatPosition } from '../shared/i18n/format.ts';
import { useT } from '../shared/i18n/useLocale.ts';
import { ErrorState } from '../shared/state/States.tsx';
import { Interstitial, ItemShell } from './Item.tsx';

type PlayerView = components['schemas']['PlayerView'];
type InterstitialView = components['schemas']['InterstitialView'];

/**
 * Watching one thing (T-3.5), inside the shell every content type shares (T-10.3).
 *
 * <p>The screen is thin on purpose and stays thin: what a learner sees around a video comes from
 * the catalog (T-5.2) and their own progress (T-3.7). What the design added is the frame — the
 * same one a SCORM package and a set of slides will be hosted in, so that a new content type is a
 * child of {@link ItemShell} rather than a new screen.
 *
 * <p>The pinned questions are real. `GET /api/v1/me/nodes/{nodeId}/interstitials` answers only
 * about the caller, takes no learner id, and returns three things this screen uses: the markers,
 * WHICH OF THEM THIS LEARNER HAS ALREADY ANSWERED, and the frontier — the second playback is held
 * at until the blocking one before it is answered (T-5.4). Rendering an answered question again
 * would be the most annoying possible bug in a training video, so the answered list is applied
 * here rather than assumed away.
 *
 * <p>WHAT IS NOT WIRED, AND WHY IT IS VISIBLE ANYWAY. Two pieces are missing between this screen
 * and a recorded answer: the player is behind an iframe (ADR-0110) and reports where it is over
 * `postMessage`, so "the video reached 06:12" is not something this component observes yet; and
 * catalog exposes no endpoint that records an `interstitial_response` — only the read above. So a
 * question is opened from the list rather than by the playhead, and answering resumes without
 * persisting. Each gap is one message and one endpoint, and drawing the question now is what makes
 * them obvious rather than theoretical.
 */
export function Watch() {
  const t = useT();
  const { nodeId } = useParams();
  const navigate = useNavigate();
  const [view, setView] = useState<PlayerView>({});
  const [open, setOpen] = useState<InterstitialView | null>(null);

  const load = useCallback(() => {
    if (!nodeId) {
      return;
    }
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

  if (!nodeId) {
    return <ErrorState message={t('watch.no-node')} />;
  }

  const answered = new Set(view.answered ?? []);
  const outstanding = (view.markers ?? []).filter((marker) => !answered.has(marker.id ?? ''));

  return (
    <ItemShell
      courseTitle="Data Protection 2026"
      moduleTitle="Module 2"
      position={3}
      of={6}
      type="video"
      onBack={() => void navigate('/')}
    >
      {/* Through the same iframe and the same loader a customer uses (ADR-0110). Rendering the
          player component directly would be one import shorter and would leave the embed path
          exercised by nobody who would notice it break. */}
      <EmbeddedPlayer nodeId={nodeId} title={t('player.untitled')} />

      {open ? (
        <Interstitial
          question="A colleague asks you to email a customer list to their personal address. What do you do?"
          options={[
            { id: 'a', text: 'Send it — they are a colleague' },
            { id: 'b', text: 'Refuse and refer them to the data owner' },
            { id: 'c', text: 'Send it with the names removed' },
          ]}
          atSecond={clock(open.positionSeconds ?? 0)}
          onAnswer={() => setOpen(null)}
          onSkip={() => setOpen(null)}
        />
      ) : null}

      {outstanding.length > 0 && !open ? (
        <section className="pinned-list" aria-labelledby="pinned-list">
          <h2 id="pinned-list" className="u-caps">
            {t('watch.pinned-title')}
          </h2>
          {view.frontierSecond !== undefined ? (
            <p className="u-meta">
              {t('watch.frontier', { at: clock(view.frontierSecond) })}
            </p>
          ) : null}
          <ul>
            {outstanding.map((marker) => (
              <li key={marker.id}>
                <button type="button" className="btn btn-secondary" onClick={() => setOpen(marker)}>
                  {clock(marker.positionSeconds ?? 0)}
                  {marker.blocking ? ` · ${t('watch.blocking')}` : ''}
                </button>
              </li>
            ))}
          </ul>
        </section>
      ) : null}
    </ItemShell>
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
