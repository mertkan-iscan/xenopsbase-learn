package com.xenopsoftware.learn.streaming.progress;

import java.time.Instant;
import java.util.List;

/**
 * What the player posts about what it has just shown (T-3.7).
 *
 * <h2>Why this is posted here as well as to reporting</h2>
 *
 * The same samples go to {@code reporting} (T-3.6) and they are not the same write. There they are
 * raw, append-only, droppable at ninety days (ADR-0108) and read by nothing on a learner's path.
 * Here they are merged into state that decides whether a person completed their training, and
 * {@code docs/reporting-inputs.md} states the rule that forces the split: <b>progress recording
 * must complete with {@code reporting} stopped.</b> Deriving completion from rows in the analytics
 * store would break that in one commit, and it would break it invisibly — reports would keep
 * rendering, with fewer completions in them.
 *
 * <p>The cost is honest and small: one extra post per learner per ten seconds, carrying the same
 * body. The alternative that avoids it — posting once here and forwarding the raw samples onward
 * through the outbox — would put ~500 rows per second of transport on this module's database,
 * which is the hot path ADR-0107 explicitly keeps that volume off.
 *
 * @param playbackToken the token minted for this session (T-3.4). ADR-0107 chose the credential
 *        that already exists over designing a nonce to rotate and leak; this is where it is
 *        carried. It identifies the session — it is not a second authentication, and the caller's
 *        own bearer token is what says who they are
 * @param samples the intervals covered since the last post, in any order
 */
public record ProgressBatch(String playbackToken, List<Sample> samples) {

    /**
     * One heartbeat: a slice of the video's own timeline, and the rate it was played at.
     *
     * @param fromSecond inclusive start, in whole seconds
     * @param toSecond   exclusive end, so consecutive heartbeats meet rather than overlap
     * @param rate       the playback rate while it was covered — recorded, and used to bound what
     *                   wall clock could have allowed, never to scale what is credited
     * @param observedAt the player's clock, kept because the difference from ours is the ingest
     *                   lag and because a laptop resumed from sleep should be a visible skew
     *                   rather than a mystery
     */
    public record Sample(int fromSecond, int toSecond, double rate, Instant observedAt) {}
}
