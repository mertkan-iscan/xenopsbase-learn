package com.xenopsoftware.learn.reporting.telemetry;

import java.time.Instant;

/**
 * One heartbeat: the slice of a video's own timeline a learner covered since the last sample
 * (ADR-0107, T-3.6).
 *
 * <p><b>An interval, not a position.</b> A furthest position is a claim — dragging the scrubber
 * produces one without watching anything — and an interval is a measurement. That difference is
 * the reason T-3.7 exists, and it starts here: the player reports what it covered, and the server
 * accumulates the union.
 *
 * @param fromSecond inclusive start, in whole seconds of the video's timeline
 * @param toSecond   exclusive end, so consecutive heartbeats meet rather than overlap
 * @param rate       the playback rate while this was covered; recorded, not trusted (T-3.7 uses
 *                   it to reject a batch claiming more content than wall clock allows)
 * @param observedAt the player's clock. Kept alongside our own receive time because the
 *                   difference is the ingest lag, and because a laptop resumed from sleep should
 *                   produce a visible skew rather than a mystery
 */
public record PlaybackSample(int fromSecond, int toSecond, double rate, Instant observedAt) {}
