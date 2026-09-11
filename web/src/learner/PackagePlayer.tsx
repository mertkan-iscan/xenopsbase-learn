import { useEffect, useMemo, useRef, useState } from 'react';
import { useMe } from '../shared/auth/useMe.ts';
import { StateChip } from '../shared/design/State.tsx';
import { useT } from '../shared/i18n/useLocale.ts';
import { ErrorState, Loading } from '../shared/state/States.tsx';
import { bridgeToWrapper, openRuntime, type RuntimeSnapshot } from './packageBridge.ts';

/**
 * Opening an uploaded package, and remembering where the learner got to (T-4.4, ADR-0105).
 *
 * <p>The iframe points at the tenant's content origin, which is a different origin to this
 * application and holds nothing of ours. Everything the package says arrives through
 * {@link bridgeToWrapper}, which is where the origin checks are.
 *
 * <h2>The frame is created once, and its `src` never changes</h2>
 *
 * <p>Re-rendering with a different `src` — or a `key` that changes — reloads the package, and a
 * package reloaded mid-course loses whatever it had not committed. So the launch URL is captured
 * with the FIRST snapshot and never recomputed: the state that arrives afterwards (completed,
 * score, time) changes what is drawn AROUND the frame and never the frame itself.
 *
 * <h2>One state object, keyed by what it is about</h2>
 *
 * <p>Everything below hangs off a single {@link Opened}, and it carries the registration it
 * belongs to. That is not tidiness — it is what lets a change of package be handled without a
 * synchronous {@code setState} inside an effect to clear the old one. A stale object is simply not
 * the one being asked about, so the screen renders as loading until its replacement arrives, and
 * there is no render in between showing one package's progress under another package's title.
 */
export function PackagePlayer({
  packageId,
  nodeId,
  title,
}: {
  packageId: string;
  /** The place in a course, so a resume is per node. Absent for a launch outside one. */
  nodeId: string | undefined;
  title: string;
}) {
  const t = useT();
  // The same call Assign and Authoring make, for the same reason: there is no shared context for
  // it, and a package that greets the learner by name needs a name from somewhere.
  const who = useMe(true);
  const frame = useRef<HTMLIFrameElement>(null);

  /** Which registration a piece of state is about, so a stale one can be recognised as stale. */
  const registration = `${packageId}:${nodeId ?? ''}`;

  type Opened = {
    for: string;
    snapshot: RuntimeSnapshot;
    /** Captured once, with the first snapshot. See the header: changing it reloads the package. */
    launch: { url: string; origin: string } | null;
    /** Another launch has taken this registration, so this window has stopped saving (T-4.4). */
    superseded: boolean;
  };

  const [opened, setOpened] = useState<Opened | null>(null);
  const [failedFor, setFailedFor] = useState<string | null>(null);

  useEffect(() => {
    let current = true;
    openRuntime(packageId, nodeId)
      .then((snapshot) => {
        if (!current) {
          return;
        }
        if (snapshot) {
          setOpened({
            for: registration,
            snapshot,
            launch: snapshot.launchUrl
              ? { url: snapshot.launchUrl, origin: snapshot.contentOrigin }
              : null,
            superseded: false,
          });
        } else {
          setFailedFor(registration);
        }
      })
      .catch(() => {
        if (current) {
          setFailedFor(registration);
        }
      });
    return () => {
      current = false;
    };
    // `registration` is derived from the two below and changes with them; naming all three keeps
    // the dependency list honest rather than clever.
  }, [packageId, nodeId, registration]);

  // Anything about a different package is not an answer to this one.
  const live = opened && opened.for === registration ? opened : null;
  const failed = failedFor === registration;

  const learner = useMemo(
    () => (who.state === 'tenant' ? { id: who.me.id, name: who.me.displayName } : null),
    [who],
  );

  const snapshot = live?.snapshot;
  const launch = live?.launch;

  /*
   * The bridge is attached after the frame exists and detached on unmount.
   *
   * Detaching matters more than it looks: the listener is on `window`, so a learner who opens two
   * packages in one session without a reload would otherwise have two, and the second package's
   * commits would be written twice — once against the right package and once against the one they
   * had already left.
   */
  useEffect(() => {
    const element = frame.current;
    if (!element || !snapshot || !launch) {
      return;
    }
    return bridgeToWrapper({
      frame: element,
      contentOrigin: launch.origin,
      packageId,
      nodeId,
      state: snapshot,
      learner,
      supersededText: t('package.superseded'),
      onSaved: (saved) =>
        setOpened((held) =>
          held && held.for === registration ? { ...held, snapshot: saved } : held,
        ),
      onSuperseded: () =>
        setOpened((held) =>
          held && held.for === registration ? { ...held, superseded: true } : held,
        ),
    });
    // `snapshot` is a dependency because the wrapper asks for it on `ready` and the answer has to
    // be the current one — but the frame is not recreated, so re-attaching a listener is all that
    // happens.
  }, [snapshot, launch, packageId, nodeId, registration, learner, t]);

  if (failed) {
    return <ErrorState message={t('package.unavailable')} />;
  }
  if (!live || !snapshot || !launch) {
    return <Loading what="loading.package" />;
  }

  return (
    <div className="flex flex-col gap-3">
      <div className="flex flex-wrap items-center gap-2">
        <StateChip state={snapshot.completed ? 'passed' : 'in-progress'} />
        <span className="text-sm text-muted">
          {t(snapshot.entry === 'resume' ? 'package.resumed' : 'package.started')}
        </span>
      </div>

      {/*
       * Said outside the frame as well as inside it. The wrapper covers the package with the same
       * sentence, and the package can navigate that document away; this cannot be navigated away
       * by anything running in the iframe, so it is the copy that survives.
       */}
      {live.superseded ? (
        <p role="alert" className="rounded-xl border border-hairline bg-surface p-3 text-sm">
          {t('package.superseded')}
        </p>
      ) : null}

      {/*
       * `sandbox` is deliberately absent, and it is the same reason the wrapper gives for the
       * frame inside it: a sandboxed iframe gets an OPAQUE origin, which is not the tenant's
       * content origin — so the package's API discovery would find nothing and the course would
       * not start. The isolation that matters is the origin, and the CSP the content origin serves
       * constrains the residue (ADR-0105).
       */}
      <iframe
        ref={frame}
        src={launch.url}
        title={title}
        allow="autoplay; fullscreen"
        className="block h-[36rem] w-full rounded-xl border border-hairline bg-surface"
      />
    </div>
  );
}
