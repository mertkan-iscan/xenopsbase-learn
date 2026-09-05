package com.xenopsoftware.learn.streaming.progress;

/**
 * Identity could not say who the caller is, so there is no durable id to credit (ADR-0104).
 *
 * <p>Its own exception rather than a rejection, because it says nothing about the batch: the
 * samples are fine and resending them later is exactly the right thing to do. The caller is told
 * 503 for that reason — a 400 would teach a player to throw away coverage that was never wrong.
 *
 * <p>The alternative would be to key coverage by the IdP subject the request already carries, and
 * that is precisely what ADR-0104 forbids: a subject is a link identity may repair (T-1.7 has a
 * script for it), so a completion record keyed by one goes stale in silence.
 */
public class LearnerUnresolvedException extends RuntimeException {

    public LearnerUnresolvedException(String message) {
        super(message, null, false, false);
    }
}
