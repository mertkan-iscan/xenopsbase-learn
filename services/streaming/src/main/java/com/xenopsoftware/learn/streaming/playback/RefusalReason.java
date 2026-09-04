package com.xenopsoftware.learn.streaming.playback;

import org.springframework.http.HttpStatus;

/**
 * Why a playback token was not minted (T-3.4) — and, separately, what the caller is told.
 *
 * <p>The two are deliberately not the same thing. Three reasons here answer a plain 404 with
 * no code at all: a caller who is not entitled to a node must not be able to learn, by the
 * shape of the refusal, whether the node exists, whether it is assigned to somebody else, or
 * whether they merely lack the permission. Probing the difference is how an id space gets
 * enumerated, and the caller has no legitimate use for the distinction.
 *
 * <p>The audit does keep the distinction, which is the point of auditing refusals at all:
 * "learners are being refused because nobody granted them content:view" and "learners are being
 * refused because the course was never assigned" are different operational problems, and the
 * response body cannot be where an administrator reads which one they have.
 *
 * <p>The reasons that DO name themselves are the ones the caller can act on: their account is
 * suspended, they are going too fast, the content is gated behind something they have not
 * finished, or the video is not encoded yet. Telling them costs nothing — they already know the
 * thing exists.
 */
public enum RefusalReason {

    /** T-1.4. The status check inside the decision, not the filter at the edge. */
    ACCOUNT_SUSPENDED(HttpStatus.FORBIDDEN, "ACCOUNT_SUSPENDED",
        "This account is suspended. Contact your administrator."),

    /**
     * A read-only account keeps its reads, and a playback token is not one: it is a fresh
     * entitlement that outlives the request that issued it, which is exactly what a wind-down
     * is meant to stop. {@code AccountStatus} has said so since T-1.4.
     */
    ACCOUNT_READ_ONLY(HttpStatus.FORBIDDEN, "ACCOUNT_READ_ONLY",
        "This account is read only. Existing content stays available to view until its current "
        + "playback token expires."),

    /** Too many mints, too fast — token farming rather than watching. */
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "PLAYBACK_RATE_LIMITED",
        "Too many playback requests. Wait a moment and try again."),

    /** The caller holds {@code content:view} nowhere. Rendered as a bare 404. */
    NO_PERMISSION(HttpStatus.NOT_FOUND, null, null),

    /** Catalog has no such node in this tenant. Rendered as a bare 404. */
    UNKNOWN_NODE(HttpStatus.NOT_FOUND, null, null),

    /** The node exists but was never assigned to this learner. Rendered as a bare 404. */
    NOT_ASSIGNED(HttpStatus.NOT_FOUND, null, null),

    /**
     * Assigned, but not yet reachable: a prerequisite is unfinished. Named, and carrying the
     * gate's own sentence, because T-5.3 requires the rule to be readable by the learner it
     * stops — a locked module with no reason is a support ticket.
     */
    GATED(HttpStatus.FORBIDDEN, "CONTENT_GATED",
        "This content is not available yet."),

    /**
     * Entitled, but there is nothing to play: the upload never finished, the encode failed, or
     * the node points at no video. Not a 404 — the learner may see it, it just is not ready.
     */
    NOT_PLAYABLE(HttpStatus.CONFLICT, "NOT_PLAYABLE",
        "This video is not ready to play yet.");

    private final HttpStatus status;
    private final String code;
    private final String message;

    RefusalReason(HttpStatus status, String code, String message) {
        this.status = status;
        this.code = code;
        this.message = message;
    }

    public HttpStatus status() {
        return status;
    }

    /** The machine-readable code for the caller, or null when the refusal discloses nothing. */
    public String code() {
        return code;
    }

    public String message() {
        return message;
    }

    /** Whether the caller is told anything beyond the status. */
    public boolean isDisclosed() {
        return code != null;
    }
}
