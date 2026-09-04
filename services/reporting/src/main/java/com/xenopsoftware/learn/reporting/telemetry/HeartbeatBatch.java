package com.xenopsoftware.learn.reporting.telemetry;

import java.util.List;
import java.util.UUID;

/**
 * What the player posts every ten seconds (T-3.6).
 *
 * <p>A batch rather than a sample per request, and the reason is arithmetic: ADR-0107 puts this at
 * roughly one post per learner per ten seconds — ~500 posts/second at 5,000 concurrent learners.
 * Per-sample posting would multiply that by however many samples a player buffered while a train
 * went through a tunnel, which is exactly when the network is worst.
 *
 * <p>The batch is also what makes a retry safe to state: a failed post is retried <b>with the
 * batch intact</b>, so nothing is dropped by the retry itself.
 *
 * @param playbackToken the token minted for this session (T-3.4). Carried rather than a second
 *        signed credential, because ADR-0107 chose the credential that already exists over
 *        designing a nonce to rotate and leak. This task records it; T-3.7 is what checks it
 */
public record HeartbeatBatch(UUID nodeId, String playbackToken, List<PlaybackSample> samples) {}
