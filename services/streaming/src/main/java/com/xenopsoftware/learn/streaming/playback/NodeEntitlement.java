package com.xenopsoftware.learn.streaming.playback;

import java.util.UUID;

/**
 * What catalog knows about one node for one viewer (T-5.1, T-5.3, T-5.5), answered in one call.
 *
 * <p>One call rather than three, deliberately. "Does this node exist", "is it assigned to this
 * person" and "has this person passed its gate" are three questions with one answer path —
 * catalog walks from the node to the assignment to the gate rule once — and asking them
 * separately would put three network hops on the learner hot path to reconstruct a single
 * traversal. The <b>checks</b> stay separate, which is what T-3.4 actually requires: this record
 * carries three independent answers and {@code PlaybackTokenService} refuses on each of them
 * with its own reason.
 *
 * @param nodeId       the node asked about, echoed so a stale or mismatched answer is visible
 * @param videoAssetId OUR asset id (never a provider ref, ADR-0101), or null when the node is
 *                     not a video — a slide deck or a SCORM package, which have their own
 *                     delivery paths and are not this endpoint's to sign for
 * @param assigned     whether this node reaches this viewer through any assignment (T-5.5)
 * @param reachable    whether the gate in front of it is open for this viewer (T-5.3)
 * @param gateReason   the sentence a learner should read when {@code reachable} is false; null
 *                     otherwise. T-5.3 requires the rule to be readable by the person it stops
 */
public record NodeEntitlement(UUID nodeId, UUID videoAssetId, boolean assigned, boolean reachable,
                              String gateReason) {}
