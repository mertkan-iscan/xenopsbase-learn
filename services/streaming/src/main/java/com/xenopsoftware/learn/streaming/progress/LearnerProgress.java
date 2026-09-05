package com.xenopsoftware.learn.streaming.progress;

import java.time.Instant;
import java.util.UUID;

/**
 * What the server knows about one learner and one node, and the only progress a client ever sees
 * (T-3.7).
 *
 * <p><b>Everything here is derived.</b> There is no field a browser can set and no endpoint that
 * would accept one — that is ADR-0107 in a record. A player renders this; it never computes its
 * own version of it, because two computations of completion is one that disagrees with the report.
 *
 * @param coveredSeconds     distinct seconds actually presented, after merging
 * @param extentSeconds      the item's measurable length as the provider reported it (T-3.1), or
 *                           null when the encode has not told us one yet — in which case coverage
 *                           is still accumulated and completion simply cannot be derived
 * @param percent            coverage of the extent, rounded down, or 0 when there is no extent
 * @param thresholdPercent   what this item needs, per item, defaulting to 90 (ADR-0107)
 * @param completed          coverage has crossed the threshold. Never a client's opinion
 * @param completionSource   DERIVED here, always. SCORM and cmi5 write SELF_REPORTED in their own
 *                           module and a person with the permission writes MANUAL; every export
 *                           carries the source, because a report that mixes measured with
 *                           self-reported completions silently is the report ADR-0107 prevents
 * @param resumeSecond       where playback should pick up — the furthest second reached
 * @param allowSeekForward   whether this item permits skipping past unwatched content. The player
 *                           enforces the same rule the server enforces, which is why it is told it
 * @param seekCeilingSecond  the furthest second the player may seek to when seeking forward is
 *                           forbidden; null when it is allowed
 * @param fragments          how many separate runs the coverage is in — the number the size bound
 *                           applies to, exposed because a support question about an approximate
 *                           record starts here
 * @param approximate        the fragment cap has merged across a real gap, so coverage credits
 *                           seconds nobody was shown. Stated rather than hidden: an approximate
 *                           completion is still a completion, and somebody may need to know
 */
public record LearnerProgress(UUID nodeId, int coveredSeconds, Integer extentSeconds, int percent,
                              int thresholdPercent, boolean completed, Instant completedAt,
                              String completionSource, int resumeSecond, boolean allowSeekForward,
                              Integer seekCeilingSecond, int fragments, boolean approximate) {}
