package com.xenopsoftware.learn.reporting.telemetry;

import org.springframework.http.HttpStatus;

/**
 * Why a batch was refused, as a code the client can act on (T-3.6, and T-7.1's "not a 500").
 *
 * <p>Specific rather than generic, because the client's correct response differs per reason and a
 * 500 tells it nothing: a batch too large should be split and resent, a malformed one should be
 * dropped rather than retried forever, and a rate that cannot be true should stop being sent. A
 * client that cannot tell those apart retries all of them, which turns one broken player into
 * sustained load.
 *
 * <p>Every one of these is counted. A rejection rate that is normally zero and suddenly is not is
 * how a player released with a bug becomes visible before the reports do.
 */
public enum RejectionReason {

    /** More samples than one post may carry. Split it. */
    BATCH_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE),

    /** No samples at all — nothing to record, and a bug in whatever sent it. */
    EMPTY_BATCH(HttpStatus.BAD_REQUEST),

    /** An interval that is not an interval: negative, inverted, or longer than a batch could cover. */
    MALFORMED_INTERVAL(HttpStatus.BAD_REQUEST),

    /** A playback rate outside anything a player offers. */
    IMPLAUSIBLE_RATE(HttpStatus.BAD_REQUEST),

    /** No node, or no playback token to attribute the batch to. */
    MISSING_ATTRIBUTION(HttpStatus.BAD_REQUEST);

    private final HttpStatus status;

    RejectionReason(HttpStatus status) {
        this.status = status;
    }

    public HttpStatus status() {
        return status;
    }
}
