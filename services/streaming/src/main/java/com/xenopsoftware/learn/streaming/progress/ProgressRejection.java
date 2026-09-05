package com.xenopsoftware.learn.streaming.progress;

import org.springframework.http.HttpStatus;

/**
 * Why a batch of watched intervals was not credited, as a code the player can act on (T-3.7).
 *
 * <p>The names and statuses deliberately match {@code reporting}'s ingest rejections (T-3.6) where
 * they mean the same thing. The player posts the same body to both services; two different
 * vocabularies for "this batch is malformed" would mean two code paths in the client for one bug
 * in the player.
 *
 * <p>Specific rather than generic, for the reason T-3.6 already paid for: a client that cannot
 * tell "split this" from "stop sending this" retries both, and one broken player becomes sustained
 * load. Every one of these is counted, and the two that describe a learner rather than a bug —
 * {@link #IMPLAUSIBLE_RATE} and {@link #SEEK_NOT_ALLOWED} — are also counted on the learner's own
 * progress row, because "why is my progress not moving" is a support question about one person.
 */
public enum ProgressRejection {

    /** More samples than one post may carry. Split it. */
    BATCH_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE),

    /** No samples at all — nothing to merge, and a bug in whatever sent it. */
    EMPTY_BATCH(HttpStatus.BAD_REQUEST),

    /** An interval that is not one: negative, inverted, or longer than a batch could cover. */
    MALFORMED_INTERVAL(HttpStatus.BAD_REQUEST),

    /** No playback token, so the batch belongs to no session (ADR-0107). */
    MISSING_ATTRIBUTION(HttpStatus.BAD_REQUEST),

    /**
     * More content than wall clock allows at the fastest rate the player offers.
     *
     * <p>A 400 and not a 403: the honest cause is far more often a clock skew or a laptop resumed
     * from sleep than an attack, and the response a player should have to either is the same —
     * stop resending this batch. What separates them is the count, not the status.
     */
    IMPLAUSIBLE_RATE(HttpStatus.BAD_REQUEST),

    /**
     * The item forbids skipping ahead, and this batch claims coverage past what has been watched.
     *
     * <p>A 409 rather than a 400, because the batch is well formed and the caller is not confused:
     * it is telling us about a seek the same rules told the player not to make.
     */
    SEEK_NOT_ALLOWED(HttpStatus.CONFLICT);

    private final HttpStatus status;

    ProgressRejection(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
