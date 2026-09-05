import { useEffect, useRef } from 'react';
import { reporting, streaming } from '../shared/api/client.ts';

/**
 * What the player reports about what was actually shown (T-3.6, T-3.7).
 *
 * <h2>Intervals, not positions</h2>
 *
 * Each sample is the slice of the video's own timeline covered since the last one. A furthest
 * position is a claim — dragging the scrubber produces one without watching anything — and an
 * interval is a measurement (ADR-0107). So the loop watches `currentTime` and records where it
 * moved *continuously*; a seek ends the current run and starts a new one rather than covering the
 * gap it jumped over.
 *
 * <h2>Two posts, and why they are not one</h2>
 *
 * The same batch goes to `reporting` and to `streaming`, and they are not the same write.
 * `reporting` keeps raw samples: append-only, droppable at ninety days (ADR-0108), read by nothing
 * a learner waits on. `streaming` merges them into the coverage that decides whether this person
 * completed their training, and answers with the result.
 *
 * The rule that forces the split is in `docs/reporting-inputs.md`: **progress recording must
 * complete with `reporting` stopped.** Deriving completion from rows in the analytics store would
 * break that in one commit and break it invisibly — reports would keep rendering, with fewer
 * completions in them. The cost is one extra request per learner per ten seconds, carrying the
 * same body, and it buys the two paths independent fates in both directions.
 *
 * <h2>Batched every ten seconds, and why the number is not smaller</h2>
 *
 * One post per learner per ten seconds is ~500 posts/second at 5,000 concurrent learners
 * (ADR-0107). Posting per sample would multiply that by whatever a player buffered while a train
 * went through a tunnel — which is exactly when the network is worst and exactly when a burst is
 * least welcome.
 *
 * <h2>The buffer is bounded, and it drops the OLDEST</h2>
 *
 * A learner offline for an hour must not produce an hour-sized post: the server refuses it (413)
 * and the client would have spent the memory to build it. So the buffer caps, and at the cap it
 * discards the oldest samples rather than refusing the newest.
 *
 * That direction is deliberate. Recent coverage is what a resume position and a progress bar are
 * computed from, so keeping it is what the learner sees working. The lost middle costs coverage
 * they will not be credited for, which is the honest trade — and ADR-0107 already accepts that
 * losing heartbeats is survivable while losing all of them silently is not.
 *
 * <h2>Retried once, with the batch intact</h2>
 *
 * A failed post is put back at the front of the buffer and goes out with the next tick. Once,
 * because a client that retried indefinitely would turn a brief outage into a thundering herd the
 * moment the service came back, and it is the server's metrics — not the client's persistence —
 * that make a real outage visible. Re-posting is safe on both sides: coverage is a union, so a
 * batch that did land twice credits nothing the second time (T-3.7).
 */

export type Sample = {
  fromSecond: number;
  toSecond: number;
  rate: number;
  observedAt: string;
};

/**
 * What the server derived from all of it — the only progress this player ever shows.
 *
 * Nothing here is computed in the browser. A player that kept its own idea of completion would be
 * a second answer to the question a compliance report answers, and the two would disagree the
 * first time a heartbeat was lost.
 */
export type Progress = {
  coveredSeconds: number;
  extentSeconds?: number | null;
  percent: number;
  thresholdPercent: number;
  completed: boolean;
  resumeSecond: number;
  allowSeekForward: boolean;
  seekCeilingSecond?: number | null;
  fragments: number;
  approximate: boolean;
};

/** Ten seconds, matching ADR-0107's write-volume arithmetic. */
const FLUSH_INTERVAL_MS = 10_000;

/**
 * Ten minutes of samples. Past that the learner has been away long enough that the middle of
 * their session is not worth the memory, and the server's own cap is the same number.
 */
const MAX_BUFFERED_SAMPLES = 60;

/**
 * How far `currentTime` may move between observations and still count as continuous playback. A
 * one-second tick at 2x moves two seconds; anything past this is a seek, and a seek covers
 * nothing. The server uses the same number as its seek tolerance, so the two halves of the
 * no-skipping-ahead rule agree about what a skip is.
 */
const CONTINUOUS_JUMP_SECONDS = 3;

export function useHeartbeats(
  video: HTMLVideoElement | null,
  nodeId: string,
  playbackToken: string | undefined,
  onProgress?: (progress: Progress) => void,
) {
  const buffer = useRef<Sample[]>([]);
  const lastSecond = useRef<number | undefined>(undefined);
  const retried = useRef(false);
  // The token changes every renewal (T-3.4); the loop must post with the current one without
  // being torn down and restarted, which would lose the buffer every five minutes.
  const token = useRef(playbackToken);
  // Held in a ref for the same reason: a new callback identity on every render of the player
  // would otherwise restart the loop and drop whatever it had buffered.
  const report = useRef(onProgress);

  useEffect(() => {
    token.current = playbackToken;
  }, [playbackToken]);

  useEffect(() => {
    report.current = onProgress;
  }, [onProgress]);

  useEffect(() => {
    if (!video || !playbackToken) {
      return;
    }

    function observe() {
      const element = video;
      if (!element || element.paused || element.ended) {
        // Paused time is not watched time. Ending the run here is also what stops a pause from
        // being covered as one enormous interval when playback resumes.
        lastSecond.current = undefined;
        return;
      }
      const now = Math.floor(element.currentTime);
      const previous = lastSecond.current;
      lastSecond.current = now;

      if (previous === undefined || now <= previous) {
        return;
      }
      if (now - previous > CONTINUOUS_JUMP_SECONDS) {
        // A seek. The jumped-over span was not watched, so nothing is recorded for it -- which is
        // the entire point of measuring intervals instead of trusting a position.
        return;
      }
      push({
        fromSecond: previous,
        toSecond: now,
        rate: element.playbackRate,
        observedAt: new Date().toISOString(),
      });
    }

    function push(sample: Sample) {
      const buffered = buffer.current;
      buffered.push(sample);
      if (buffered.length > MAX_BUFFERED_SAMPLES) {
        // Drop the oldest, keep the newest: recent coverage is what the learner sees working.
        buffer.current = buffered.slice(buffered.length - MAX_BUFFERED_SAMPLES);
      }
    }

    async function flush() {
      const sending = buffer.current;
      const currentToken = token.current;
      if (sending.length === 0 || !currentToken) {
        return;
      }
      buffer.current = [];

      // Both posts are started before either is awaited: they are independent services and one
      // being slow must not delay the other. `allSettled`, because a rejected analytics post is
      // not a reason to lose the progress one -- or the other way round.
      const [analytics, progress] = await Promise.allSettled([
        reporting.POST('/api/v1/telemetry/playback', {
          body: { nodeId, playbackToken: currentToken, samples: sending },
        }),
        streaming.POST('/api/v1/me/nodes/{id}/progress', {
          params: { path: { id: nodeId } },
          body: { playbackToken: currentToken, samples: sending },
        }),
      ]);

      if (progress.status === 'fulfilled' && progress.value.data) {
        report.current?.(progress.value.data as Progress);
      }

      if (accepted(analytics) && accepted(progress)) {
        retried.current = false;
        return;
      }
      keepForOneRetry(sending);
    }

    /**
     * Whether a post is finished with, either way.
     *
     * A 4xx will answer the same way however many times it is sent -- the server says so with a
     * specific status precisely so a client can stop. Retrying it would turn one broken player
     * into sustained load, so a refusal counts as done and only a 5xx or a dead network is worth
     * coming back for.
     */
    function accepted(result: PromiseSettledResult<{ response?: Response }>): boolean {
      if (result.status === 'rejected') {
        return false;
      }
      const status = result.value.response?.status;
      return status !== undefined && (status < 500 || status === 0);
    }

    function keepForOneRetry(sending: Sample[]) {
      if (retried.current) {
        // Already retried this batch once. Telemetry is allowed to lose samples; what is not
        // allowed is a client that hoards them and stampedes when the service returns.
        retried.current = false;
        return;
      }
      retried.current = true;
      buffer.current = [...sending, ...buffer.current].slice(-MAX_BUFFERED_SAMPLES);
    }

    const ticking = setInterval(observe, 1000);
    const flushing = setInterval(() => void flush(), FLUSH_INTERVAL_MS);

    return () => {
      clearInterval(ticking);
      clearInterval(flushing);
      // One last attempt on the way out, so closing a tab does not silently discard the last ten
      // seconds of every session in the product.
      void flush();
    };
    // playbackToken is a dependency only so the loop starts once a token exists; renewals reach
    // it through the ref above rather than restarting it and losing the buffer.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [video, nodeId, !!playbackToken]);
}
